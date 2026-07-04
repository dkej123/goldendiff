package com.github.dkwasniak.goldendiff.match

import java.io.File

/**
 * Scans the configured screenshot directories for PNG golden files whose name matches any of the
 * candidate names taken from the current editor.
 */
object GoldenFinder {

    fun find(
        roots: List<File>,
        screen: CurrentScreen.Screen,
        excludedSuffixes: List<String> = emptyList(),
    ): List<File> {
        val candidates = screen.names
            .map { it.trim() }
            .filter { isUsefulCandidate(it) }
            .distinct()
        if (candidates.isEmpty()) return emptyList()
        val suffixes = excludedSuffixes.filter { it.isNotBlank() }

        val matches = LinkedHashSet<File>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
                .filter { file -> suffixes.none { file.nameWithoutExtension.endsWith(it) } }
                .filter { file ->
                    candidates.any { file.name.matchesCandidate(it) }
                }
                .forEach { matches.add(it) }
        }

        // Sort so that the golden matching the caret symbol comes first, then by name.
        val caret = screen.caretName?.trim()?.takeIf { isUsefulCandidate(it) }
        return matches.sortedWith(
            compareByDescending<File> { caret != null && it.name.matchesCandidate(caret) }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun isUsefulCandidate(name: String): Boolean =
        name.length >= MIN_CANDIDATE_LENGTH && name.lowercase() !in GENERIC_CANDIDATES

    private fun String.matchesCandidate(candidate: String): Boolean {
        var start = indexOf(candidate, ignoreCase = true)
        while (start >= 0) {
            val endExclusive = start + candidate.length
            if (hasCandidateBoundaryBefore(start) && hasCandidateBoundaryAfter(endExclusive)) return true
            start = indexOf(candidate, startIndex = start + 1, ignoreCase = true)
        }
        return false
    }

    private fun String.hasCandidateBoundaryBefore(start: Int): Boolean =
        start == 0 ||
            !this[start - 1].isLetterOrDigit() ||
            this[start - 1].isLowerCase() && this[start].isUpperCase()

    private fun String.hasCandidateBoundaryAfter(endExclusive: Int): Boolean =
        endExclusive == length ||
            !this[endExclusive].isLetterOrDigit() ||
            this[endExclusive].isUpperCase()

    private const val MIN_CANDIDATE_LENGTH = 3

    private val GENERIC_CANDIDATES = setOf(
        "content",
        "model",
        "preview",
        "previews",
        "root",
        "screen",
        "state",
        "states",
        "ui",
        "uistate",
        "view",
    )
}
