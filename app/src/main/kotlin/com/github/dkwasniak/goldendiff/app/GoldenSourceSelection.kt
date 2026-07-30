package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem

/**
 * Resolves the source shown for the active golden without involving Compose state.
 *
 * A changed or new Test output is the actionable failure and therefore wins. All other comparisons
 * use Working copy. If the preferred variant is absent, the only available built-in variant is used.
 */
internal fun automaticGoldenSource(item: MergedGoldenItem?): BuiltInSource {
    val preferred = if (item?.hasAcceptableTestOutput == true) {
        BuiltInSource.GENERATED
    } else {
        BuiltInSource.WORKING_COPY
    }
    if (item?.variant(preferred) != null) return preferred

    val fallback = if (preferred == BuiltInSource.GENERATED) {
        BuiltInSource.WORKING_COPY
    } else {
        BuiltInSource.GENERATED
    }
    return fallback.takeIf { item?.variant(it) != null } ?: preferred
}
