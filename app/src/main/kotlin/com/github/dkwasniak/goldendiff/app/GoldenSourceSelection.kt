package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem

/**
 * Resolves the source shown for the active golden without involving Compose state.
 *
 * A still-available remembered choice wins. New dual-source comparisons prefer Test output, and a source
 * removed by a watcher refresh falls back to the remaining built-in source.
 */
internal fun preferredGoldenSource(
    item: MergedGoldenItem?,
    remembered: BuiltInSource?,
): BuiltInSource = when {
    remembered != null && item?.variant(remembered) != null -> remembered
    item?.testOutput != null -> BuiltInSource.GENERATED
    else -> BuiltInSource.WORKING_COPY
}
