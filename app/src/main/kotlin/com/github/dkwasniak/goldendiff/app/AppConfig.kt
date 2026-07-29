package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.match.MatchingDefaults
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import com.github.dkwasniak.goldendiff.settings.GoldenDiffDefaults
import java.io.File
import java.util.Properties

/**
 * Per-project settings, stored beside the user's other application data.
 *
 * Deliberately NOT written into the project directory: a dotfile there would show up in `git status`
 * and, for a tool whose whole job is reporting what changed, adding noise to that list is the last
 * thing it should do.
 *
 * Java Properties rather than JSON so there is no serialization dependency for eight fields.
 */
object AppConfig {

    private val configDir: File
        get() = File(System.getProperty("user.home"), ".config/golden-diff")

    private fun fileFor(projectRoot: File): File {
        // Keyed by a hash of the absolute path so two projects with the same directory name do not
        // share settings, while the name is kept in the filename to stay recognisable.
        val key = Integer.toHexString(projectRoot.absolutePath.hashCode())
        return File(configDir, "${projectRoot.name}-$key.properties")
    }

    private fun sessionFileFor(projectRoot: File): File {
        val key = Integer.toHexString(projectRoot.absolutePath.hashCode())
        return File(configDir, "${projectRoot.name}-$key-session.properties")
    }

