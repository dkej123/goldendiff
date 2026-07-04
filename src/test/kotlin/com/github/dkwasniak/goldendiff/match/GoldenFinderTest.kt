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
    fun `matches candidate followed by a camel-case suffix`() {
        png("PlansScreenKt.PlansScreenStatesPreview.Dark.png")
        png("Unrelated.png")

        val result = GoldenFinder.find(listOf(root), screen("PlansScreen"))

        assertEquals(listOf("PlansScreenKt.PlansScreenStatesPreview.Dark.png"), result.map { it.name })
    }

    @Test
    fun `does not match candidate inside a larger word`() {
        png("PlansScreenKt.PlansScreenStatesPreview.Dark.png")

        val result = GoldenFinder.find(listOf(root), screen("Stat"))

        assertTrue(result.isEmpty())
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

        val result = GoldenFinder.find(listOf(root), screen("AScreen", "ZScreen", caret = "ZScreen"))

        assertEquals("ZScreen.png", result.first().name)
    }

    @Test
    fun `generic candidates do not match unrelated screens`() {
        png("PlansScreen.png")
        png("ProfileScreen.png")

        val result = GoldenFinder.find(listOf(root), screen("Screen", "State", "ProfileScreen"))

        assertEquals(listOf("ProfileScreen.png"), result.map { it.name })
    }

    @Test
    fun `only generic candidates yield no matches`() {
        png("PlansScreen.png")

        val result = GoldenFinder.find(listOf(root), screen("Screen", "State"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty name set yields no matches`() {
        png("LoginScreen.png")

        assertTrue(GoldenFinder.find(listOf(root), screen()).isEmpty())
    }
}
