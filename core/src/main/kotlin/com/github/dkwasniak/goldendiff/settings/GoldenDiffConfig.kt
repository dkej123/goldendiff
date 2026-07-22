package com.github.dkwasniak.goldendiff.settings

import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.match.MatchingDefaults
import java.io.File

/**
 * Everything the comparison logic needs to know about how a project is laid out and how goldens
 * should be matched. A plain value object: no persistence, no host types.
 *
 * Each host owns its own storage and converts to this at the edge — the IDE plugin from its
 * `PersistentStateComponent` (an XML file managed by the platform), the standalone app from JSON.
 * Keeping the shape here is what lets `ChangeScanner`, `GoldenFinder` and `GeneratedImageSource` be
 * written once against a single type instead of once per host.
 *
 * Values are expected to arrive already normalised (no blank regexes, no empty pattern lists);
 * hosts apply their defaults when reading their own storage, where a hand-edited config file is the
 * realistic failure mode.
 */
data class GoldenDiffConfig(
    /** Directories holding golden files. Relative paths resolve against the project root. */
    val goldenPaths: List<String> = emptyList(),
    /** Directories holding screenshots produced by verification tests. */
    val generatedPaths: List<String> = emptyList(),
    /** Selects generated output; the first capture group maps back to the golden's base name. */
    val generatedFileRegex: String = GoldenDiffDefaults.GENERATED_FILE_REGEX,
    val matchMode: MatchMode = MatchingDefaults.DEFAULT_MATCH_MODE,
    /** Applied to annotation names; matching annotated functions become golden candidates. */
    val annotatedFunctionRegex: String = MatchingDefaults.ANNOTATION_NAME_REGEX,
    /** Patterns for [MatchMode.FILE_CLASS_REGEX]; support `{file_name}` and `{class_name}`. */
    val goldenFilePatterns: List<String> = MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS,
    /** Suffixes marking tool artifacts rather than goldens, e.g. `_compare` / `_actual`. */
    val excludedSuffixes: List<String> = GoldenDiffDefaults.EXCLUDED_SUFFIXES,
    /** Trim fully transparent padding around image content before showing it. */
    val trimTransparentPadding: Boolean = false,
) {

    val isConfigured: Boolean get() = goldenPaths.isNotEmpty()

    val hasGeneratedPaths: Boolean get() = generatedPaths.isNotEmpty()

    fun resolvedGoldenPaths(projectRoot: File?): List<File> = goldenPaths.map { resolve(projectRoot, it) }

    fun resolvedGeneratedPaths(projectRoot: File?): List<File> = generatedPaths.map { resolve(projectRoot, it) }

    // Absolute paths stay supported for backwards compatibility with configs written before paths
    // became project-relative.
    private fun resolve(projectRoot: File?, path: String): File {
        val file = File(path)
        if (file.isAbsolute || projectRoot == null) return file.normalize()
        return File(projectRoot, path).normalize()
    }
}