    fun load(projectRoot: File): GoldenDiffConfig {
        val file = fileFor(projectRoot)
        if (!file.isFile) return EMPTY
        val props = Properties()
        runCatching { file.inputStream().use(props::load) }.getOrElse { return EMPTY }

        fun list(key: String): List<String> {
            val count = props.getProperty("$key.count")?.toIntOrNull()
            if (count != null) return (0 until count).mapNotNull { props.getProperty("$key.$it") }
            return props.getProperty(key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

        val excludedSuffixes = list("excludedSuffixes").let { values ->
            // New configs store a count, including zero when the user deliberately clears the list.
            // Legacy blank values came from untouched placeholder-only settings, so migrate those to
            // the real default instead of silently behaving as "exclude nothing".
            if (values.isNotEmpty() || props.containsKey("excludedSuffixes.count")) {
                values
            } else {
                GoldenDiffDefaults.EXCLUDED_SUFFIXES
            }
        }

        // Structural matching defaults are real, visible settings rather than implicit runtime
        // fallbacks. Older configs stored them blank, so migrate those reads to the same values shown
        // by the UI and returned by the CLI.
        return GoldenDiffConfig(
            goldenPaths = list("goldenPaths"),
            generatedPaths = list("generatedPaths"),
            generatedFileRegex = props.getProperty("generatedFileRegex")
                ?.takeIf { it.isNotBlank() } ?: GoldenDiffDefaults.GENERATED_FILE_REGEX,
            matchMode = MatchMode.fromName(props.getProperty("matchMode").orEmpty()),
            annotatedFunctionRegex = props.getProperty("annotatedFunctionRegex")
                ?.takeIf { it.isNotBlank() } ?: MatchingDefaults.ANNOTATION_NAME_REGEX,
            goldenFilePatterns = list("goldenFilePatterns")
                .ifEmpty { MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS },
            excludedSuffixes = excludedSuffixes,
            trimTransparentPadding = props.getProperty("trimTransparentPadding").toBoolean(),
            generateAllCommand = props.getProperty("generateAllCommand").orEmpty(),
            generateFileCommand = props.getProperty("generateFileCommand").orEmpty(),
            verifyAllCommand = props.getProperty("verifyAllCommand").orEmpty(),
            verifyFileCommand = props.getProperty("verifyFileCommand").orEmpty(),
            verifyCaseCommand = props.getProperty("verifyCaseCommand").orEmpty(),
            commandJavaHome = props.getProperty("commandJavaHome").orEmpty(),
            notifyWhenTasksFinish = props.getProperty("notifyWhenTasksFinish")?.toBooleanStrictOrNull() ?: true,
        )
    }

    private val EMPTY = GoldenDiffConfig()

    /**
     * Persists [config], returning the write failure to the caller instead of hiding it. The CLI
     * relies on this result so it never reports an in-memory configuration as successfully saved.
     */
    fun save(projectRoot: File, config: GoldenDiffConfig): Result<Unit> {
        val effective = config.normalizedForMatching()
        val props = Properties().apply {
            setProperty("goldenPaths", effective.goldenPaths.joinToString(","))
            setProperty("goldenPaths.count", effective.goldenPaths.size.toString())
            effective.goldenPaths.forEachIndexed { index, path -> setProperty("goldenPaths.$index", path) }
            setProperty("generatedPaths", effective.generatedPaths.joinToString(","))
            setProperty("generatedFileRegex", effective.generatedFileRegex)
            setProperty("matchMode", effective.matchMode.name)
            setProperty("annotatedFunctionRegex", effective.annotatedFunctionRegex)
            setProperty("goldenFilePatterns", effective.goldenFilePatterns.joinToString(","))
            setProperty("goldenFilePatterns.count", effective.goldenFilePatterns.size.toString())
            effective.goldenFilePatterns.forEachIndexed { index, pattern ->
                setProperty("goldenFilePatterns.$index", pattern)
            }
            setProperty("excludedSuffixes", effective.excludedSuffixes.joinToString(","))
            setProperty("excludedSuffixes.count", effective.excludedSuffixes.size.toString())
            effective.excludedSuffixes.forEachIndexed { index, suffix ->
                setProperty("excludedSuffixes.$index", suffix)
            }
            setProperty("trimTransparentPadding", effective.trimTransparentPadding.toString())
            setProperty("generateAllCommand", effective.generateAllCommand)
            setProperty("generateFileCommand", effective.generateFileCommand)
            setProperty("verifyAllCommand", effective.verifyAllCommand)
            setProperty("verifyFileCommand", effective.verifyFileCommand)
            setProperty("verifyCaseCommand", effective.verifyCaseCommand)
            setProperty("commandJavaHome", effective.commandJavaHome)
            setProperty("notifyWhenTasksFinish", effective.notifyWhenTasksFinish.toString())
        }
        return runCatching {
            configDir.mkdirs()
            fileFor(projectRoot).outputStream().use { props.store(it, "Golden Diff — ${projectRoot.absolutePath}") }
        }
    }

    /**
     * Editor-style file tabs remembered independently for each project.
     *
     * This intentionally lives beside, rather than inside, [GoldenDiffConfig]: saving settings from
     * the GUI or CLI must not overwrite the current UI session. Missing paths are kept so the app can
     * render their tabs as stale and let the user close them explicitly.
     */
    data class OpenFilesSession(
        val paths: List<String> = emptyList(),
        val selectedPath: String? = null,
    )

    fun loadOpenFiles(projectRoot: File): OpenFilesSession {
        val file = sessionFileFor(projectRoot)
        if (!file.isFile) return OpenFilesSession()
        val props = Properties()
        runCatching { file.inputStream().use(props::load) }.getOrElse { return OpenFilesSession() }
        val count = props.getProperty("paths.count")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val paths = (0 until count)
            .mapNotNull { props.getProperty("paths.$it") }
            .filter { it.isNotBlank() }
            .distinct()
        val selected = props.getProperty("selectedPath")?.takeIf { it in paths }
        return OpenFilesSession(paths, selected)
    }

    fun saveOpenFiles(projectRoot: File, session: OpenFilesSession): Result<Unit> {
        val paths = session.paths.filter { it.isNotBlank() }.distinct()
        val props = Properties().apply {
            setProperty("paths.count", paths.size.toString())
            paths.forEachIndexed { index, path -> setProperty("paths.$index", path) }
            session.selectedPath?.takeIf { it in paths }?.let { setProperty("selectedPath", it) }
        }
        return runCatching {
            configDir.mkdirs()
            sessionFileFor(projectRoot).outputStream().use {
                props.store(it, "Golden Diff open files — ${projectRoot.absolutePath}")
            }
        }
    }

    /** Most recently opened project, so the app reopens where the user left off. */
    fun lastProject(): File? =
        File(configDir, "last-project").takeIf { it.isFile }
            ?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(::File)?.takeIf { it.isDirectory }

    fun rememberProject(projectRoot: File) {
        runCatching {
            configDir.mkdirs()
            File(configDir, "last-project").writeText(projectRoot.absolutePath)
        }
        rememberRecentProject(projectRoot)
    }

    private const val MAX_RECENT_PROJECTS = 8

    /**
     * Recently opened projects, newest first, so the toolbar can offer a quick switch. Non-existent
     * directories are dropped on read rather than persisted around — a stale entry is just noise.
     */
    fun recentProjects(): List<File> =
        File(configDir, "recent-projects").takeIf { it.isFile }
            ?.readLines().orEmpty()
            .map(String::trim).filter { it.isNotEmpty() }
            .map(::File).filter { it.isDirectory }
            .distinctBy { it.absolutePath }
            .take(MAX_RECENT_PROJECTS)

    private fun rememberRecentProject(projectRoot: File) {
        runCatching {
            configDir.mkdirs()
            val updated = (listOf(projectRoot) + recentProjects())
                .distinctBy { it.absolutePath }
                .take(MAX_RECENT_PROJECTS)
            File(configDir, "recent-projects").writeText(updated.joinToString("\n") { it.absolutePath })
        }
    }

    /** Whether the first-run setup walkthrough has already been completed on this machine. */
    fun onboardingCompleted(): Boolean = File(configDir, "onboarding-done").isFile

    fun markOnboardingCompleted() {
        runCatching {
            configDir.mkdirs()
            File(configDir, "onboarding-done").writeText("1")
        }
    }

    /**
     * Cross-process hand-off from the CLI to the running app: the CLI writes it after it has finished
     * configuring a project. The main window reloads that project's settings, while the onboarding
     * walkthrough additionally advances past the steps the agent completed. A file is enough — both
     * sides are the same user on the same machine — and the timestamp lets readers ignore a stale
     * signal from an earlier run.
     */
    data class CliSignal(val project: File, val timestamp: Long)

    private val cliSignalFile: File get() = File(configDir, "cli-signal.properties")

    fun writeCliSignal(projectRoot: File): Result<Unit> {
        val props = Properties().apply {
            setProperty("project", projectRoot.absolutePath)
            setProperty("timestamp", System.currentTimeMillis().toString())
        }
        return runCatching {
            configDir.mkdirs()
            cliSignalFile.outputStream().use { props.store(it, "Golden Diff — CLI setup signal") }
        }
    }

    fun readCliSignal(): CliSignal? {
        val file = cliSignalFile.takeIf { it.isFile } ?: return null
        val props = Properties()
        runCatching { file.inputStream().use(props::load) }.getOrElse { return null }
        val project = props.getProperty("project")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File) ?: return null
        val timestamp = props.getProperty("timestamp")?.trim()?.toLongOrNull() ?: return null
        return CliSignal(project, timestamp)
    }
}
