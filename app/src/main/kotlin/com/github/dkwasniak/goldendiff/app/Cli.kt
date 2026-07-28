package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.git.GitCli
import com.github.dkwasniak.goldendiff.match.GenericScreenExtractor
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.match.MatchingDefaults
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import com.github.dkwasniak.goldendiff.settings.GoldenDiffDefaults
import java.io.File
import java.util.Properties

/**
 * The terminal face of the standalone app, so an AI CLI agent (or a person) can configure a project
 * without clicking through the setup window.
 *
 * It runs headless: [Main]'s entry point hands off here before any Compose, AWT or telemetry code
 * starts, so invoking the packaged launcher with arguments behaves like an ordinary command-line tool
 * and never flashes a window.
 *
 * Everything it writes goes through [AppConfig], the very same store the GUI uses, so the config file
 * location and encoding can never drift between the two front-ends. No third-party arg parser: the
 * surface is a handful of flags, matching the "no extra dependency for a few fields" ethos of the
 * config store itself.
 */
object GoldenDiffCli {

    private const val EXIT_OK = 0
    private const val EXIT_ERROR = 1
    private const val EXIT_USAGE = 2

    fun run(args: Array<String>): Int {
        val first = args.firstOrNull()
        if (first in COMMANDS && args.drop(1).any { it == "-h" || it == "--help" }) {
            printUsage()
            return EXIT_OK
        }
        return when (first) {
            "-h", "--help", "help", null -> { printUsage(); EXIT_OK }
            "-v", "--version", "version" -> { println(appVersion()); EXIT_OK }
            "configure" -> withParsed(args.drop(1), ::configure)
            "show" -> withParsed(args.drop(1), ::show)
            "validate" -> withParsed(args.drop(1), ::validate)
            "guides" -> { printGuide(null) }
            "guide" -> { printGuide(args.getOrNull(1)) }
            else -> { fail("Unknown command '$first'. Run with --help."); EXIT_USAGE }
        }
    }

    private fun withParsed(tokens: List<String>, action: (Parsed) -> Int): Int {
        val parsed = Parsed.of(tokens)
        if (parsed.errors.isNotEmpty()) {
            parsed.errors.forEach { System.err.println("Error: $it") }
            return EXIT_USAGE
        }
        return action(parsed)
    }

    // region configure

