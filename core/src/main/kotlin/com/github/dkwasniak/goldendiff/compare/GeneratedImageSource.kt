package com.github.dkwasniak.goldendiff.compare

import java.io.File

/** Finds the test-generated counterpart for a selected golden screenshot. */
object GeneratedImageSource {

    fun findForGolden(
        golden: File,
        goldenRoots: List<File>,
        generatedRoots: List<File>,
        generatedFileRegex: String,
        excludedSuffixes: List<String> = emptyList(),
    ): File? {
        val pattern = runCatching { Regex(generatedFileRegex) }.getOrNull() ?: return null
        val suffixes = excludedSuffixes.filter { it.isNotBlank() }
        val goldenBaseName = golden.nameWithoutExtension
        val relativePath = goldenRoots
            .firstNotNullOfOrNull { root -> golden.relativeToOrNull(root)?.path }

        if (relativePath != null) {
            val relativeParent = File(relativePath).parentFile?.path
            generatedRoots.asSequence()
                .mapNotNull { root -> relativeParent?.let { File(root, it) } ?: root }
                .filter { it.isDirectory }
                .flatMap { root -> root.listFiles()?.asSequence() ?: emptySequence() }
                .firstOrNull { it.matchesGolden(goldenBaseName, pattern, suffixes) }
                ?.let { return it }
        }

        return generatedRoots
            .asSequence()
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().asSequence() }
            .firstOrNull { it.matchesGolden(goldenBaseName, pattern, suffixes) }
    }

    private fun File.relativeToOrNull(root: File): File? =
        runCatching { relativeTo(root) }.getOrNull()?.takeIf { !it.path.startsWith("..") }

    private fun File.matchesGolden(goldenBaseName: String, pattern: Regex, suffixes: List<String>): Boolean {
        if (!isFile) return false
        val match = pattern.matchEntire(name) ?: return false
        val generatedBaseName = match.groups.getOrNull(1)?.value
            ?: suffixes.fold(nameWithoutExtension) { acc, suffix -> acc.removeSuffix(suffix) }
        return generatedBaseName.equals(goldenBaseName, ignoreCase = true)
    }

    private fun MatchGroupCollection.getOrNull(index: Int): MatchGroup? =
        runCatching { get(index) }.getOrNull()
}
