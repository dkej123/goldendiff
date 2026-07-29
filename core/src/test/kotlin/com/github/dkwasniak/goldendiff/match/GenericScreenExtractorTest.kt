package com.github.dkwasniak.goldendiff.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericScreenExtractorTest {

    private val previewOrTest = ".*Preview.*|Test"

    private fun extract(fileName: String, text: String) = GenericScreenExtractor.extract(fileName, text)

    @Test
    fun `keeps the file base name`() {
        val screen = extract("Button", "// nothing declared here")
        assertEquals("Button", screen.fileName)
    }

    @Test
    fun `extracts react function and arrow components from tsx`() {
        val text = """
            import React from 'react';
            export function PrimaryButton(props: Props) { return <button/>; }
            export const DisabledButton = (props: Props) => <button disabled/>;
            const Card: React.FC = () => <div/>;
            const Badge = styled.span`color: red;`;
            export default memo(function IconButton() { return null; });
        """.trimIndent()

        val functions = extract("Button.tsx", text).functionNames
        assertTrue(functions.toString(), "PrimaryButton" in functions)
        assertTrue(functions.toString(), "DisabledButton" in functions)
        assertTrue(functions.toString(), "Card" in functions)
        assertTrue(functions.toString(), "Badge" in functions)
    }

    @Test
    fun `extracts class and interface names`() {
        val text = """
            class LoginScreen extends React.Component {}
            interface Props {}
            struct ProfileView: View {}
        """.trimIndent()

        val classes = extract("LoginScreen.tsx", text).classNames
        assertTrue(classes.toString(), "LoginScreen" in classes)
        assertTrue(classes.toString(), "ProfileView" in classes)
    }

    @Test
    fun `extracts single-token test and story titles`() {
        val text = """
            describe('Button', () => {
              it('primary', async () => { await expect(page).toHaveScreenshot(); });
              test("Disabled", () => {});
            });
        """.trimIndent()

        val functions = extract("Button.spec.ts", text).functionNames
        assertTrue(functions.toString(), "primary" in functions)
        assertTrue(functions.toString(), "Disabled" in functions)
    }

    @Test
    fun `extracts swift declarations`() {
        val text = """
            struct ContentView: View { }
            func makeHeader() -> some View { EmptyView() }
        """.trimIndent()

        val screen = extract("ContentView.swift", text)
        assertTrue(screen.classNames.toString(), "ContentView" in screen.classNames)
        assertTrue(screen.functionNames.toString(), "makeHeader" in screen.functionNames)
    }

    @Test
    fun `plain variables are not treated as components`() {
        val text = "const count = 42; let title = 'hello';"
        assertEquals(emptyList<String>(), extract("misc.ts", text).functionNames)
    }

    @Test
    fun `annotated mode keeps only functions with matching annotations`() {
        val text = """
            @Preview
            fun LoginScreenPreview() { }

            @Composable
            fun LoginScreen() { }

            fun buildFakeData() { }

            fun testLogin() { }
        """.trimIndent()

        val functions = GenericScreenExtractor.extract("LoginScreen.kt", text, previewOrTest).functionNames
        assertTrue(functions.toString(), "LoginScreenPreview" in functions)
        assertFalse(functions.toString(), "testLogin" in functions)
        assertFalse(functions.toString(), "LoginScreen" in functions)
        assertFalse(functions.toString(), "buildFakeData" in functions)
    }

    @Test
    fun `annotated mode drops unannotated swift test methods`() {
        val text = """
            final class LoginSnapshotTests: XCTestCase {
                override func setUp() { }
                func test_login_default() { assertSnapshot(of: view) }
                func makeSut() -> Sut { Sut() }
            }
        """.trimIndent()

        val functions = GenericScreenExtractor.extract("LoginSnapshotTests.swift", text, previewOrTest).functionNames
        assertFalse(functions.toString(), "test_login_default" in functions)
        assertFalse(functions.toString(), "setUp" in functions)
        assertFalse(functions.toString(), "makeSut" in functions)
    }

    @Test
    fun `annotated mode drops non-function test titles and assigned components`() {
        val text = """
            const LoginScreen = () => null
            test("login", () => {})
        """.trimIndent()

        val functions = GenericScreenExtractor.extract("Login.ts", text, previewOrTest).functionNames
        assertFalse(functions.toString(), "LoginScreen" in functions)
        assertFalse(functions.toString(), "login" in functions)
    }

    @Test
    fun `annotated mode keeps functions whose annotation matches the regex`() {
        val text = """
            @Test
            func login_snapshot() { }
        """.trimIndent()

        val functions = GenericScreenExtractor.extract("Tests.swift", text, previewOrTest).functionNames
        assertTrue(functions.toString(), "login_snapshot" in functions)
    }

    @Test
    fun `annotated mode associates a function with a multiline annotation`() {
        val text = """
            @Preview(
                name = "Dark",
                device = "spec:width=360dp,height=708dp",
            )
            @Composable
            fun LoginFigmaPreview() { }
        """.trimIndent()

        val functions = GenericScreenExtractor.extract("Login.kt", text, previewOrTest).functionNames

        assertTrue(functions.toString(), "LoginFigmaPreview" in functions)
    }

    @Test
    fun `an inline annotation does not leak onto the following function`() {
        val text = """
            @Preview fun AlphaPreview() { }
            fun beta() { }
        """.trimIndent()

        val functions = GenericScreenExtractor.extract("Screens.kt", text, previewOrTest).functionNames
        assertTrue(functions.toString(), "AlphaPreview" in functions)
        assertFalse(functions.toString(), "beta" in functions)
    }

    @Test
    fun `a null regex keeps every function (unchanged behaviour)`() {
        val text = """
            fun helperOnly() { }
        """.trimIndent()

        assertTrue("helperOnly" in GenericScreenExtractor.extract("F.kt", text, null).functionNames)
    }
}
