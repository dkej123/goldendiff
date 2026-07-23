package com.github.dkwasniak.goldendiff.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyFileMatcherTest {

    private val paths = listOf(
        "app/src/main/kotlin/LoginScreen.kt",
        "app/src/test/kotlin/LoginScreenTest.kt",
        "app/src/main/kotlin/SettingsScreen.kt",
        "app/src/test/kotlin/SettingsScreenTest.kt",
        "app/src/main/kotlin/util/Strings.kt",
        "docs/testing.md",
    )

    private fun best(query: String): String? =
        FuzzyFileMatcher.search(paths, query).firstOrNull()?.path

    @Test
    fun `camel-hump query finds the intended file`() {
        assertEquals("app/src/test/kotlin/LoginScreenTest.kt", best("LSTest"))
    }

    @Test
    fun `camel case initials find a screen`() {
        val candidates = listOf(
            "core/src/main/LocationDetails.kt",
            "presentation/src/main/LocationDetailsScreen.kt",
            "presentation/src/main/LocationDetailsViewModel.kt",
        )

        assertEquals(
            "presentation/src/main/LocationDetailsScreen.kt",
            FuzzyFileMatcher.search(candidates, "LDS").first().path,
        )
    }

    @Test
    fun `exact file name wins`() {
        assertEquals("app/src/main/kotlin/LoginScreen.kt", best("LoginScreen.kt"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals("app/src/main/kotlin/LoginScreen.kt", best("loginscreen.kt"))
    }

    @Test
    fun `a name match outranks a directory match`() {
        // "test" appears in the src/test/ directory of two files, but one file is actually named
        // testing.md. Matching whole paths would bury it under everything under src/test/.
        val results = FuzzyFileMatcher.search(paths, "testing")
        assertEquals("docs/testing.md", results.first().path)
    }

    @Test
    fun `blank query returns nothing rather than everything`() {
        assertEquals(emptyList<String>(), FuzzyFileMatcher.search(paths, "   ").map { it.path })
    }

    @Test
    fun `a query that does not match returns no results`() {
        assertEquals(emptyList<String>(), FuzzyFileMatcher.search(paths, "zzzz").map { it.path })
        assertNull(FuzzyFileMatcher.score("LoginScreen.kt", "zzzz"))
    }

    @Test
    fun `letters must appear in order`() {
        assertNull(FuzzyFileMatcher.score("LoginScreen.kt", "nigoL"))
    }

    @Test
    fun `shorter path wins when scores tie`() {
        val tied = listOf("a/b/c/d/Screen.kt", "Screen.kt")
        assertEquals("Screen.kt", FuzzyFileMatcher.search(tied, "Screen").first().path)
    }

    @Test
    fun `results are capped by the limit`() {
        val many = (1..100).map { "File$it.kt" }
        assertEquals(5, FuzzyFileMatcher.search(many, "File", limit = 5).size)
    }

    @Test
    fun `ordering is deterministic for otherwise identical candidates`() {
        // Same length, same score - only the alphabetical tiebreaker separates them, which is what
        // keeps the list identical across platforms and runs.
        val same = listOf("Beta.kt", "Alfa.kt")
        val first = FuzzyFileMatcher.search(same, "a").map { it.path }
        val second = FuzzyFileMatcher.search(same.reversed(), "a").map { it.path }
        assertEquals(first, second)
    }

    @Test
    fun `prefix beats a mid-string occurrence`() {
        val candidates = listOf("MyLoginScreen.kt", "LoginScreen.kt")
        assertEquals("LoginScreen.kt", FuzzyFileMatcher.search(candidates, "Login").first().path)
    }

    @Test
    fun `humps are rewarded over scattered letters`() {
        val hump = FuzzyFileMatcher.score("LoginScreenTest.kt", "LST")!!
        val scattered = FuzzyFileMatcher.score("alsotesting.kt", "LST")!!
        assertTrue("hump=$hump scattered=$scattered", hump > scattered)
    }
}
