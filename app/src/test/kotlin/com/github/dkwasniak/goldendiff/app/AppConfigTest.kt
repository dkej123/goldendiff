package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.match.MatchingDefaults
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import com.github.dkwasniak.goldendiff.settings.GoldenDiffDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

class AppConfigTest {

    private lateinit var originalHome: String
    private lateinit var tempHome: File

    @Before
    fun setUp() {
        // AppConfig keys everything off user.home; point it at a scratch dir so the real config is safe.
        originalHome = System.getProperty("user.home")
        tempHome = Files.createTempDirectory("gd-home").toFile()
        System.setProperty("user.home", tempHome.absolutePath)
    }

    @After
    fun tearDown() {
        System.setProperty("user.home", originalHome)
        tempHome.deleteRecursively()
    }

    private fun projectDir(name: String): File = File(tempHome, name).apply { mkdirs() }

    @Test
    fun `recent projects are newest first and de-duplicated`() {
        val a = projectDir("a")
        val b = projectDir("b")
        AppConfig.rememberProject(a)
        AppConfig.rememberProject(b)
        AppConfig.rememberProject(a) // re-opening a moves it back to the front, without duplicating it

        assertEquals(
            listOf(a.absolutePath, b.absolutePath),
            AppConfig.recentProjects().map { it.absolutePath },
        )
    }

    @Test
    fun `recent projects are capped at eight, keeping the most recent`() {
        (1..10).forEach { AppConfig.rememberProject(projectDir("p$it")) }

        val recents = AppConfig.recentProjects()
        assertEquals(8, recents.size)
        assertEquals("p10", recents.first().name)
        assertTrue(recents.none { it.name == "p1" || it.name == "p2" })
    }

    @Test
    fun `recent projects drop directories that no longer exist`() {
        val gone = projectDir("gone")
        AppConfig.rememberProject(gone)
        gone.deleteRecursively()

        assertTrue(AppConfig.recentProjects().none { it.name == "gone" })
    }

    @Test
    fun `onboarding completion flag persists`() {
        assertFalse(AppConfig.onboardingCompleted())
        AppConfig.markOnboardingCompleted()
        assertTrue(AppConfig.onboardingCompleted())
    }

    @Test
    fun `open files preserve order active tab and missing paths`() {
        val project = projectDir("open-files")
        val missing = "src/DeletedScreen.kt"
        val session = AppConfig.OpenFilesSession(
            paths = listOf("src/LoginScreen.kt", missing, "src/SettingsScreen.kt"),
            selectedPath = missing,
        )

        AppConfig.saveOpenFiles(project, session).getOrThrow()

        assertEquals(session, AppConfig.loadOpenFiles(project))
    }

    @Test
    fun `open files are independent per project and de-duplicated`() {
        val first = projectDir("first-open-files")
        val second = projectDir("second-open-files")

        AppConfig.saveOpenFiles(
            first,
            AppConfig.OpenFilesSession(
                paths = listOf("src/Screen.kt", "src/Screen.kt"),
                selectedPath = "src/Screen.kt",
            ),
        ).getOrThrow()

        assertEquals(
            AppConfig.OpenFilesSession(listOf("src/Screen.kt"), "src/Screen.kt"),
            AppConfig.loadOpenFiles(first),
        )
        assertEquals(AppConfig.OpenFilesSession(), AppConfig.loadOpenFiles(second))
    }

    @Test
    fun `open files ignore a selected path that is not an open tab`() {
        val project = projectDir("invalid-selected-tab")

        AppConfig.saveOpenFiles(
            project,
            AppConfig.OpenFilesSession(listOf("src/Open.kt"), "src/Closed.kt"),
        ).getOrThrow()

        assertEquals(
            AppConfig.OpenFilesSession(listOf("src/Open.kt"), null),
            AppConfig.loadOpenFiles(project),
        )
    }

    @Test
    fun `screenshot-test commands round trip`() {
        val project = projectDir("commands-project")
        val config = GoldenDiffConfig(
            goldenPaths = listOf("screenshots"),
            generateAllCommand = "./gradlew recordRoborazziDebug",
            generateFileCommand = "./gradlew recordRoborazziDebug --tests '*{file_name}Kt*'",
            verifyAllCommand = "./gradlew verifyRoborazziDebug",
            verifyFileCommand = "xcodebuild test -only-testing:App/{class_name}",
            verifyCaseCommand = "xcodebuild test -only-testing:App/{snapshot_class_name}/{snapshot_method_name}",
        )

        AppConfig.save(project, config)

        val loaded = AppConfig.load(project)
        assertEquals(config.generateAllCommand, loaded.generateAllCommand)
        assertEquals(config.generateFileCommand, loaded.generateFileCommand)
        assertEquals(config.verifyAllCommand, loaded.verifyAllCommand)
        assertEquals(config.verifyFileCommand, loaded.verifyFileCommand)
        assertEquals(config.verifyCaseCommand, loaded.verifyCaseCommand)
    }

