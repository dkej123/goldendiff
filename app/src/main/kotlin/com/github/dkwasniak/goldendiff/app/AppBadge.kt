package com.github.dkwasniak.goldendiff.app

import java.awt.Taskbar
import java.util.concurrent.atomic.AtomicInteger

/**
 * The unread count on the Dock icon.
 *
 * Counts notifications the user has not come back to yet, so a run that finished while Golden Diff
 * was hidden leaves a visible trace even after the banner times out.
 *
 * `java.awt.Taskbar` rather than the notification framework's own badge: a `UNNotificationContent`
 * badge is owned by Notification Center, which never clears it on its own, and it needs the badge
 * authorization on top of alerts. This is the same Dock badge, minus both problems.
 */
object AppBadge {
    private val pending = AtomicInteger()

    private val taskbar: Taskbar? by lazy {
        runCatching {
            Taskbar.getTaskbar().takeIf { it.isSupported(Taskbar.Feature.ICON_BADGE_TEXT) }
        }.getOrNull()
    }

    fun increment() {
        apply(pending.incrementAndGet())
    }

    /** Called when the user looks at the app; anything pending has by definition been seen. */
    fun clear() {
        if (pending.getAndSet(0) == 0) return
        apply(0)
    }

    private fun apply(count: Int) {
        // Null text removes the badge; an empty string would leave an empty red dot.
        runCatching { taskbar?.setIconBadge(if (count > 0) count.toString() else null) }
    }
}