    private fun configure(parsed: Parsed): Int {
        val root = parsed.requireProject() ?: return EXIT_USAGE

        if (!parsed.has("allow-non-git")) {
            val git = GitCli(root)
            if (!git.isAvailable()) return fail("git was not found on the PATH. Install Git, or pass --allow-non-git.")
            if (!git.isInsideWorkTree()) {
                return fail("'${root.absolutePath}' is not inside a Git repository. Pass --allow-non-git to configure anyway.")
            }
        }

        var config = AppConfig.load(root)

        parsed.list("golden-dir").takeIf { it.isNotEmpty() }
            ?.let { dirs -> config = config.copy(goldenPaths = dirs.map { relativize(root, it) }) }
        parsed.list("golden-dir-regex").takeIf { it.isNotEmpty() }
            ?.let { patterns ->
                patterns.firstNotNullOfOrNull { invalidRegex("Golden directory", it) }?.let { return fail(it) }
                val literalDirs = parsed.list("golden-dir").map { relativize(root, it) }
                config = config.copy(
                    goldenPaths = literalDirs + patterns.map { GoldenDiffConfig.goldenDirectoryRegexEntry(it) },
                )
            }
        parsed.list("generated-dir").takeIf { it.isNotEmpty() }
            ?.let { dirs -> config = config.copy(generatedPaths = dirs.map { relativize(root, it) }) }

        parsed.value("match-mode")?.let { raw ->
            val mode = parseMatchMode(raw) ?: return fail("--match-mode must be 'annotated' or 'file-class'.")
            config = config.copy(matchMode = mode)
        }
        parsed.value("annotation-regex")?.let { re ->
            invalidRegex("Annotation", re)?.let { return fail(it) }
            config = config.copy(
                annotatedFunctionRegex = re.ifBlank { MatchingDefaults.ANNOTATION_NAME_REGEX },
            )
        }
        parsed.list("golden-pattern").takeIf { it.isNotEmpty() }
            ?.let { patterns ->
                config = config.copy(
                    goldenFilePatterns = patterns.map(String::trim).filter(String::isNotEmpty)
                        .ifEmpty { MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS },
                )
            }
        parsed.value("generated-regex")?.let { re ->
            invalidRegex("Generated file", re)?.let { return fail(it) }
            config = config.copy(
                generatedFileRegex = re.ifBlank { GoldenDiffDefaults.GENERATED_FILE_REGEX },
            )
        }
        parsed.list("exclude-suffix").takeIf { it.isNotEmpty() }
            ?.let { config = config.copy(excludedSuffixes = it) }
        if (parsed.has("trim-transparent-padding")) config = config.copy(trimTransparentPadding = true)
        if (parsed.has("no-trim-transparent-padding")) config = config.copy(trimTransparentPadding = false)

        parsed.value("generate-all-cmd")?.let { config = config.copy(generateAllCommand = it) }
        parsed.value("generate-file-cmd")?.let { config = config.copy(generateFileCommand = it) }
        parsed.value("verify-all-cmd")?.let { config = config.copy(verifyAllCommand = it) }
        parsed.value("verify-file-cmd")?.let { config = config.copy(verifyFileCommand = it) }
        parsed.value("command-java-home")?.let { config = config.copy(commandJavaHome = it) }

        if (config.goldenPaths.isEmpty()) {
            System.err.println(
                "Warning: no golden directories are configured — Golden Diff has nothing to compare " +
                    "until at least one --golden-dir or --golden-dir-regex is set.",
            )
        }

        AppConfig.save(root, config).exceptionOrNull()?.let { error ->
            return fail("Could not save Golden Diff settings: ${writeErrorMessage(error)}")
        }
        if (parsed.has("open")) AppConfig.rememberProject(root)
        if (parsed.has("signal")) {
            AppConfig.writeCliSignal(root).exceptionOrNull()?.let { error ->
                return fail("Settings were saved, but the completion signal could not be written: ${writeErrorMessage(error)}")
            }
        }

        if (parsed.has("json")) println(configJson(root, config)) else printConfig(root, config)
        return EXIT_OK
    }

    // endregion

    // region show / validate

    private fun show(parsed: Parsed): Int {
        val root = parsed.requireProject() ?: return EXIT_USAGE
        val config = AppConfig.load(root)
        if (parsed.has("json")) println(configJson(root, config)) else printConfig(root, config)
        return EXIT_OK
    }

