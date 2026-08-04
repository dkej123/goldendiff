package com.github.dkwasniak.goldendiff.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GoldenDiffConfigTest {

    @Test
    fun `literal and regex golden directories resolve together`() {
        val root = Files.createTempDirectory("golden-config").toFile()
        try {
            File(root, "shared/goldens").mkdirs()
            File(root, "feature-a/src/test/snapshots").mkdirs()
            File(root, "feature-b/src/test/snapshots").mkdirs()

            val config = GoldenDiffConfig(
                goldenPaths = listOf(
                    "shared/goldens",
                    GoldenDiffConfig.goldenDirectoryRegexEntry(".*/src/test/snapshots"),
                ),
            )

            assertEquals(
                setOf(
                    "shared/goldens",
                    "feature-a/src/test/snapshots",
                    "feature-b/src/test/snapshots",
                ),
                config.resolvedGoldenPaths(root)
                    .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
                    .toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `directory regex matches the complete relative path`() {
        val root = Files.createTempDirectory("golden-config").toFile()
        try {
            File(root, "feature/src/test/snapshots/nested").mkdirs()
            val config = GoldenDiffConfig(
                goldenPaths = listOf(GoldenDiffConfig.goldenDirectoryRegexEntry("src/test/snapshots")),
            )

            assertTrue(config.resolvedGoldenPaths(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `directory regex skips build and dependency output directories`() {
        val root = Files.createTempDirectory("golden-config").toFile()
        try {
            // A real golden directory the regex should find.
            File(root, "UIComponents/Tests/UIComponentsTests/Badge/__Snapshots__").mkdirs()
            // Look-alikes buried in build/dependency output that must never be scanned — on an iOS
            // project these hold the whole SPM checkout + DerivedData and dominate the walk cost.
            File(root, ".build/SPM/checkouts/UIComponents/Tests/x/__Snapshots__").mkdirs()
            File(root, "DerivedData/UIComponents/Tests/x/__Snapshots__").mkdirs()
            File(root, "Pods/UIComponents/Tests/x/__Snapshots__").mkdirs()

            val config = GoldenDiffConfig(
                goldenPaths = listOf(GoldenDiffConfig.goldenDirectoryRegexEntry(".*/__Snapshots__")),
            )

            assertEquals(
                setOf("UIComponents/Tests/UIComponentsTests/Badge/__Snapshots__"),
                config.resolvedGoldenPaths(root)
                    .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
                    .toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid directory regex resolves safely to no directories`() {
        val root = Files.createTempDirectory("golden-config").toFile()
        try {
            val config = GoldenDiffConfig(
                goldenPaths = listOf(GoldenDiffConfig.goldenDirectoryRegexEntry("[")),
            )

            assertTrue(config.resolvedGoldenPaths(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `watcher membership checks do not require scanning resolved directories`() {
        val root = Files.createTempDirectory("golden-config-membership").toFile()
        try {
            val config = GoldenDiffConfig(
                goldenPaths = listOf(
                    "shared/goldens",
                    GoldenDiffConfig.goldenDirectoryRegexEntry(".*/src/test/snapshots"),
                ),
                generatedPaths = listOf("build/test-output"),
            )

            assertTrue(config.containsGoldenFile(root, File(root, "shared/goldens/nested/Literal.png")))
            assertTrue(config.containsGoldenFile(root, File(root, "feature/src/test/snapshots/Nested.png")))
            assertTrue(config.containsGeneratedFile(root, File(root, "build/test-output/Screen_actual.png")))
            assertTrue(!config.containsGoldenFile(root, File(root, "feature/screenshots/Other.png")))
            assertTrue(!config.containsGoldenFile(root, File(root, ".build/cache/src/test/snapshots/Noise.png")))
        } finally {
            root.deleteRecursively()
        }
    }

}
