package com.github.dkwasniak.goldendiff.version

/**
 * A Semantic Version, parsed leniently enough for our release tags (`1.5.0`, `1.5.0-beta.4`) and
 * ordered per the SemVer spec: a release outranks a prerelease of the same core; prerelease
 * identifiers compare numerically when both numeric, otherwise lexically, and a longer identifier
 * list wins ties. Build metadata (`+...`) is ignored, as the spec requires.
 *
 * Shared by the standalone app (GitHub-release check) and the plugin (Marketplace check), so both
 * decide "is this newer?" the same way.
 */
class SemVer private constructor(
    private val core: List<Int>,
    private val pre: List<String>,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        val width = maxOf(core.size, other.core.size)
        for (i in 0 until width) {
            val diff = core.getOrElse(i) { 0 }.compareTo(other.core.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        // A build with no prerelease tag is the final release and outranks any prerelease.
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1
        if (other.pre.isEmpty()) return -1
        val shared = minOf(pre.size, other.pre.size)
        for (i in 0 until shared) {
            val diff = comparePreIdentifier(pre[i], other.pre[i])
            if (diff != 0) return diff
        }
        return pre.size.compareTo(other.pre.size)
    }

    private fun comparePreIdentifier(a: String, b: String): Int {
        val an = a.toIntOrNull()
        val bn = b.toIntOrNull()
        return when {
            an != null && bn != null -> an.compareTo(bn)
            an != null -> -1 // numeric identifiers rank below alphanumeric ones
            bn != null -> 1
            else -> a.compareTo(b)
        }
    }

    companion object {
        /** Parses [raw] (an optional leading `v` is tolerated), or null if it is not version-shaped. */
        fun parse(raw: String): SemVer? {
            val trimmed = raw.trim().removePrefix("v").substringBefore('+')
            if (trimmed.isEmpty()) return null
            val dash = trimmed.indexOf('-')
            val coreText = if (dash >= 0) trimmed.substring(0, dash) else trimmed
            val preText = if (dash >= 0) trimmed.substring(dash + 1) else ""
            val core = coreText.split('.').map { it.toIntOrNull() ?: return null }
            if (core.isEmpty()) return null
            val pre = if (preText.isEmpty()) emptyList() else preText.split('.')
            return SemVer(core, pre)
        }

        /** True when [candidate] parses to a strictly higher version than [current]. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(current) ?: return false
            return a > b
        }
    }
}