    private fun validate(parsed: Parsed): Int {
        val root = parsed.requireProject() ?: return EXIT_USAGE
        val git = GitCli(root)
        val gitAvailable = git.isAvailable()
        val insideRepo = gitAvailable && git.isInsideWorkTree()
        val config = AppConfig.load(root)
        val goldenDirs = config.goldenPaths.flatMap { entry ->
            val resolved = config.resolveGoldenPath(root, entry)
            if (resolved.isEmpty()) {
                listOf(DirStatus(entry, null, exists = false, hasPng = false))
            } else {
                resolved.map { dir ->
                    DirStatus(
                        entry,
                        dir.relativeToOrNull(root)?.path ?: dir.absolutePath,
                        dir.isDirectory,
                        dir.isDirectory && dir.walkTopDown().any { it.isFile && it.extension.equals("png", true) },
                    )
                }
            }
        }
        val sourceMatches = parsed.list("source-file").map { sourcePath ->
            validateSourceFile(root, config, sourcePath)
        }

        if (parsed.has("json")) {
            println(validateJson(root, gitAvailable, insideRepo, goldenDirs, sourceMatches))
        } else {
            println("Project:        ${root.absolutePath}")
            println("git installed:  ${check(gitAvailable)}")
            println("Git repository: ${check(insideRepo)}")
            if (goldenDirs.isEmpty()) {
                println("Golden dirs:    none configured")
            } else {
                println("Golden dirs:")
                goldenDirs.forEach { d ->
                    val note = when {
                        !d.exists -> "missing"
                        !d.hasPng -> "no PNGs found"
                        else -> "ok"
                    }
                    val resolved = d.resolvedPath?.takeIf { it != d.entry }?.let { " -> $it" }.orEmpty()
                    println("  ${check(d.exists && d.hasPng)} ${d.entry}$resolved  ($note)")
                }
            }
            if (sourceMatches.isEmpty()) {
                val note = if (parsed.has("directories-only")) {
                    "explicit directory-only check; semantic matching was not validated"
                } else {
                    "none supplied; validation is incomplete (use --source-file)"
                }
                println("Source files:   $note")
            } else {
                println("Source-file matches:")
                sourceMatches.forEach { match ->
                    val note = when {
                        !match.exists -> "missing or outside project"
                        match.matchCount == 0 -> "no matching goldens"
                        else -> "${match.matchCount} golden(s)"
                    }
                    println("  ${check(match.exists && match.matchCount > 0)} ${match.path}  ($note)")
                    match.samples.forEach { println("      $it") }
                }
            }
        }
        val directoriesValid = goldenDirs.isNotEmpty() && goldenDirs.all { it.exists && it.hasPng }
        val sourcesValid = if (parsed.has("directories-only")) {
            true
        } else {
            sourceMatches.isNotEmpty() && sourceMatches.all { it.exists && it.matchCount > 0 }
        }
        return if (gitAvailable && insideRepo && directoriesValid && sourcesValid) {
            EXIT_OK
        } else {
            EXIT_ERROR
        }
    }

    private data class DirStatus(
        val entry: String,
        val resolvedPath: String?,
        val exists: Boolean,
        val hasPng: Boolean,
    )

    private data class SourceMatchStatus(
        val path: String,
        val exists: Boolean,
        val matchCount: Int,
        val samples: List<String>,
    )

    private fun validateSourceFile(
        root: File,
        config: GoldenDiffConfig,
        sourcePath: String,
    ): SourceMatchStatus {
        val source = (if (File(sourcePath).isAbsolute) File(sourcePath) else File(root, sourcePath)).normalize()
        val relative = source.relativeToOrNull(root)
            ?.takeIf { !it.path.startsWith("..") }
            ?.path?.replace(File.separatorChar, '/')
            ?: return SourceMatchStatus(sourcePath, exists = false, matchCount = 0, samples = emptyList())
        if (!source.isFile) {
            return SourceMatchStatus(relative, exists = false, matchCount = 0, samples = emptyList())
        }

        val effective = config.normalizedForMatching()
        val annotationRegex = effective.annotatedFunctionRegex
            .takeIf { effective.matchMode == MatchMode.ANNOTATED_METHOD }
        val screen = GenericScreenExtractor.extract(
            source.nameWithoutExtension,
            runCatching { source.readText() }.getOrDefault(""),
            annotationRegex,
        )
        val matches = GoldenFinder.find(
            effective.resolvedGoldenPaths(root),
            screen,
            effective.matchMode,
            effective.excludedSuffixes,
            effective.goldenFilePatterns,
        )
        return SourceMatchStatus(
            path = relative,
            exists = true,
            matchCount = matches.size,
            samples = matches.take(3).map { file ->
                file.relativeToOrNull(root)?.path?.replace(File.separatorChar, '/') ?: file.absolutePath
            },
        )
    }

    private fun check(ok: Boolean): String = if (ok) "yes" else "no"

    // endregion

    // region output

    private fun writeErrorMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty().ifBlank { "unknown I/O error" }

