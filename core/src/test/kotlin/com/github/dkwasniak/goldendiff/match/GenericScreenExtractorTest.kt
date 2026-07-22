package com.github.dkwasniak.goldendiff.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericScreenExtractorTest {

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
}
