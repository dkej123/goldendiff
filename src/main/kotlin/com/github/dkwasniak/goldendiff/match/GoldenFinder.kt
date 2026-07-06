package com.github.dkwasniak.goldendiff.match

import java.io.File

/**
 * Scans the configured screenshot directories for PNG golden files that match the file open in the
 * editor, using one of two mutually exclusive [MatchMode]s.
 *
 * Matching is performed against each golden's path relative to its root (with `/` separators), not
 * just the file name, so layouts that nest the class or package as directories still match.
 */
object GoldenFinder {

    fun find(
        roots: List<File>,
        screen: CurrentScreen.Screen,
        mode: MatchMode = MatchingDefaults.DEFAULT_MATCH_MODE,
        excludedSuffixes: List<String> = emptyList(),
        generalPatterns: List<String> = MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS,
    ): List<File> {
        val suffixes = excludedSuffixes.filter { it.isNotBlank() }

        val methodCandidates = screen.functionNames
            .map { it.trim() }
            .filter { isUsefulCandidate(it) }
            .distinct()
        val patterns = generalPatterns.map { it.trim() }.filter { it.isNotEmpty() }
        if (mode == MatchMode.ANNOTATED_METHOD && methodCandidates.isEmpty()) return emptyList()
        if (mode == MatchMode.FILE_CLASS_REGEX && patterns.isEmpty()) return emptyList()

        val matches = LinkedHashSet<File>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
                .filter { file -> suffixes.none { file.nameWithoutExtension.endsWith(it) } }
                .filter { file ->
                    val relativePath = file.relativeTo(root).invariantPath()
                    when (mode) {
                        MatchMode.ANNOTATED_METHOD ->
                            methodCandidates.any { relativePath.matchesCandidate(it, allowCamelSuffix = false) }
                        MatchMode.FILE_CLASS_REGEX ->
                            patterns.any { relativePath.matchesExpandedPattern(it, screen) }
                    }
                }
                .forEach { matches.add(it) }
        }

        // Sort so that the golden matching the caret symbol comes first (annotated-method mode only),
        // then by name.
        val caret = screen.caretName?.trim()?.takeIf { isUsefulCandidate(it) }
            ?.takeIf { mode == MatchMode.ANNOTATED_METHOD }
        return matches.sortedWith(
            compareByDescending<File> {
                caret != null && it.invariantPathIn(roots).matchesCandidate(caret, allowCamelSuffix = false)
            }.thenBy { it.name.lowercase() }
        )
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    private fun File.invariantPathIn(roots: List<File>): String {
        val root = roots.firstOrNull { isDescendantOf(it) }
        return (if (root != null) relativeTo(root) else this).invariantPath()
    }

    private fun File.isDescendantOf(root: File): Boolean =
        path.startsWith(root.path + File.separatorChar)

    private fun String.matchesExpandedPattern(pattern: String, screen: CurrentScreen.Screen): Boolean {
        val classAlternatives = screen.classNames
            .filter { isUsefulCandidate(it) }
            .joinToString("|") { Regex.escape(it) }
            .ifBlank { "(?!)" }
        val expanded = pattern
            .replace("{file_name}", Regex.escape(screen.fileName))
            .replace("{class_name}", "(?:$classAlternatives)")
        val regex = runCatching { Regex(expanded, RegexOption.IGNORE_CASE) }.getOrNull() ?: return false
        return regex.containsMatchIn(this)
    }

    private fun isUsefulCandidate(name: String): Boolean =
        name.length >= MIN_CANDIDATE_LENGTH && name.lowercase() !in GENERIC_CANDIDATES

    private fun String.matchesCandidate(candidate: String, allowCamelSuffix: Boolean): Boolean {
        var start = indexOf(candidate, ignoreCase = true)
        while (start >= 0) {
            val endExclusive = start + candidate.length
            if (
                hasCandidateBoundaryBefore(start) &&
                hasCandidateBoundaryAfter(endExclusive, allowCamelSuffix)
            ) {
                return true
            }
            start = indexOf(candidate, startIndex = start + 1, ignoreCase = true)
        }
        return false
    }

    private fun String.hasCandidateBoundaryBefore(start: Int): Boolean =
        start == 0 ||
            !this[start - 1].isLetterOrDigit() ||
            this[start - 1].isLowerCase() && this[start].isUpperCase()

    private fun String.hasCandidateBoundaryAfter(endExclusive: Int, allowCamelSuffix: Boolean): Boolean =
        endExclusive == length ||
            !this[endExclusive].isLetterOrDigit() ||
            allowCamelSuffix && this[endExclusive].isUpperCase()

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