    private fun printConfig(root: File, config: GoldenDiffConfig) {
        println("Golden Diff configured for ${root.absolutePath}")
        println("  Golden directories:    ${config.goldenPaths.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        println("  Generated directories: ${config.generatedPaths.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        println("  Matching mode:         ${matchModeLabel(config.matchMode)}")
        // Blank fields are genuinely unset — nothing was written for them; the tool falls back to the
        // built-in default at match time, shown here so it is clear what will actually be used.
        if (config.matchMode == MatchMode.ANNOTATED_METHOD) {
            println("  Annotation regex:      ${orDefault(config.annotatedFunctionRegex, MatchingDefaults.ANNOTATION_NAME_REGEX)}")
        } else {
            println("  Golden patterns:       ${orDefaultList(config.goldenFilePatterns, MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS)}")
        }
        println("  Generated file regex:  ${orDefault(config.generatedFileRegex, GoldenDiffDefaults.GENERATED_FILE_REGEX)}")
        println("  Excluded suffixes:     ${config.excludedSuffixes.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        println("  Trim transparent pad:  ${config.trimTransparentPadding}")
        println("  Generate all cmd:      ${orNone(config.generateAllCommand)}")
        println("  Generate file cmd:     ${orNone(config.generateFileCommand)}")
        println("  Verify all cmd:        ${orNone(config.verifyAllCommand)}")
        println("  Verify file cmd:       ${orNone(config.verifyFileCommand)}")
        println("  Command JAVA_HOME:     ${orNone(config.commandJavaHome)}")
    }

    private fun orNone(value: String): String = value.ifBlank { "(none)" }

    private fun orDefault(value: String, default: String): String =
        if (value.isBlank()) "(not set — default: $default)" else value

    private fun orDefaultList(values: List<String>, default: List<String>): String =
        if (values.isEmpty()) "(not set — default: ${default.joinToString(", ")})" else values.joinToString(", ")

    private fun configJson(root: File, config: GoldenDiffConfig): String = buildString {
        append("{")
        append(jsonField("project", root.absolutePath)).append(",")
        append(jsonArray("goldenPaths", config.goldenPaths)).append(",")
        append(jsonArray("generatedPaths", config.generatedPaths)).append(",")
        append(jsonField("matchMode", config.matchMode.name)).append(",")
        append(jsonField("annotatedFunctionRegex", config.annotatedFunctionRegex)).append(",")
        append(jsonArray("goldenFilePatterns", config.goldenFilePatterns)).append(",")
        append(jsonField("generatedFileRegex", config.generatedFileRegex)).append(",")
        append(jsonArray("excludedSuffixes", config.excludedSuffixes)).append(",")
        append("\"trimTransparentPadding\":${config.trimTransparentPadding},")
        append(jsonField("generateAllCommand", config.generateAllCommand)).append(",")
        append(jsonField("generateFileCommand", config.generateFileCommand)).append(",")
        append(jsonField("verifyAllCommand", config.verifyAllCommand)).append(",")
        append(jsonField("verifyFileCommand", config.verifyFileCommand)).append(",")
        append(jsonField("commandJavaHome", config.commandJavaHome)).append(",")
        append("\"isConfigured\":${config.isConfigured}")
        append("}")
    }

    private fun validateJson(
        root: File,
        gitAvailable: Boolean,
        insideRepo: Boolean,
        goldenDirs: List<DirStatus>,
        sourceMatches: List<SourceMatchStatus>,
    ): String = buildString {
        append("{")
        append(jsonField("project", root.absolutePath)).append(",")
        append("\"gitInstalled\":$gitAvailable,")
        append("\"gitRepository\":$insideRepo,")
        append("\"goldenDirectories\":[")
        goldenDirs.forEachIndexed { i, d ->
            if (i > 0) append(",")
            append("{")
            append(jsonField("path", d.entry)).append(",")
            d.resolvedPath?.let { append(jsonField("resolvedPath", it)).append(",") }
            append("\"exists\":${d.exists},")
            append("\"hasPng\":${d.hasPng}")
            append("}")
        }
        append("],")
        append("\"sourceFiles\":[")
        sourceMatches.forEachIndexed { i, source ->
            if (i > 0) append(",")
            append("{")
            append(jsonField("path", source.path)).append(",")
            append("\"exists\":${source.exists},")
            append("\"matchCount\":${source.matchCount},")
            append(jsonArray("samples", source.samples))
            append("}")
        }
        append("]}")
    }

    private fun jsonField(name: String, value: String): String = "\"$name\":\"${jsonEscape(value)}\""

    private fun jsonArray(name: String, values: List<String>): String =
        "\"$name\":[${values.joinToString(",") { "\"${jsonEscape(it)}\"" }}]"

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private fun matchModeLabel(mode: MatchMode): String =
        if (mode == MatchMode.ANNOTATED_METHOD) "annotated (Match by annotated method)" else "file-class (Match by file/class regex)"

