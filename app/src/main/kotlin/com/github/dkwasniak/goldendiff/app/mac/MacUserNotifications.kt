package com.github.dkwasniak.goldendiff.app.mac

import com.github.dkwasniak.goldendiff.app.MacNotifications.Authorization
import com.github.dkwasniak.goldendiff.platform.Os
import com.sun.jna.Callback
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicLong

/**
 * Notification Center via `UNUserNotificationCenter`, the framework macOS actually recognises.
 *
 * The app used to shell out to `osascript "display notification"`. That is attributed to the
 * scripting host rather than to the calling process, so Golden Diff never appeared in System Settings
 * → Notifications and — on macOS 26, where those notifications are suppressed by default — nothing
 * was delivered at all. `osascript` exits 0 either way, so the failure was invisible.
 *
 * Posting through the framework fixes both: the app registers under its own bundle identifier on the
 * first authorization request, gets its own row in System Settings, and its notifications carry the
 * app icon and can be clicked.
 *
 * The hard requirement is a bundle: `currentNotificationCenter` raises rather than returning nil when
 * the process has no bundle identifier of its own. A `./gradlew :app:run` process is plain `java`, so
 * [available] is false there and the caller falls back — see [com.github.dkwasniak.goldendiff.app.MacNotifications].
 */
internal object MacUserNotifications {

    /** Set by the app so a clicked notification can raise the relevant window. */
    @Volatile
    var onActivated: (() -> Unit)? = null

    private val identifiers = AtomicLong()

    private val bundleIdentifier: String? by lazy {
        if (Os.current != Os.MAC) {
            null
        } else {
            runCatching {
                ObjC.stringFrom(ObjC.send(ObjC.send(ObjC.classNamed("NSBundle"), "mainBundle"), "bundleIdentifier"))
            }.getOrNull()
        }
    }

    /**
     * True only inside the packaged app.
     *
     * The identifier is matched against our own prefix rather than merely being non-null: a JDK
     * launcher can present its own bundle, and posting against someone else's identity would put the
     * notification — and the System Settings row — under that name.
     */
    val available: Boolean by lazy {
        Os.current == Os.MAC &&
            bundleIdentifier?.startsWith(BUNDLE_PREFIX) == true &&
            ObjC.loadFramework(FRAMEWORK) &&
            center != null
    }

    private val center: Pointer? by lazy {
        runCatching {
            ObjC.send(ObjC.classNamed("UNUserNotificationCenter"), "currentNotificationCenter")
        }.getOrNull()?.takeIf { Pointer.nativeValue(it) != 0L }
    }

    /**
     * Asks for permission, registering the app with Notification Center as a side effect.
     *
     * This is what puts Golden Diff in the System Settings list, so it runs at startup even though
     * nothing is being posted yet — the row has to exist before a user can go looking for it.
     */
    fun requestAuthorization(onResult: (granted: Boolean, error: String?) -> Unit) {
        if (!available) {
            onResult(false, "Notification Center is only available in the packaged app.")
            return
        }
        val callback = object : AuthorizationCallback {
            override fun invoke(block: Pointer, granted: Byte, error: Pointer?) {
                val message = error?.takeIf { Pointer.nativeValue(it) != 0L }
                    ?.let { ObjC.stringFrom(ObjC.send(it, "localizedDescription")) }
                onResult(granted.toInt() != 0, message)
            }
        }
        runCatching {
            ObjC.sendVoid(
                center,
                "requestAuthorizationWithOptions:completionHandler:",
                ALERT or SOUND,
                ObjC.block(callback, "v@?B@"),
            )
        }.onFailure { onResult(false, it.message) }
    }

    /** Asynchronous because the framework only exposes the settings through a completion block. */
    fun authorizationStatus(onResult: (Authorization) -> Unit) {
        if (!available) {
            onResult(Authorization.UNKNOWN)
            return
        }
        val callback = object : SettingsCallback {
            override fun invoke(block: Pointer, settings: Pointer?) {
                onResult(
                    when (ObjC.sendLong(settings, "authorizationStatus")) {
                        0L -> Authorization.NOT_DETERMINED
                        1L -> Authorization.DENIED
                        // Provisional and ephemeral both deliver, so they are reported as authorized.
                        2L, 3L, 4L -> Authorization.AUTHORIZED
                        else -> Authorization.UNKNOWN
                    },
                )
            }
        }
        runCatching {
            ObjC.sendVoid(center, "getNotificationSettingsWithCompletionHandler:", ObjC.block(callback, "v@?@"))
        }.onFailure { onResult(Authorization.UNKNOWN) }
    }

