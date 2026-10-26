package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.scan.MergedGoldenStatus
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus

/**
 * Status rendered by the Files / Review shell. It extends the indexed status with the plugin-only,
 * session-scoped [ACCEPTED] overlay; [rank] orders the "worst status wins" aggregation.
 */
internal enum class GoldenDisplayStatus(
    val rank: Int,
    val badgeLabel: String?,
    val statusText: String,
) {
    FAILED(4, "Failed", "Failed — test output differs"),
    MODIFIED(3, "Modified", "Modified — working copy"),
    NEW(2, "New", "New — not in HEAD"),
    ACCEPTED(1, "Accepted", "Accepted — saved as golden"),
    UNCHANGED(0, null, "Unchanged"),
}

/** A failing golden always shows as Failed; otherwise an accepted golden shows as Accepted. */
internal fun goldenDisplayStatus(item: MergedGoldenItem, accepted: Boolean): GoldenDisplayStatus {
    val status = item.displayStatus.toDisplayStatus()
    return if (accepted && status != GoldenDisplayStatus.FAILED) GoldenDisplayStatus.ACCEPTED else status
}

internal fun worstDisplayStatus(statuses: Iterable<GoldenDisplayStatus>): GoldenDisplayStatus =
    statuses.maxByOrNull(GoldenDisplayStatus::rank) ?: GoldenDisplayStatus.UNCHANGED

internal fun MergedGoldenStatus.toDisplayStatus(): GoldenDisplayStatus = when (this) {
    MergedGoldenStatus.FAILED -> GoldenDisplayStatus.FAILED
    MergedGoldenStatus.MODIFIED -> GoldenDisplayStatus.MODIFIED
    MergedGoldenStatus.NEW -> GoldenDisplayStatus.NEW
    MergedGoldenStatus.UNCHANGED -> GoldenDisplayStatus.UNCHANGED
}

internal fun SnapshotFileStatus.toDisplayStatus(): GoldenDisplayStatus = when (this) {
    SnapshotFileStatus.FAILED -> GoldenDisplayStatus.FAILED
    SnapshotFileStatus.MODIFIED -> GoldenDisplayStatus.MODIFIED
    SnapshotFileStatus.NEW -> GoldenDisplayStatus.NEW
    SnapshotFileStatus.UNCHANGED -> GoldenDisplayStatus.UNCHANGED
}

internal fun ExtraComparisonItemStatus.toDisplayStatus(): GoldenDisplayStatus = when (this) {
    ExtraComparisonItemStatus.MODIFIED -> GoldenDisplayStatus.MODIFIED
    ExtraComparisonItemStatus.NEW -> GoldenDisplayStatus.NEW
    ExtraComparisonItemStatus.UNCHANGED -> GoldenDisplayStatus.UNCHANGED
}

internal enum class AcceptButtonState { ENABLED, DISABLED, ACCEPTING, ACCEPTED }

/**
 * Accept stays visible in the mode row: it promotes only an acceptable built-in Test output, so an
 * extension card or a working-copy-only change renders it disabled. A new failing output re-enables it.
 */
internal fun acceptButtonState(item: GoldenGridItem?, accepted: Boolean, accepting: Boolean): AcceptButtonState =
    when {
        accepting -> AcceptButtonState.ACCEPTING
        (item as? GoldenGridItem.BuiltIn)?.item?.hasAcceptableTestOutput == true -> AcceptButtonState.ENABLED
        accepted && item is GoldenGridItem.BuiltIn -> AcceptButtonState.ACCEPTED
        else -> AcceptButtonState.DISABLED
    }