    // endregion

    // region helpers

    private fun parseMatchMode(raw: String): MatchMode? = when (raw.lowercase()) {
        "annotated", "annotated_method", "annotated-method" -> MatchMode.ANNOTATED_METHOD
        "file-class", "file_class", "file_class_regex", "file-class-regex", "regex" -> MatchMode.FILE_CLASS_REGEX
        else -> null
    }

    private fun invalidRegex(label: String, pattern: String): String? =
        runCatching { Regex(pattern) }.exceptionOrNull()?.let { "$label regex is invalid: ${it.message}" }

    /**
     * Stores a directory the way the GUI does: relative to the project root when it sits inside it (so
     * a moved checkout keeps working), absolute otherwise. Accepts either an absolute path or one
     * relative to the project root.
     */
    private fun relativize(root: File, path: String): String {
        val file = File(path)
        val absolute = (if (file.isAbsolute) file else File(root, path)).normalize()
        val relative = absolute.relativeToOrNull(root)?.path
        return if (relative != null && !relative.startsWith("..")) relative else absolute.absolutePath
    }

    private fun appVersion(): String = runCatching {
        Properties().apply {
            GoldenDiffCli::class.java.classLoader.getResourceAsStream("golden-diff-telemetry.properties")?.use(::load)
        }.getProperty("version").orEmpty().ifBlank { "dev" }
    }.getOrDefault("dev")

    private fun fail(message: String): Int {
        System.err.println("Error: $message")
        return EXIT_ERROR
    }

    private fun printUsage() {
        println(
            """
            Golden Diff — configure a project from the terminal.

            Usage:
              golden-diff configure --project <path> [options]
              golden-diff show      --project <path> [--json]
              golden-diff validate  --project <path> [--json]
              golden-diff guides                     List bundled framework setup guides.
              golden-diff guide <name>               Print a framework setup guide (project + GoldenDiff config).
              golden-diff --version

            configure options:
              --project <path>              Project root (required).
              --golden-dir <path>           Golden screenshot directory. Repeat for several; replaces the list.
              --golden-dir-regex <regex>    Full regex over project-relative directory paths. Repeatable.
              --generated-dir <path>        Test-output directory. Repeatable.
              --match-mode annotated|file-class
              --annotation-regex <regex>    Screenshot annotation name regex (annotated mode).
              --golden-pattern <pattern>    Golden filename pattern (file-class mode). Repeatable.
              --generated-regex <regex>     Generated output filename regex.
              --exclude-suffix <suffix>     Golden suffix to hide. Repeatable.
              --generate-all-cmd <cmd>      Shell command that records/generates all screenshots. Blank clears it.
              --generate-file-cmd <cmd>     Command that generates one file's screenshots. Blank clears it.
                                            May use {file_name}, {class_name}, {file_path} placeholders.
              --verify-all-cmd <cmd>        Shell command that verifies all screenshots. Blank clears it.
              --verify-file-cmd <cmd>       Command that verifies one file's screenshots. Blank clears it.
                                            May use {file_name}, {class_name}, {file_path} placeholders.
              --command-java-home <path>    JDK/JBR exported as JAVA_HOME when running the commands
                                            above (e.g. Android Studio's bundled JBR). Blank clears it.
              --trim-transparent-padding | --no-trim-transparent-padding
              --open                        Remember the project so the app reopens it.
              --signal                      Reload settings in the open app and finish setup.
              --allow-non-git               Skip the Git repository check.
              --json                        Print the result as JSON.

            validate options:
              --project <path>              Project root (required).
              --source-file <path>          Representative source/test file. Repeatable; each must match goldens.
              --directories-only            Explicitly skip semantic source-to-golden matching.
              --json                        Print resolved directories and per-source match results as JSON.

            Paths may be absolute or relative to the project root; they are stored relative when inside it.
            Literal and regex golden-directory options may be combined. A regex must match the complete
            directory path using '/' separators, for example: .*/src/test/snapshots
            """.trimIndent(),
        )
    }