    /** Returns false if the post could not even be handed to the framework; delivery itself is its call. */
    fun deliver(title: String, body: String): Boolean {
        if (!available) return false
        installDelegate()
        return runCatching {
            val content = ObjC.send(ObjC.send(ObjC.classNamed("UNMutableNotificationContent"), "alloc"), "init")
            ObjC.sendVoid(content, "setTitle:", ObjC.nsString(title))
            ObjC.sendVoid(content, "setBody:", ObjC.nsString(body))
            ObjC.sendVoid(content, "setSound:", ObjC.send(ObjC.classNamed("UNNotificationSound"), "defaultSound"))
            val request = ObjC.send(
                ObjC.classNamed("UNNotificationRequest"),
                "requestWithIdentifier:content:trigger:",
                // No trigger: deliver immediately.
                ObjC.nsString("golden-diff-${identifiers.incrementAndGet()}"),
                content,
                null,
            )
            // A nil completion handler is explicitly allowed here, which saves synthesising a block.
            ObjC.sendVoid(center, "addNotificationRequest:withCompletionHandler:", request, null)
            ObjC.sendVoid(content, "release")
            true
        }.getOrDefault(false)
    }

    /**
     * Registers the delegate that receives clicks.
     *
     * Deferred to the first delivery rather than done at startup: the class pair is created once and
     * only matters when a notification can actually be clicked.
     */
    @Synchronized
    private fun installDelegate() {
        if (delegateInstalled) return
        delegateInstalled = true
        val callback = object : ResponseCallback {
            override fun invoke(
                self: Pointer,
                selector: Pointer,
                notificationCenter: Pointer?,
                response: Pointer?,
                completionHandler: Pointer?,
            ) {
                runCatching {
                    // Clicking a notification does not raise the app on its own.
                    ObjC.sendVoid(
                        ObjC.send(ObjC.classNamed("NSApplication"), "sharedApplication"),
                        "activateIgnoringOtherApps:",
                        1,
                    )
                    onActivated?.invoke()
                }
                // The framework waits on this before considering the response handled.
                ObjC.invokeBlock(completionHandler)
            }
        }
        // Without this, a notification posted while Golden Diff is the active app is filed straight
        // into Notification Center with no banner. macOS asks the delegate what to do and takes
        // silence as "show nothing".
        val presentation = object : ResponseCallback {
            override fun invoke(
                self: Pointer,
                selector: Pointer,
                notificationCenter: Pointer?,
                notification: Pointer?,
                completionHandler: Pointer?,
            ) {
                ObjC.invokeBlock(completionHandler, BANNER or LIST or SOUND)
            }
        }
        val delegate = ObjC.defineDelegate(
            "GoldenDiffNotificationDelegate",
            mapOf(
                "userNotificationCenter:didReceiveNotificationResponse:withCompletionHandler:" to
                    (callback to "v@:@@@"),
                "userNotificationCenter:willPresentNotification:withCompletionHandler:" to
                    (presentation to "v@:@@@"),
            ),
        )
        if (delegate != null) ObjC.sendVoid(center, "setDelegate:", delegate)
    }

    @Volatile
    private var delegateInstalled = false

    /** JNA maps each callback interface by its single method, so one interface per block signature. */
    private interface AuthorizationCallback : Callback {
        fun invoke(block: Pointer, granted: Byte, error: Pointer?)
    }

    private interface SettingsCallback : Callback {
        fun invoke(block: Pointer, settings: Pointer?)
    }

    private interface ResponseCallback : Callback {
        fun invoke(
            self: Pointer,
            selector: Pointer,
            notificationCenter: Pointer?,
            response: Pointer?,
            completionHandler: Pointer?,
        )
    }

    private const val BUNDLE_PREFIX = "com.github.dkwasniak.goldendiff"
    private const val FRAMEWORK = "/System/Library/Frameworks/UserNotifications.framework/UserNotifications"
    private const val ALERT = 4L
    private const val SOUND = 2L

    /** `UNNotificationPresentationOptions`: banner plus an entry in Notification Center. */
    private const val BANNER = 8L
    private const val LIST = 4L
}
