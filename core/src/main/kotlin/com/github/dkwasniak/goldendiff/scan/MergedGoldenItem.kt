package com.github.dkwasniak.goldendiff.scan

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
) {
    val displayStatus: MergedGoldenStatus
        get() = when {
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
