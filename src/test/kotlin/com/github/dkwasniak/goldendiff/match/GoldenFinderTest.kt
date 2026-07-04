package com.github.dkwasniak.goldendiff.match

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GoldenFinderTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("goldens").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun png(name: String): File = File(root, name).apply { writeBytes(byteArrayOf(1)) }

    private fun screen(vararg names: String, caret: String? = null) =
        CurrentScreen.Screen(names.toList(), caret)

    @Test
    fun `matches golden by case-insensitive substring of a candidate name`() {
        png("LoginScreen_light.png")
        png("Unrelated.png")

        val result = GoldenFinder.find(listOf(root), screen("loginscreen"))

        assertEquals(listOf("LoginScreen_light.png"), result.map { it.name })
    }

    @Test
    fun `only png files are considered`() {
        png("LoginScreen.png")
        File(root, "LoginScreen.txt").writeText("x")

        val result = GoldenFinder.find(listOf(root), screen("LoginScreen"))

        assertEquals(listOf("LoginScreen.png"), result.map { it.name })
    }

    @Test
    fun `configured suffixes are excluded, others kept`() {
        png("LoginScreen.png")
        png("LoginScreen_compare.png")
        png("LoginScreen_actual.png")

        val excluded = GoldenFinder.find(listOf(root), screen("LoginScreen"), listOf("_compare", "_actual"))
        assertEquals(listOf("LoginScreen.png"), excluded.map { it.name })

        val noExclusion = GoldenFinder.find(listOf(root), screen("LoginScreen"), emptyList())
        assertEquals(3, noExclusion.size)
    }

    @Test
    fun `blank suffixes are ignored so nothing is excluded`() {
        png("LoginScreen.png")
        png("LoginScreen_compare.png")

        val result = GoldenFinder.find(listOf(root), screen("LoginScreen"), listOf("", "  "))

        assertEquals(2, result.size)
    }

    @Test
    fun `caret match is sorted first`() {
        png("AScreen.png")
        png("ZScreen.png")

        val result = GoldenFinder.find(listOf(root), screen("Screen", caret = "ZScreen"))

        assertEquals("ZScreen.png", result.first().name)
    }

    @Test
    fun `empty name set yields no matches`() {
        png("LoginScreen.png")

        assertTrue(GoldenFinder.find(listOf(root), screen()).isEmpty())
    }
}
