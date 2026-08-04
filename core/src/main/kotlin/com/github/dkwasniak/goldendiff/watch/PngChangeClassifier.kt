package com.github.dkwasniak.goldendiff.watch

import java.io.File

enum class PngChangeKind { CREATED, MODIFIED, DELETED }

data class PngChangeEvent(val file: File, val kind: PngChangeKind)

enum class PngRefreshMode { NONE, IN_PLACE, RESCAN }

/**
 * Chooses the state transition for a debounced, already-filtered watcher batch.
 *
 * Structural changes can alter list membership and therefore require an atomic soft rescan.
 * Modifying a PNG that is already on screen can update its merged item in place.
 */
fun pngRefreshMode(events: List<PngChangeEvent>, overflow: Boolean): PngRefreshMode = when {
    !overflow && events.isEmpty() -> PngRefreshMode.NONE
    overflow || events.any { it.kind != PngChangeKind.MODIFIED } -> PngRefreshMode.RESCAN
    else -> PngRefreshMode.IN_PLACE
}

/** Shared debounce window for both hosts' PNG watchers, tuned against Generate/Verify runs that
 * write hundreds of files over several seconds. */
const val PNG_REFRESH_DEBOUNCE_MS: Long = 400L
