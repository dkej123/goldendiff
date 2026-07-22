package com.github.dkwasniak.goldendiff.variant

import java.io.File

/**
 * How a golden compares to its committed version.
 *
 * Ordering matters to the UI: lists are sorted changed-first, then new, then unchanged, so the files
 * a reviewer actually has to look at come first (see `ChangeScanner.statusSortRank`).
 */
enum class ExtraComparisonItemStatus {
    UNCHANGED,
    MODIFIED,
    NEW,
}

/**
 * One row in the golden list.
 *
 * Pure data with no host types, so the same scan results drive the plugin's tool window and the
 * standalone app's list.
 *
 * [isLoading] defaults to "the file is not on disk yet", which covers sources that fetch lazily —
 * the Figma source lists a reference before it has finished downloading it.
 */
data class ExtraComparisonItem(
    val file: File,
    val title: String = file.name,
    val isLoading: Boolean = !file.isFile,
    val status: ExtraComparisonItemStatus = ExtraComparisonItemStatus.UNCHANGED,
)
