package com.github.dkwasniak.goldendiff.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GeneratedCounterpartIndexTest {
    private fun tempDirectory(): File = Files.createTempDirectory("generated-index").toFile()

    private fun generated(root: File, path: String): File =
        File(root, path).apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }

    @Test
    fun `roots are scanned once and reused for every golden resolution`() {
        val project = tempDirectory()
        val goldenRoot = File(project, "goldens").apply { mkdirs() }
        val generatedRoot = File(project, "output").apply { mkdirs() }
        val first = generated(generatedRoot, "First_actual.png")
        val second = generated(generatedRoot, "Second_actual.png")
        generated(generatedRoot, "notes.txt")
        val index = index(generatedRoot)

        repeat(10) {
            assertEquals(first, (index.resolve(File(goldenRoot, "First.png"), listOf(goldenRoot)) as GeneratedCounterpartResolution.Exact).file)
            assertEquals(second, (index.resolve(File(goldenRoot, "Second.png"), listOf(goldenRoot)) as GeneratedCounterpartResolution.Exact).file)
        }

        assertEquals(1, index.stats.scannedRoots)
        assertEquals(3, index.stats.visitedFiles)
        assertEquals(2, index.stats.indexedCandidates)
    }

    @Test
    fun `same relative directory wins before fallback candidates`() {
        val project = tempDirectory()
        val goldenRoot = File(project, "goldens").apply { mkdirs() }
        val generatedRoot = File(project, "output").apply { mkdirs() }
        val expected = generated(generatedRoot, "feature/login/Login_actual.png")
        generated(generatedRoot, "feature/profile/Login_actual.png")
        val index = index(generatedRoot)

        val resolution = index.resolve(File(goldenRoot, "feature/login/Login.png"), listOf(goldenRoot))

        assertEquals(expected, (resolution as GeneratedCounterpartResolution.Exact).file)
    }

    @Test
    fun `unique directory suffix provides deterministic fallback ranking`() {
        val project = tempDirectory()
        val goldenRoot = File(project, "goldens").apply { mkdirs() }
        val generatedRoot = File(project, "output").apply { mkdirs() }
        val expected = generated(generatedRoot, "build/login/Login_actual.png")
        generated(generatedRoot, "build/profile/Login_actual.png")
        val index = index(generatedRoot)

        val resolution = index.resolve(File(goldenRoot, "feature/login/Login.png"), listOf(goldenRoot))

        assertEquals(expected, (resolution as GeneratedCounterpartResolution.Exact).file)
    }

    @Test
    fun `tied collisions are ambiguous in deterministic path order`() {
        val project = tempDirectory()
        val goldenRoot = File(project, "goldens").apply { mkdirs() }
        val generatedRoot = File(project, "output").apply { mkdirs() }
        val z = generated(generatedRoot, "z/Login_actual.png")
        val a = generated(generatedRoot, "a/Login_actual.png")
        val index = index(generatedRoot)

        val resolution = index.resolve(File(goldenRoot, "Login.png"), listOf(goldenRoot))

        assertTrue(resolution is GeneratedCounterpartResolution.Ambiguous)
        assertEquals(listOf(a, z), (resolution as GeneratedCounterpartResolution.Ambiguous).candidates)
    }

    @Test
    fun `duplicate configured roots are scanned and indexed once`() {
        val project = tempDirectory()
        val generatedRoot = File(project, "output").apply { mkdirs() }
        generated(generatedRoot, "Login_actual.png")

        val index = GeneratedCounterpartIndex.build(
            listOf(generatedRoot, generatedRoot.absoluteFile, File(generatedRoot, ".")),
            "^(.+)_actual\\.png$",
        )

        assertEquals(1, index.stats.scannedRoots)
        assertEquals(1, index.stats.indexedCandidates)
    }

    @Test
    fun `configuration fingerprint and candidates follow the active regex`() {
        val project = tempDirectory()
        val goldenRoot = File(project, "goldens").apply { mkdirs() }
        val generatedRoot = File(project, "output").apply { mkdirs() }
        val actual = generated(generatedRoot, "Login_actual.png")
        generated(generatedRoot, "Login_reference.png")
        val actualIndex = GeneratedCounterpartIndex.build(listOf(generatedRoot), "^(.+)_actual\\.png$")
        val referenceIndex = GeneratedCounterpartIndex.build(listOf(generatedRoot), "^(.+)_reference\\.png$")

        assertTrue(actualIndex.configuration != referenceIndex.configuration)
        assertEquals(
            actual,
            (actualIndex.resolve(File(goldenRoot, "Login.png"), listOf(goldenRoot)) as GeneratedCounterpartResolution.Exact).file,
        )
        assertTrue(
            GeneratedCounterpartIndex.build(listOf(generatedRoot), "[invalid")
                .resolve(File(goldenRoot, "Login.png"), listOf(goldenRoot)) is GeneratedCounterpartResolution.Missing,
        )
    }

    @Test
    fun `explicit generated candidate preserves its snapshot class directory without scanning simulator temp`() {
        val project = tempDirectory()
        val goldenRoot = File(project, "goldens").apply { mkdirs() }
        val golden = generated(goldenRoot, "Badge/__Snapshots__/BadgeViewSnapshotTests/test_badge.1.png")
        val actual = generated(
            File(project, "CoreSimulator/data/tmp"),
            "BadgeViewSnapshotTests/test_badge.1.png",
        )

        val index = GeneratedCounterpartIndex.build(
            generatedRoots = emptyList(),
            generatedFileRegex = "^(.+)\\.png$",
            explicitCandidates = listOf(
                GeneratedCounterpartIndex.ExplicitCandidate(
                    file = actual,
                    relativeParent = "BadgeViewSnapshotTests",
                ),
            ),
        )

        assertEquals(actual, (index.resolve(golden, listOf(goldenRoot)) as GeneratedCounterpartResolution.Exact).file)
        assertEquals(0, index.stats.scannedRoots)
        assertEquals(1, index.stats.indexedCandidates)
    }

    @Test
    fun `host-resolved explicit basename is independent of the directory filename regex`() {
        val project = tempDirectory()
        val goldenRoot = File(project, "goldens").apply { mkdirs() }
        val golden = generated(goldenRoot, "Tests/test_badge.1.png")
        val actual = generated(File(project, "simulator/tmp"), "Tests/test_badge.1.png")

        val index = GeneratedCounterpartIndex.build(
            generatedRoots = emptyList(),
            generatedFileRegex = "^(.+)_actual\\.png$",
            explicitCandidates = listOf(
                GeneratedCounterpartIndex.ExplicitCandidate(
                    file = actual,
                    relativeParent = "Tests",
                    goldenBase = "test_badge.1",
                ),
            ),
        )

        assertEquals(actual, (index.resolve(golden, listOf(goldenRoot)) as GeneratedCounterpartResolution.Exact).file)
    }

    private fun index(generatedRoot: File): GeneratedCounterpartIndex =
        GeneratedCounterpartIndex.build(listOf(generatedRoot), "^(.+)_actual\\.png$")
}