    @Test
    fun `unset commands load as blank`() {
        val project = projectDir("no-commands")
        AppConfig.save(project, GoldenDiffConfig(goldenPaths = listOf("screenshots")))

        val loaded = AppConfig.load(project)
        assertEquals("", loaded.generateAllCommand)
        assertEquals("", loaded.verifyFileCommand)
        assertEquals("", loaded.verifyCaseCommand)
    }

    @Test
    fun `golden directory regex with commas round trips unchanged`() {
        val project = projectDir("regex-project")
        val regex = GoldenDiffConfig.goldenDirectoryRegexEntry(".*/screenshots/[a-z]{1,3}")

        AppConfig.save(project, GoldenDiffConfig(goldenPaths = listOf(regex)))

        assertEquals(listOf(regex), AppConfig.load(project).goldenPaths)
    }

    @Test
    fun `blank annotation regex is persisted and loaded as the visible default`() {
        val project = projectDir("annotation-default")

        AppConfig.save(project, GoldenDiffConfig(annotatedFunctionRegex = ""))

        assertEquals(MatchingDefaults.ANNOTATION_NAME_REGEX, AppConfig.load(project).annotatedFunctionRegex)
    }

    @Test
    fun `blank structural matching settings are persisted and loaded as visible defaults`() {
        val project = projectDir("structural-defaults")

        AppConfig.save(
            project,
            GoldenDiffConfig(
                generatedFileRegex = "",
                goldenFilePatterns = emptyList(),
                excludedSuffixes = emptyList(),
            ),
        )

        val loaded = AppConfig.load(project)
        assertEquals(GoldenDiffDefaults.GENERATED_FILE_REGEX, loaded.generatedFileRegex)
        assertEquals(MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS, loaded.goldenFilePatterns)
        assertTrue(loaded.excludedSuffixes.isEmpty())
    }

    @Test
    fun `fresh configuration exposes every effective default`() {
        val config = AppConfig.load(projectDir("fresh-defaults"))

        assertEquals(GoldenDiffDefaults.GENERATED_FILE_REGEX, config.generatedFileRegex)
        assertEquals(MatchingDefaults.ANNOTATION_NAME_REGEX, config.annotatedFunctionRegex)
        assertEquals(MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS, config.goldenFilePatterns)
        assertEquals(GoldenDiffDefaults.EXCLUDED_SUFFIXES, config.excludedSuffixes)
    }

    @Test
    fun `golden file pattern with comma round trips unchanged`() {
        val project = projectDir("pattern-comma")
        val pattern = "screen_[a-z]{1,3}"

        AppConfig.save(project, GoldenDiffConfig(goldenFilePatterns = listOf(pattern)))

        assertEquals(listOf(pattern), AppConfig.load(project).goldenFilePatterns)
    }

    @Test
    fun `CLI stores and resolves a golden directory regex`() {
        val project = projectDir("cli-regex")
        File(project, "feature-a/src/test/snapshots").mkdirs()
        File(project, "feature-b/src/test/snapshots").mkdirs()

        val exit = GoldenDiffCli.run(
            arrayOf(
                "configure",
                "--project", project.absolutePath,
                "--golden-dir-regex", ".*/src/test/snapshots",
                "--allow-non-git",
            ),
        )

        assertEquals(0, exit)
        val config = AppConfig.load(project)
        assertEquals(listOf("regex:.*/src/test/snapshots"), config.goldenPaths)
        assertEquals(2, config.resolvedGoldenPaths(project).size)
    }

    @Test
    fun `CLI signal identifies the project after settings are persisted`() {
        val project = projectDir("cli-signal")
        val startedAt = System.currentTimeMillis()

        val exit = GoldenDiffCli.run(
            arrayOf(
                "configure",
                "--project", project.absolutePath,
                "--golden-dir", "screenshots",
                "--verify-all-cmd", "./verify",
                "--signal",
                "--allow-non-git",
            ),
        )

        assertEquals(0, exit)
        assertEquals("./verify", AppConfig.load(project).verifyAllCommand)
        val signal = AppConfig.readCliSignal()
        assertEquals(project.canonicalPath, signal?.project?.canonicalPath)
        assertTrue(signal != null && signal.timestamp >= startedAt)
    }

