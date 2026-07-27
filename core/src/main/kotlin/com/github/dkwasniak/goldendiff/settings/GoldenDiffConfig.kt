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
    /**
     * Directories holding golden files. Relative paths resolve against the project root; entries
     * prefixed with [GOLDEN_DIRECTORY_REGEX_PREFIX] are regexes over project-relative directories.
     */
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
    /**
     * Shell command run to (re)generate every golden in the project. Free-form and framework-agnostic
     * — the tool never assumes Gradle, xcodebuild or any framework; blank means "not configured".
     */
    val generateAllCommand: String = "",
    /**
     * Shell command run to regenerate the goldens for the current file only. May contain the
     * placeholders [com.github.dkwasniak.goldendiff.command.CommandTemplate] expands.
     */
    val generateFileCommand: String = "",
    /** Shell command run to verify every golden in the project. Blank means "not configured". */
    val verifyAllCommand: String = "",
    /** Shell command run to verify the current file's goldens only. Supports the same placeholders. */
    val verifyFileCommand: String = "",
) {

    val isConfigured: Boolean get() = goldenPaths.isNotEmpty()

    val hasGeneratedPaths: Boolean get() = generatedPaths.isNotEmpty()

    val hasGenerateAllCommand: Boolean get() = generateAllCommand.isNotBlank()

    val hasGenerateFileCommand: Boolean get() = generateFileCommand.isNotBlank()

    val hasVerifyAllCommand: Boolean get() = verifyAllCommand.isNotBlank()

    val hasVerifyFileCommand: Boolean get() = verifyFileCommand.isNotBlank()

    /**
     * Fills the structural matching defaults just for consumption, so a host that stores these fields
     * blank (the standalone app leaves them empty until the user sets them) still matches. Only the
     * fields that would otherwise match nothing are filled: excluded suffixes are left untouched
     * because blank there legitimately means "exclude nothing", and paths/mode are unchanged.
     */
    fun normalizedForMatching(): GoldenDiffConfig = copy(
        generatedFileRegex = generatedFileRegex.ifBlank { GoldenDiffDefaults.GENERATED_FILE_REGEX },
        annotatedFunctionRegex = annotatedFunctionRegex.ifBlank { MatchingDefaults.ANNOTATION_NAME_REGEX },
        goldenFilePatterns = goldenFilePatterns.ifEmpty { MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS },
    )

    fun resolvedGoldenPaths(projectRoot: File?): List<File> =
        goldenPaths.flatMap { resolveGoldenPath(projectRoot, it) }
            .distinctBy { it.normalize().path }

    /**
     * Resolves one literal or regex golden-directory entry. Regexes are matched against the complete
     * project-relative directory path with `/` separators. Invalid regexes and regexes without a
     * project root resolve to no directories; hosts should validate input before saving it.
     */
    fun resolveGoldenPath(projectRoot: File?, path: String): List<File> {
        val pattern = goldenDirectoryRegex(path) ?: return listOf(resolve(projectRoot, path))
        val root = projectRoot?.normalize()?.takeIf { it.isDirectory } ?: return emptyList()
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return emptyList()
        return root.walkTopDown()
            .onEnter { directory -> directory == root || directory.name !in REGEX_SCAN_EXCLUDED_DIRECTORIES }
            .filter { directory ->
                directory.isDirectory &&
                    regex.matches(directory.relativeTo(root).path.replace(File.separatorChar, '/').ifEmpty { "." })
            }
            .toList()
    }

    fun resolvedGeneratedPaths(projectRoot: File?): List<File> = generatedPaths.map { resolve(projectRoot, it) }

    // Absolute paths stay supported for backwards compatibility with configs written before paths
    // became project-relative.
    private fun resolve(projectRoot: File?, path: String): File {
        val file = File(path)
        if (file.isAbsolute || projectRoot == null) return file.normalize()
        return File(projectRoot, path).normalize()
    }

    companion object {
        const val GOLDEN_DIRECTORY_REGEX_PREFIX = "regex:"

        private val REGEX_SCAN_EXCLUDED_DIRECTORIES = setOf(
            ".build",
            ".git",
            ".gradle",
            ".idea",
            ".swiftpm",
            "Carthage",
            "DerivedData",
            "Pods",
            "build",
            "dist",
            "node_modules",
            "out",
            "target",
        )

        fun goldenDirectoryRegex(path: String): String? =
            path.takeIf { it.startsWith(GOLDEN_DIRECTORY_REGEX_PREFIX) }
                ?.removePrefix(GOLDEN_DIRECTORY_REGEX_PREFIX)

        fun goldenDirectoryRegexEntry(pattern: String): String = GOLDEN_DIRECTORY_REGEX_PREFIX + pattern
    }
}
