package com.github.dkwasniak.screenshotcompare.match

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
        if (screen.names.isEmpty()) return emptyList()
        val lowerNames = screen.names.map { it.lowercase() }
        val suffixes = excludedSuffixes.filter { it.isNotBlank() }

        val matches = LinkedHashSet<File>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
                .filter { file -> suffixes.none { file.nameWithoutExtension.endsWith(it) } }
                .filter { file ->
                    val name = file.name.lowercase()
                    lowerNames.any { name.contains(it) }
                }
                .forEach { matches.add(it) }
        }

        // Sort so that the golden matching the caret symbol comes first, then by name.
        val caret = screen.caretName?.lowercase()
        return matches.sortedWith(
            compareByDescending<File> { caret != null && it.name.lowercase().contains(caret) }
                .thenBy { it.name.lowercase() }
        )
    }
}