    @Test
    fun `CLI fails instead of reporting an in-memory config when settings cannot be written`() {
        val project = projectDir("cli-unwritable-config")
        File(tempHome, ".config").writeText("blocks creation of the config directory")
        val stderr = ByteArrayOutputStream()
        val originalErr = System.err

        val exit = try {
            System.setErr(PrintStream(stderr))
            GoldenDiffCli.run(
                arrayOf(
                    "configure",
                    "--project", project.absolutePath,
                    "--golden-dir-regex", ".*/__Snapshots__",
                    "--allow-non-git",
                ),
            )
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(1, exit)
        assertTrue(stderr.toString().contains("Could not save Golden Diff settings"))
        assertTrue(AppConfig.load(project).goldenPaths.isEmpty())
    }

    @Test
    fun `CLI rejects an invalid golden directory regex`() {
        val project = projectDir("cli-invalid-regex")

        val exit = GoldenDiffCli.run(
            arrayOf(
                "configure",
                "--project", project.absolutePath,
                "--golden-dir-regex", "[",
                "--allow-non-git",
            ),
        )

        assertEquals(1, exit)
        assertTrue(AppConfig.load(project).goldenPaths.isEmpty())
    }

    @Test
    fun `agent prompt configures validates and signals in that order`() {
        val prompt = buildCliAgentPrompt("/project")

        val configure = prompt.indexOf("\"$INSTALLED_CLI_PATH\" configure")
        val validate = prompt.indexOf("\"$INSTALLED_CLI_PATH\" validate")
        val signal = prompt.lastIndexOf("--signal")
        assertTrue(configure >= 0)
        assertTrue(validate > configure)
        assertTrue(signal > validate)
        assertTrue(prompt.contains("--golden-dir-regex"))
        assertTrue(prompt.contains("--source-file"))
        assertTrue(prompt.contains("official framework recording switch"))
        assertTrue(prompt.contains("Do not require a wrapper"))
        assertTrue(prompt.contains("xcrun simctl list devices available"))
        assertTrue(prompt.contains("xcodebuild -showdestinations"))
        assertTrue(prompt.contains("exact per-device OS value"))
        assertTrue(prompt.contains("ask the user"))
        assertTrue(prompt.contains("Never silently choose"))
        assertTrue(prompt.contains("audit the four final command strings"))
        assertTrue(prompt.contains("Generate All/File enable recording"))
        assertTrue(prompt.contains("Verify All/File do not enable recording"))
        assertTrue(prompt.contains("no command contains an unresolved <placeholder>"))
    }

    @Test
    fun `agent prompt always targets installed app and keeps project analysis read only`() {
        assertEquals(
            "/Applications/Golden Diff.app/Contents/MacOS/Golden Diff",
            resolveCliBinaryPath(),
        )

        val prompt = buildCliAgentPrompt("/project")
        assertTrue(prompt.contains("Use that exact path for EVERY Golden Diff command"))
        assertTrue(prompt.contains("inspect the project read-only"))
        assertTrue(prompt.contains("Do not create, edit, move, or delete project files"))
        assertTrue(prompt.contains("do not apply it"))
        assertTrue(prompt.contains("\"$INSTALLED_CLI_PATH\" guides"))
        assertTrue(prompt.contains("\"$INSTALLED_CLI_PATH\" guide <name>"))
        assertTrue(prompt.contains("\"$INSTALLED_CLI_PATH\" show --project"))
        assertFalse(prompt.contains("\"/golden-diff\""))
        assertFalse(prompt.contains("Apply the guide's default project-side setup"))
    }

    @Test
    fun `swift snapshot guide reports environment change without applying it`() {
        val guide = checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "framework-guides/ios-swift-snapshot-testing.md",
            ),
        ).bufferedReader().use { it.readText() }
        val normalized = guide.replace(Regex("\\s+"), " ")
        assertTrue(normalized.contains("inspect the existing shell/CI environment read-only", ignoreCase = true))
        assertTrue(normalized.contains("Do not edit a shell profile"))
        assertFalse(normalized.contains("offer to append"))
    }

    @Test
    fun `swift snapshot guide configures generate commands through the supported record environment`() {
        val guide = checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "framework-guides/ios-swift-snapshot-testing.md",
            ),
        ).bufferedReader().use { it.readText() }
        val normalized = guide.replace(Regex("\\s+"), " ")

        assertTrue(guide.contains("1.17.0 or newer"))
        assertTrue(guide.contains("TEST_RUNNER_SNAPSHOT_TESTING_RECORD=all"))
        assertTrue(guide.contains("--generate-all-cmd"))
        assertTrue(guide.contains("--generate-file-cmd"))
        assertTrue(guide.contains("no wrapper and no source-code change are needed"))
        assertTrue(guide.contains("xcrun simctl list devices available"))
        assertTrue(guide.contains("xcodebuild -showdestinations"))
        assertTrue(guide.contains("xcodebuild -showdestinations` is authoritative"))
        assertTrue(guide.contains("never copy a device model", ignoreCase = true))
        assertTrue(guide.contains("Ask the user which test target/destination to use"))
        assertTrue(guide.contains("Do not save the Generate/Verify commands until the user answers"))
        assertTrue(guide.contains("Plugin “…” from package “…” must be enabled before it can be used"))
        assertTrue(guide.contains("Macro “…” from package “…” must be enabled before it can be used"))
        assertTrue(guide.contains("-skipPackagePluginValidation"))
        assertTrue(guide.contains("-skipMacroValidation"))
        assertTrue(normalized.contains("Do not add them preemptively"))
        assertTrue(guide.contains("all four set `TEST_RUNNER_SNAPSHOT_ARTIFACTS`"))
        assertTrue(guide.contains("only Generate All/File set `TEST_RUNNER_SNAPSHOT_TESTING_RECORD=all`"))
        assertTrue(guide.contains("only Generate File/Verify File use `-only-testing:"))
        assertFalse(guide.contains("iPhone 15 Pro"))
        assertFalse(guide.contains("There is no universal record command"))
    }

    @Test
    fun `CLI supports help after a command without requiring a project`() {
        assertEquals(0, GoldenDiffCli.run(arrayOf("configure", "--help")))
    }

    @Test
    fun `CLI rejects unknown options instead of silently ignoring them`() {
        val project = projectDir("cli-unknown-option")

        val exit = GoldenDiffCli.run(
            arrayOf(
                "configure",
                "--project", project.absolutePath,
                "--golden-dir-regexp", ".*/snapshots",
                "--allow-non-git",
            ),
        )

        assertEquals(2, exit)
        assertTrue(AppConfig.load(project).goldenPaths.isEmpty())
    }

    @Test
    fun `CLI validation expands regex and requires PNGs`() {
        val project = projectDir("cli-validate")
        val golden = File(project, "feature/src/test/snapshots").apply { mkdirs() }
        assertEquals(0, ProcessBuilder("git", "init", project.absolutePath).start().waitFor())
        assertEquals(
            0,
            GoldenDiffCli.run(
                arrayOf(
                    "configure",
                    "--project", project.absolutePath,
                    "--golden-dir-regex", ".*/src/test/snapshots",
                ),
            ),
        )

        assertEquals(
            1,
            GoldenDiffCli.run(arrayOf("validate", "--project", project.absolutePath, "--directories-only")),
        )
        File(golden, "screen.png").writeBytes(byteArrayOf(1))
        assertEquals(
            0,
            GoldenDiffCli.run(arrayOf("validate", "--project", project.absolutePath, "--directories-only")),
        )
    }

    @Test
    fun `CLI source validation rejects a root that removes the file-class key`() {
        val project = projectDir("cli-source-match")
        val source = File(project, "Sources/BadgeViewSnapshotTests.swift").apply {
            parentFile.mkdirs()
            writeText("final class BadgeViewSnapshotTests: SnapshotTestCase {}")
        }
        File(project, "Goldens/__Snapshots__/BadgeViewSnapshotTests/test_badge.1.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        assertEquals(0, ProcessBuilder("git", "init", project.absolutePath).start().waitFor())

        assertEquals(
            0,
            GoldenDiffCli.run(
                arrayOf(
                    "configure",
                    "--project", project.absolutePath,
                    "--golden-dir-regex", ".*/__Snapshots__/[^/]+",
                    "--match-mode", "file-class",
                ),
            ),
        )
        assertEquals(
            1,
            GoldenDiffCli.run(
                arrayOf("validate", "--project", project.absolutePath, "--source-file", source.relativeTo(project).path),
            ),
        )

        assertEquals(
            0,
            GoldenDiffCli.run(
                arrayOf(
                    "configure",
                    "--project", project.absolutePath,
                    "--golden-dir-regex", ".*/__Snapshots__",
                    "--match-mode", "file-class",
                ),
            ),
        )
        assertEquals(
            0,
            GoldenDiffCli.run(
                arrayOf("validate", "--project", project.absolutePath, "--source-file", source.relativeTo(project).path),
            ),
        )
    }
}
