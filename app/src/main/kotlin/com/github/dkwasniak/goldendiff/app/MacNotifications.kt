package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.app.mac.MacUserNotifications
import com.github.dkwasniak.goldendiff.platform.Os
import java.awt.KeyboardFocusManager

/**
 * Host adapter around macOS Notification Center.
 *
 * Two backends. The packaged app posts through `UNUserNotificationCenter`, which is what gives it a
 * row in System Settings → Notifications and an icon on the banner. A development run has no bundle
 * of its own, so it falls back to `osascript` — notifications then arrive under the scripting host's
 * identity, or not at all, but the code path stays exercised without a `packageDmg` round trip.
 */
object MacNotifications {

    /** Which backend a delivery would use; surfaced in diagnostics, where "nothing happened" is the bug. */
    enum class Backend { NOTIFICATION_CENTER, APPLESCRIPT, UNSUPPORTED }

    /** Notification Center's view of us. Only meaningful for [Backend.NOTIFICATION_CENTER]. */
    enum class Authorization {
        NOT_DETERMINED,
        DENIED,
        AUTHORIZED,
        /** Not asked yet, framework unreachable, or a status this build does not model. */
        UNKNOWN,
    }

    val backend: Backend
        get() = when {
            Os.current != Os.MAC -> Backend.UNSUPPORTED
            MacUserNotifications.available -> Backend.NOTIFICATION_CENTER
            else -> Backend.APPLESCRIPT
        }

    fun hostForeground(): Boolean =
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null

    /**
     * Registers with Notification Center, prompting for permission the first time.
     *
     * Called at startup: until the app has asked once it does not exist as far as System Settings is
     * concerned, so a user who wants to enable notifications up front has nothing to enable.
     */
    fun register(onResult: (granted: Boolean, error: String?) -> Unit = { _, _ -> }) {
        if (backend != Backend.NOTIFICATION_CENTER) return
        MacUserNotifications.requestAuthorization(onResult)
    }

    /** Runs [handler] when the user clicks a delivered notification. Notification Center backend only. */
    fun onNotificationClicked(handler: () -> Unit) {
        MacUserNotifications.onActivated = handler
    }

    fun authorizationStatus(onResult: (Authorization) -> Unit) {
        MacUserNotifications.authorizationStatus(onResult)
    }

    fun deliver(title: String, body: String) {
        if (backend == Backend.UNSUPPORTED) return
        // Counted before delivery: the badge is the record that survives the banner timing out, and it
        // should be there whichever backend ends up carrying the notification.
        AppBadge.increment()
        when (backend) {
            Backend.NOTIFICATION_CENTER -> if (MacUserNotifications.deliver(title, body)) return
            else -> Unit
        }
        deliverViaAppleScript(title, body)
    }

    fun openSystemSettings() {
        if (Os.current != Os.MAC) return
        runCatching {
            ProcessBuilder(
                "/usr/bin/open",
                "x-apple.systempreferences:com.apple.Notifications-Settings.extension",
            ).start()
        }
    }

    private fun deliverViaAppleScript(title: String, body: String) {
        val script = "display notification ${appleString(body)} with title ${appleString(title)}"
        runCatching {
            ProcessBuilder("/usr/bin/osascript", "-e", script)
                .redirectErrorStream(true)
                .start()
        }
    }

    private fun appleString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
