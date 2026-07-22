package com.github.dkwasniak.goldendiff.compare

import com.github.dkwasniak.goldendiff.settings.GoldenDiffDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GeneratedImageSourceTest {

    private lateinit var goldenRoot: File
    private lateinit var generatedRoot: File

    @Before
    fun setUp() {
        goldenRoot = Files.createTempDirectory("golden").toFile()
        generatedRoot = Files.createTempDirectory("generated").toFile()
    }

    @After
    fun tearDown() {
        goldenRoot.deleteRecursively()
        generatedRoot.deleteRecursively()
    }

    private fun file(dir: File, name: String): File = File(dir, name).apply { writeBytes(byteArrayOf(1)) }

    @Test
    fun `default regex maps _actual output to the golden`() {
        val golden = file(goldenRoot, "LoginScreen.png")
        val generated = file(generatedRoot, "LoginScreen_actual.png")

        val found = GeneratedImageSource.findForGolden(
            golden = golden,
            goldenRoots = listOf(goldenRoot),
            generatedRoots = listOf(generatedRoot),
            generatedFileRegex = GoldenDiffDefaults.GENERATED_FILE_REGEX,
        )

        assertEquals(generated, found)
    }

    @Test
    fun `regex without capture group falls back to stripping configured suffixes`() {
        val golden = file(goldenRoot, "LoginScreen.png")
        val generated = file(generatedRoot, "LoginScreen_compare.png")

        val found = GeneratedImageSource.findForGolden(
            golden = golden,
            goldenRoots = listOf(goldenRoot),
            generatedRoots = listOf(generatedRoot),
            generatedFileRegex = ".*\\.png",
            excludedSuffixes = listOf("_compare", "_actual"),
        )

        assertEquals(generated, found)
    }

    @Test
    fun `no matching generated file returns null`() {
        val golden = file(goldenRoot, "LoginScreen.png")
        file(generatedRoot, "OtherScreen_actual.png")

        val found = GeneratedImageSource.findForGolden(
            golden = golden,
            goldenRoots = listOf(goldenRoot),
            generatedRoots = listOf(generatedRoot),
            generatedFileRegex = GoldenDiffDefaults.GENERATED_FILE_REGEX,
        )

        assertNull(found)
    }
}
