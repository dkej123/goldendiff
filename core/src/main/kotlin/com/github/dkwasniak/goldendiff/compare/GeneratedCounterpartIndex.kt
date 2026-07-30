package com.github.dkwasniak.goldendiff.compare

import java.io.File
import java.util.Locale

sealed interface GeneratedCounterpartResolution {
    data object Missing : GeneratedCounterpartResolution
    data class Exact(val file: File) : GeneratedCounterpartResolution
    data class Ambiguous(val candidates: List<File>) : GeneratedCounterpartResolution
}

/**
 * Immutable generated-output index. Each distinct configured root is walked once at construction;
 * resolving any number of goldens afterwards is allocation-only and performs no directory walks.
 */
class GeneratedCounterpartIndex private constructor(
    val configuration: Configuration,
    val stats: Stats,
    private val candidatesByGoldenBase: Map<String, List<Candidate>>,
) {
    data class Configuration(
        val generatedRoots: List<String>,
        val generatedFileRegex: String,
        val excludedSuffixes: List<String>,
    )

    data class Stats(
        val scannedRoots: Int,
        val visitedFiles: Int,
        val indexedCandidates: Int,
    )

    fun resolve(golden: File, goldenRoots: List<File>): GeneratedCounterpartResolution {
        val matches = candidatesByGoldenBase[golden.nameWithoutExtension.lowercase(Locale.ROOT)].orEmpty()
        if (matches.isEmpty()) return GeneratedCounterpartResolution.Missing

        val goldenRelativeParent = goldenRoots.firstNotNullOfOrNull { root ->
            golden.relativeToOrNull(root)?.parent.orEmpty().invariantPath()
        }.orEmpty()
        val goldenSegments = goldenRelativeParent.pathSegments()
        val ranked = matches.map { candidate ->
            RankedCandidate(
                candidate = candidate,
                exactRelativeDirectory = candidate.relativeParent.equals(goldenRelativeParent, ignoreCase = true),
                commonDirectorySuffix = commonSuffixLength(goldenSegments, candidate.relativeParent.pathSegments()),
                exactBaseCase = candidate.goldenBase == golden.nameWithoutExtension,
            )
        }
        val bestRank = ranked.maxOf { it.rank }
        val best = ranked.filter { it.rank == bestRank }
            .map { it.candidate.file }
            .distinctBy { it.canonicalOrAbsolutePath() }
            .sortedWith(FILE_ORDER)
        return when (best.size) {
            0 -> GeneratedCounterpartResolution.Missing
            1 -> GeneratedCounterpartResolution.Exact(best.single())
            else -> GeneratedCounterpartResolution.Ambiguous(best)
        }
    }

    private data class Candidate(
        val file: File,
        val goldenBase: String,
        val relativeParent: String,
    )

    private data class Rank(
        val exactRelativeDirectory: Boolean,
        val commonDirectorySuffix: Int,
        val exactBaseCase: Boolean,
    ) : Comparable<Rank> {
        override fun compareTo(other: Rank): Int =
            compareValuesBy(
                this,
                other,
                Rank::exactRelativeDirectory,
                Rank::commonDirectorySuffix,
                Rank::exactBaseCase,
            )
    }

    private data class RankedCandidate(
        val candidate: Candidate,
        val exactRelativeDirectory: Boolean,
        val commonDirectorySuffix: Int,
        val exactBaseCase: Boolean,
    ) {
        val rank: Rank get() = Rank(exactRelativeDirectory, commonDirectorySuffix, exactBaseCase)
    }

    companion object {
        fun build(
            generatedRoots: List<File>,
            generatedFileRegex: String,
            excludedSuffixes: List<String> = emptyList(),
        ): GeneratedCounterpartIndex {
            val roots = generatedRoots
                .map { it.normalize() }
                .distinctBy { it.canonicalOrAbsolutePath() }
            val suffixes = excludedSuffixes.filter(String::isNotBlank)
            val configuration = Configuration(
                generatedRoots = roots.map { it.canonicalOrAbsolutePath() },
                generatedFileRegex = generatedFileRegex,
                excludedSuffixes = suffixes,
            )
            val pattern = runCatching { Regex(generatedFileRegex) }.getOrNull()
                ?: return GeneratedCounterpartIndex(configuration, Stats(0, 0, 0), emptyMap())
            var scannedRoots = 0
            var visitedFiles = 0
            val candidates = linkedMapOf<String, Candidate>()
            roots.filter(File::isDirectory).forEach { root ->
                scannedRoots++
                root.walkTopDown().filter(File::isFile).forEach { file ->
                    visitedFiles++
                    val goldenBase = file.generatedGoldenBase(pattern, suffixes) ?: return@forEach
                    val relativeParent = file.relativeToOrNull(root)?.parent.orEmpty().invariantPath()
                    candidates.putIfAbsent(
                        file.canonicalOrAbsolutePath(),
                        Candidate(file, goldenBase, relativeParent),
                    )
                }
            }
            val byBase = candidates.values.groupBy { it.goldenBase.lowercase(Locale.ROOT) }
                .mapValues { (_, values) -> values.sortedWith(compareBy(FILE_ORDER) { it.file }) }
            return GeneratedCounterpartIndex(
                configuration,
                Stats(scannedRoots, visitedFiles, candidates.size),
                byBase,
            )
        }

        private val FILE_ORDER =
            compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.canonicalOrAbsolutePath() }
                .thenBy { it.canonicalOrAbsolutePath() }

        private fun File.generatedGoldenBase(pattern: Regex, suffixes: List<String>): String? {
            val match = pattern.matchEntire(name) ?: return null
            return runCatching { match.groups[1]?.value }.getOrNull()
                ?: suffixes.fold(nameWithoutExtension) { value, suffix -> value.removeSuffix(suffix) }
        }

        private fun File.relativeToOrNull(root: File): File? =
            runCatching { normalize().relativeTo(root.normalize()) }.getOrNull()
                ?.takeIf { !it.path.startsWith("..") }

        private fun File.canonicalOrAbsolutePath(): String =
            runCatching { canonicalFile.normalize().path }.getOrElse { absoluteFile.normalize().path }

        private fun String.invariantPath(): String = replace('\\', '/').trim('/')

        private fun String.pathSegments(): List<String> =
            split('/').filter(String::isNotBlank)

        private fun commonSuffixLength(first: List<String>, second: List<String>): Int {
            var count = 0
            while (
                count < first.size &&
                count < second.size &&
                first[first.lastIndex - count].equals(second[second.lastIndex - count], ignoreCase = true)
            ) {
                count++
            }
            return count
        }
    }
}