    // endregion

    // region guides

    /**
     * Prints a bundled framework setup guide (or the index when [name] is null/"index"). The guides
     * are packaged under `framework-guides/` from docs/framework-guides so a configuration agent can
     * read the exact project-side + GoldenDiff-side setup for the detected screenshot framework.
     */
    private fun printGuide(name: String?): Int {
        val requested = name?.takeIf { it.isNotBlank() && it != "index" }
        val guide = readGuideResource(requested ?: "index")
        if (guide != null) {
            println(guide.trimEnd())
            return EXIT_OK
        }
        System.err.println("No guide named '${requested}'. Available guides:")
        readGuideResource("index")?.let(System.err::println)
        return EXIT_USAGE
    }

    private fun readGuideResource(name: String): String? =
        GoldenDiffCli::class.java.classLoader.getResourceAsStream("framework-guides/$name.md")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    // endregion

    /** Minimal flag parser: repeatable value options collect into lists, everything else is a boolean flag. */
    private class Parsed private constructor(
        private val values: Map<String, List<String>>,
        private val flags: Set<String>,
        val errors: List<String>,
    ) {
        fun value(name: String): String? = values[name]?.lastOrNull()
        fun list(name: String): List<String> = values[name].orEmpty()
        fun has(flag: String): Boolean = flag in flags

        fun requireProject(): File? {
            val raw = value("project")
            if (raw == null) {
                System.err.println("Error: --project <path> is required.")
                return null
            }
            val root = File(raw).absoluteFile.normalize()
            if (!root.isDirectory) {
                System.err.println("Error: project directory does not exist: ${root.absolutePath}")
                return null
            }
            return root
        }

        companion object {
            private val VALUE_OPTIONS = setOf(
                "project", "golden-dir", "golden-dir-regex", "generated-dir", "match-mode", "annotation-regex",
                "golden-pattern", "generated-regex", "exclude-suffix", "source-file",
                "generate-all-cmd", "generate-file-cmd", "verify-all-cmd", "verify-file-cmd",
                "command-java-home",
            )
            private val FLAG_OPTIONS = setOf(
                "trim-transparent-padding", "no-trim-transparent-padding", "open", "signal",
                "allow-non-git", "directories-only", "json",
            )

            fun of(tokens: List<String>): Parsed {
                val values = LinkedHashMap<String, MutableList<String>>()
                val flags = LinkedHashSet<String>()
                val errors = mutableListOf<String>()
                var i = 0
                while (i < tokens.size) {
                    val token = tokens[i]
                    val name = token.removePrefix("--")
                    when {
                        !token.startsWith("--") -> errors += "Unexpected argument '$token'."
                        name in VALUE_OPTIONS -> {
                            val next = tokens.getOrNull(i + 1)
                            if (next == null || next.startsWith("--")) {
                                errors += "Missing value for --$name."
                            } else {
                                values.getOrPut(name) { mutableListOf() }.add(next)
                                i++
                            }
                        }
                        name in FLAG_OPTIONS -> flags.add(name)
                        else -> errors += "Unknown option '--$name'. Run with --help."
                    }
                    i++
                }
                return Parsed(values, flags, errors)
            }
        }
    }

    private val COMMANDS = setOf("configure", "show", "validate")
}
