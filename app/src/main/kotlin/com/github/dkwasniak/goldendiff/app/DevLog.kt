package com.github.dkwasniak.goldendiff.app

import androidx.compose.runtime.mutableStateListOf
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** One timestamped line in the developer-build diagnostics panel. */
data class DevLogEntry(val time: String, val category: String, val message: String)

/**
 * In-memory diagnostic log for developer builds. Captures analytics events (even when suppressed by
 * consent), update checks, install steps and uncaught exceptions so a developer can watch what the
 * app is doing — nothing leaves the machine. [enabled] is false in release builds, so recording is a
 * no-op there and the panel is never offered.
 */
object DevLog {
    private const val MAX_ENTRIES = 1000
    private val format = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    var enabled: Boolean = false

    val entries = mutableStateListOf<DevLogEntry>()

    @Synchronized
    fun record(category: String, message: String) {
        if (!enabled) return
        entries.add(DevLogEntry(LocalTime.now().format(format), category, message))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    @Synchronized
    fun clear() = entries.clear()
}
