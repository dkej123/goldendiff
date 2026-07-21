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

    /** Creates a PNG, honoring `/` in [name] as sub-directories. */
    private fun png(name: String): File =
        File(root, name).apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }

    private fun screen(
        vararg functions: String,
        classes: List<String> = emptyList(),
        fileName: String = "",
        caret: String? = null,
    ) = CurrentScreen.Screen(functions.toList(), classes, fileName, caret)

    private fun findByMethod(
        screen: CurrentScreen.Screen,
        excludedSuffixes: List<String> = emptyList(),
    ) = GoldenFinder.find(listOf(root), screen, MatchMode.ANNOTATED_METHOD, excludedSuffixes)

    private fun findByRegex(
        screen: CurrentScreen.Screen,
        patterns: List<String>,
        excludedSuffixes: List<String> = emptyList(),
    ) = GoldenFinder.find(listOf(root), screen, MatchMode.FILE_CLASS_REGEX, excludedSuffixes, patterns)

    // region ANNOTATED_METHOD mode

    @Test
    fun `matches golden by case-insensitive substring of a method name`() {
        png("LoginScreenPreview_light.png")
        png("Unrelated.png")

        val result = findByMethod(screen("loginscreenpreview"))

        assertEquals(listOf("LoginScreenPreview_light.png"), result.map { it.name })
    }

    @Test
    fun `does not match method as prefix of another preview function`() {
        png("PlansScreenKt.PlansScreenPreview.png")
        png("PlansScreenKt.PlansScreenBottomSheetPreview.png")

        val result = findByMethod(screen("PlansScreenPreview"))

        assertEquals(listOf("PlansScreenKt.PlansScreenPreview.png"), result.map { it.name })
    }

    @Test
    fun `method matches variant suffix after separator`() {
        png("PlansScreenKt.PlansScreenPreview.Dark.png")
        png("PlansScreenKt.PlansScreenPreview_light.png")
        png("PlansScreenKt.PlansScreenPreviewExtra.png")

        val result = findByMethod(screen("PlansScreenPreview"))

        assertEquals(
            listOf("PlansScreenKt.PlansScreenPreview.Dark.png", "PlansScreenKt.PlansScreenPreview_light.png"),
            result.map { it.name },
        )
    }

    @Test
    fun `does not match method inside a larger word`() {
        png("PlansScreenKt.PlansScreenStatesPreview.Dark.png")

        val result = findByMethod(screen("Stat"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `only png files are considered`() {
        png("LoginScreenPreview.png")
        File(root, "LoginScreenPreview.txt").writeText("x")

        val result = findByMethod(screen("LoginScreenPreview"))

        assertEquals(listOf("LoginScreenPreview.png"), result.map { it.name })
    }

    @Test
    fun `configured suffixes are excluded, others kept`() {
        png("LoginScreenPreview.png")
        png("LoginScreenPreview_compare.png")
        png("LoginScreenPreview_actual.png")

        val excluded = findByMethod(screen("LoginScreenPreview"), listOf("_compare", "_actual"))
        assertEquals(listOf("LoginScreenPreview.png"), excluded.map { it.name })

        val noExclusion = findByMethod(screen("LoginScreenPreview"))
        assertEquals(3, noExclusion.size)
    }

    @Test
    fun `caret match is sorted first`() {
        png("AScreenPreview.png")
        png("ZScreenPreview.png")

        val result = findByMethod(screen("AScreenPreview", "ZScreenPreview", caret = "ZScreenPreview"))

        assertEquals("ZScreenPreview.png", result.first().name)
    }

    @Test
    fun `generic method names do not match unrelated screens`() {
        png("PlansScreen.png")
        png("ProfileScreen.png")

        val result = findByMethod(screen("Screen", "State", "ProfileScreen"))

        assertEquals(listOf("ProfileScreen.png"), result.map { it.name })
    }

    @Test
    fun `only generic method names yield no matches`() {
        png("PlansScreen.png")

        assertTrue(findByMethod(screen("Screen", "State")).isEmpty())
    }

    @Test
    fun `no method candidates yields no matches`() {
        png("LoginScreen.png")

        assertTrue(findByMethod(screen()).isEmpty())
    }

    @Test
    fun `method matches golden nested in package sub-directories`() {
        png("com/example/MyTest.emptyState.png")
        png("com/example/OtherTest.fullState.png")

        val result = findByMethod(screen("emptyState"))

        assertEquals(listOf("MyTest.emptyState.png"), result.map { it.name })
    }

    // endregion

    // region FILE_CLASS_REGEX mode

    @Test
    fun `file name placeholder matches file based goldens`() {
        png("LoginScreen_empty.png")
        png("emptyState.png")

        val result = findByRegex(screen(fileName = "LoginScreen"), listOf("{file_name}_.*\\.png"))

        assertEquals(listOf("LoginScreen_empty.png"), result.map { it.name })
    }

    @Test
    fun `file name placeholder escapes regex characters in file names`() {
        png("Login.Screen_empty.png")
        png("LoginXScreen_empty.png")

        val result = findByRegex(screen(fileName = "Login.Screen"), listOf("{file_name}_.*\\.png"))

        assertEquals(listOf("Login.Screen_empty.png"), result.map { it.name })
    }

    @Test
    fun `class name placeholder matches class based goldens`() {
        png("LoginScreenTest_reference.png")
        png("ProfileScreenTest_reference.png")

        val result = findByRegex(
            screen(classes = listOf("LoginScreenTest"), fileName = "LoginScreen"),
            listOf("{class_name}_reference\\.png"),
        )

        assertEquals(listOf("LoginScreenTest_reference.png"), result.map { it.name })
    }

    @Test
    fun `class name placeholder tries all useful class names`() {
        png("LoginScreen_reference.png")
        png("Helper_reference.png")
        png("Other_reference.png")

        val result = findByRegex(
            screen(classes = listOf("Helper", "LoginScreen"), fileName = "LoginScreenFile"),
            listOf("{class_name}_reference\\.png"),
        )

        assertEquals(listOf("Helper_reference.png", "LoginScreen_reference.png"), result.map { it.name })
    }

    @Test
    fun `class name placeholder ignores generic class names`() {
        png("Screen_reference.png")
        png("LoginScreen_reference.png")

        val result = findByRegex(
            screen(classes = listOf("Screen", "LoginScreen"), fileName = "LoginScreenFile"),
            listOf("{class_name}_reference\\.png"),
        )

        assertEquals(listOf("LoginScreen_reference.png"), result.map { it.name })
    }

    @Test
    fun `placeholder without anchors matches anywhere in the path`() {
        png("LoginScreen_dark.png")
        png("Profile_dark.png")

        val result = findByRegex(screen(classes = listOf("LoginScreen")), listOf("{class_name}"))

        assertEquals(listOf("LoginScreen_dark.png"), result.map { it.name })
    }

    @Test
    fun `class name placeholder matches class nested as a directory`() {
        png("MyVCTests/testEmpty.png")
        png("OtherTests/testFull.png")

        val result = findByRegex(screen(classes = listOf("MyVCTests")), listOf("{class_name}"))

        assertEquals(listOf("testEmpty.png"), result.map { it.name })
    }

    @Test
    fun `plain regex pattern can match filenames without placeholders`() {
        png("auth_login_empty_state.png")
        png("settings_empty_state.png")

        val result = findByRegex(screen(fileName = "UI"), listOf("auth_login_.*\\.png"))

        assertEquals(listOf("auth_login_empty_state.png"), result.map { it.name })
    }

    @Test
    fun `multiple patterns are ORed`() {
        png("LoginScreen_empty.png")
        png("emptyState.png")
        png("ProfileScreen_empty.png")

        val result = findByRegex(
            screen(classes = listOf("EmptyState"), fileName = "LoginScreen"),
            listOf("{file_name}_.*\\.png", "{class_name}\\.png"),
        )

        assertEquals(listOf("emptyState.png", "LoginScreen_empty.png"), result.map { it.name })
    }

    @Test
    fun `suffix exclusion is applied before patterns`() {
        png("LoginScreen.png")
        png("LoginScreen_actual.png")
        png("LoginScreen_compare.png")

        val result = findByRegex(
            screen(fileName = "LoginScreen"),
            listOf("{file_name}.*\\.png"),
            excludedSuffixes = listOf("_actual", "_compare"),
        )

        assertEquals(listOf("LoginScreen.png"), result.map { it.name })
    }

    @Test
    fun `blank patterns produce no matches`() {
        png("LoginScreen.png")

        assertTrue(findByRegex(screen(fileName = "LoginScreen"), listOf("", "  ")).isEmpty())
    }

    @Test
    fun `invalid pattern is ignored`() {
        png("LoginScreen.png")

        assertTrue(findByRegex(screen(fileName = "LoginScreen"), listOf("[")).isEmpty())
    }

    // endregion
}
