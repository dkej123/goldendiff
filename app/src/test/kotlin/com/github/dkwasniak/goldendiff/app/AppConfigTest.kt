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
import java.io.File
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
        val prompt = buildCliAgentPrompt("/project", "/golden-diff")

        val configure = prompt.indexOf("\"/golden-diff\" configure")
        val validate = prompt.indexOf("\"/golden-diff\" validate")
        val signal = prompt.lastIndexOf("--signal")
        assertTrue(configure >= 0)
        assertTrue(validate > configure)
        assertTrue(signal > validate)
        assertTrue(prompt.contains("--golden-dir-regex"))
        assertTrue(prompt.contains("--source-file"))
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
