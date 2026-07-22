package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.match.MatchMode
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

    fun load(projectRoot: File): GoldenDiffConfig {
        val file = fileFor(projectRoot)
        if (!file.isFile) return GoldenDiffConfig()
        val props = Properties()
        runCatching { file.inputStream().use(props::load) }.getOrElse { return GoldenDiffConfig() }

        fun list(key: String): List<String> =
            props.getProperty(key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        // Blank or missing values fall back to the defaults rather than being taken literally - this
        // file is hand-editable, so an empty regex must not disable matching outright.
        return GoldenDiffConfig(
            goldenPaths = list("goldenPaths"),
            generatedPaths = list("generatedPaths"),
            generatedFileRegex = props.getProperty("generatedFileRegex")
                ?.takeIf { it.isNotBlank() } ?: GoldenDiffDefaults.GENERATED_FILE_REGEX,
            matchMode = MatchMode.fromName(props.getProperty("matchMode").orEmpty()),
            excludedSuffixes = list("excludedSuffixes").ifEmpty { GoldenDiffDefaults.EXCLUDED_SUFFIXES },
            trimTransparentPadding = props.getProperty("trimTransparentPadding").toBoolean(),
        )
    }

    fun save(projectRoot: File, config: GoldenDiffConfig) {
        val props = Properties().apply {
            setProperty("goldenPaths", config.goldenPaths.joinToString(","))
            setProperty("generatedPaths", config.generatedPaths.joinToString(","))
            setProperty("generatedFileRegex", config.generatedFileRegex)
            setProperty("matchMode", config.matchMode.name)
            setProperty("excludedSuffixes", config.excludedSuffixes.joinToString(","))
            setProperty("trimTransparentPadding", config.trimTransparentPadding.toString())
        }
        runCatching {
            configDir.mkdirs()
            fileFor(projectRoot).outputStream().use { props.store(it, "Golden Diff — ${projectRoot.absolutePath}") }
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
    }
}
