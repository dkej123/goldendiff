package com.github.dkwasniak.goldendiff.project

/**
 * IntelliJ-style "Go to File" matching: subsequence matching over the file name, with camel-hump
 * queries (`LSTest` finds `LoginScreenTest.kt`) and a ranking that puts the file a developer meant
 * first.
 *
 * Deliberately matches against the file NAME rather than the whole path. Matching the full path
 * sounds more generous but is much worse in practice: a query like `test` hits every file under a
 * `src/test/` directory, drowning the file actually called `…Test.kt`. Paths are used only to break
 * ties.
 */
object FuzzyFileMatcher {

    data class Match(val path: String, val score: Int)

    /**
     * Ranked matches for [query], best first.
     *
     * A blank query returns nothing rather than everything — the caller shows recent files or an
     * empty popup instead of dumping the whole index.
     */
    fun search(paths: List<String>, query: String, limit: Int = 50): List<Match> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return paths.asSequence()
            .mapNotNull { path -> score(path, trimmed)?.let { Match(path, it) } }
            // Higher score first; ties broken by shorter path, then alphabetically, so the result is
            // stable and identical on every platform.
            .sortedWith(
                compareByDescending<Match> { it.score }
                    .thenBy { it.path.length }
                    .thenBy { it.path },
            )
            .take(limit)
            .toList()
    }

    /** Score for one path, or null when the query does not match at all. Higher is better. */
    fun score(path: String, query: String): Int? {
        val name = path.substringAfterLast('/')
        val nameScore = scoreAgainst(name, query) ?: return scoreAgainst(path, query)?.let { it - PATH_ONLY_PENALTY }
        return nameScore
    }

    private fun scoreAgainst(candidate: String, query: String): Int? {
        val exact = candidate.equals(query, ignoreCase = true)
        if (exact) return 1000

        val prefix = candidate.startsWith(query, ignoreCase = true)
        if (prefix) return 700 - candidate.length.coerceAtMost(200)

        // Initials of CamelCase words are a first-class match: LDS should find
        // LocationDetailsScreen.kt, just like IntelliJ's Go to File.
        val acronym = camelHumpAcronym(candidate)
        if (acronym.startsWith(query, ignoreCase = true)) {
            return 800 - candidate.length.coerceAtMost(200)
        }

        val contains = candidate.contains(query, ignoreCase = true)
        if (contains) return 500 - candidate.length.coerceAtMost(200)

        return subsequenceScore(candidate, query)
    }

    /**
     * Matches [query] as a subsequence of [candidate], rewarding characters that land on a "hump" —
     * an upper-case letter or the character after a separator. That is what makes `LSTest` prefer
     * `LoginScreenTest` over some file that merely happens to contain those letters in order.
     */
    private fun subsequenceScore(candidate: String, query: String): Int? {
        var ci = 0
        var humps = 0
        var consecutive = 0
        var lastMatch = -2
        for (qc in query) {
            var found = -1
            while (ci < candidate.length) {
                if (candidate[ci].equalsIgnoreCase(qc)) {
                    found = ci
                    ci++
                    break
                }
                ci++
            }
            if (found < 0) return null
            if (isHump(candidate, found)) humps++
            if (found == lastMatch + 1) consecutive++
            lastMatch = found
        }
        // Density matters: the same letters spread across a long name is a weaker signal than a tight
        // run in a short one.
        val density = (query.length * 100) / candidate.length.coerceAtLeast(1)
        return humps * 20 + consecutive * 10 + density
    }

    private fun isHump(text: String, index: Int): Boolean {
        if (index == 0) return true
        val c = text[index]
        val prev = text[index - 1]
        if (!prev.isLetterOrDigit()) return true
        return c.isUpperCase() && !prev.isUpperCase()
    }

    private fun camelHumpAcronym(candidate: String): String {
        val name = candidate.substringAfterLast('/').substringBeforeLast('.')
        return buildString {
            name.indices.filter { isHump(name, it) }.forEach { append(name[it]) }
        }
    }

    private fun Char.equalsIgnoreCase(other: Char): Boolean =
        this == other || lowercaseChar() == other.lowercaseChar()

    /** Applied when only the directory part matched, so name matches always outrank path matches. */
    private const val PATH_ONLY_PENALTY = 400
}
