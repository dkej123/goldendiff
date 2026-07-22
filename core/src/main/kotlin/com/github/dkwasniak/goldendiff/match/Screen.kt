package com.github.dkwasniak.goldendiff.match

/**
 * The names a golden file can be matched against for one source file: preview/test function names,
 * class names, and the file base name. [GoldenFinder] picks which of these to use depending on the
 * active [MatchMode].
 *
 * How a [Screen] is produced depends on the host. The IDE plugin reads the file open in the editor
 * (Kotlin PSI, falling back to [GenericScreenExtractor] for other languages); the standalone app has
 * no editor and always goes through [GenericScreenExtractor]. Both then feed the exact same matching
 * code, which is why this type lives here rather than next to either producer.
 *
 * [names] does NOT depend on the caret position, so it stays stable while the user clicks around a
 * file. [caretName] is separate and only used to preselect the best-matching golden when the list is
 * first built for a file; producers without a caret leave it null.
 */
data class Screen(
    /** Function names selected from configured screenshot annotations and test naming. */
    val functionNames: List<String>,
    /** Class names declared in the current file. */
    val classNames: List<String>,
    /** File name without extension. */
    val fileName: String,
    /** Name of the function under the caret, used only for the initial selection. */
    val caretName: String?,
) {
    /** Stable candidate set used to decide whether the list needs to be rebuilt. */
    val names: List<String> = (functionNames + classNames + fileName)
        .filter { it.isNotBlank() }
        .distinct()
}
