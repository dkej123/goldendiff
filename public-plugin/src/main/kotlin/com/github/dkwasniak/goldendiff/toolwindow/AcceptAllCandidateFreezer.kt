package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.command.GoldenAccept
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem

/** Freezes every source covered by a confirmed View-scoped Accept all operation. */
internal fun freezeAcceptAllCandidates(
    candidates: List<MergedGoldenItem>,
    displayedCandidates: Map<String, GoldenAccept.FrozenCandidate>,
): List<GoldenAccept.FrozenCandidate> = candidates.map { item ->
    val key = item.golden.normalize().path
    displayedCandidates[key]
        ?: item.testOutput?.file?.let { GoldenAccept.freeze(item.golden, it).getOrNull() }
        ?: GoldenAccept.FrozenCandidate(item.golden, GoldenAccept.FrozenSource.Missing)
}
