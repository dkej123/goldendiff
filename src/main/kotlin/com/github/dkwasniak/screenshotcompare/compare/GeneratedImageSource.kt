package com.github.dkwasniak.screenshotcompare.compare

import java.io.File

/** Finds the test-generated counterpart for a selected golden screenshot. */
object GeneratedImageSource {

    fun findForGolden(
        golden: File,
        goldenRoots: List<File>,
        generatedRoots: List<File>,
        generatedFileRegex: String,
    ): File? {
        val pattern = runCatching { Regex(generatedFileRegex) }.getOrNull() ?: return null
        val goldenBaseName = golden.nameWithoutExtension
        val relativePath = goldenRoots
            .firstNotNullOfOrNull { root -> golden.relativeToOrNull(root)?.path }

        if (relativePath != null) {
            val relativeParent = File(relativePath).parentFile?.path
            generatedRoots.asSequence()
                .mapNotNull { root -> relativeParent?.let { File(root, it) } ?: root }
                .filter { it.isDirectory }
                .flatMap { root -> root.listFiles()?.asSequence() ?: emptySequence() }
                .firstOrNull { it.matchesGolden(goldenBaseName, pattern) }
                ?.let { return it }
        }

        return generatedRoots
            .asSequence()
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().asSequence() }
            .firstOrNull { it.matchesGolden(goldenBaseName, pattern) }
    }

    private fun File.relativeToOrNull(root: File): File? =
        runCatching { relativeTo(root) }.getOrNull()?.takeIf { !it.path.startsWith("..") }

    private fun File.matchesGolden(goldenBaseName: String, pattern: Regex): Boolean {
        if (!isFile) return false
        val match = pattern.matchEntire(name) ?: return false
        val generatedBaseName = match.groups.getOrNull(1)?.value ?: nameWithoutExtension
            .removeSuffix("_actual")
            .removeSuffix("_compare")
        return generatedBaseName.equals(goldenBaseName, ignoreCase = true)
    }

    private fun MatchGroupCollection.getOrNull(index: Int): MatchGroup? =
        runCatching { get(index) }.getOrNull()
}
