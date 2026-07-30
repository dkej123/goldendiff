package com.github.dkwasniak.goldendiff.scan

import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartResolution
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import java.io.File

/**
 * One golden with the two built-in comparison sources kept independently.
 *
 * A null variant means that source does not exist. In particular, a missing generated counterpart
 * must not be represented as UNCHANGED because the UI needs to distinguish "identical" from
 * "there is no Test output".
 */
data class GoldenSourceVariant(
    val file: File,
    val status: ExtraComparisonItemStatus,
)

enum class MergedGoldenStatus {
    FAILED,
    MODIFIED,
    NEW,
    UNCHANGED,
}

data class MergedGoldenItem(
    val golden: File,
    val workingCopy: GoldenSourceVariant?,
    val testOutput: GoldenSourceVariant?,
    val testOutputResolution: GeneratedCounterpartResolution =
        testOutput?.let { GeneratedCounterpartResolution.Exact(it.file) }
            ?: GeneratedCounterpartResolution.Missing,
) {
    val displayStatus: MergedGoldenStatus
        get() = when {
            testOutputResolution is GeneratedCounterpartResolution.Ambiguous -> MergedGoldenStatus.FAILED
            testOutput?.status == ExtraComparisonItemStatus.MODIFIED ||
                testOutput?.status == ExtraComparisonItemStatus.NEW -> MergedGoldenStatus.FAILED
            workingCopy?.status == ExtraComparisonItemStatus.MODIFIED -> MergedGoldenStatus.MODIFIED
            workingCopy?.status == ExtraComparisonItemStatus.NEW -> MergedGoldenStatus.NEW
            else -> MergedGoldenStatus.UNCHANGED
        }

    val hasAcceptableTestOutput: Boolean
        get() = testOutput?.status == ExtraComparisonItemStatus.MODIFIED ||
            testOutput?.status == ExtraComparisonItemStatus.NEW

    fun variant(source: BuiltInSource): GoldenSourceVariant? = when (source) {
        BuiltInSource.WORKING_COPY -> workingCopy
        BuiltInSource.GENERATED -> testOutput
    }
}

/**
 * Selects the built-in source that should be compared for [item].
 *
 * A changed or new Test output is the actionable failure and therefore wins. Otherwise Working
 * copy wins. When the preferred variant is absent, the only available built-in variant is used.
 * A null item (or an item without variants) returns Working copy as the stable default.
 */
fun automaticBuiltInSource(item: MergedGoldenItem?): BuiltInSource {
    val preferred = if (item?.hasAcceptableTestOutput == true) {
        BuiltInSource.GENERATED
    } else {
        BuiltInSource.WORKING_COPY
    }
    if (item?.variant(preferred) != null) return preferred

    val fallback = when (preferred) {
        BuiltInSource.WORKING_COPY -> BuiltInSource.GENERATED
        BuiltInSource.GENERATED -> BuiltInSource.WORKING_COPY
    }
    return fallback.takeIf { item?.variant(it) != null } ?: preferred
}
