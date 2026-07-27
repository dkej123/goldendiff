package com.github.dkwasniak.goldendiff.command

import com.github.dkwasniak.goldendiff.match.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandTemplateTest {

    private fun screen(
        fileName: String = "LoginScreen",
        classNames: List<String> = emptyList(),
    ) = Screen(functionNames = emptyList(), classNames = classNames, fileName = fileName, caretName = null)

    @Test fun `substitutes file name`() {
        assertEquals(
            "./gradlew record --tests '*LoginScreenKt*'",
            CommandTemplate.expand("./gradlew record --tests '*{file_name}Kt*'", screen()),
        )
    }

    @Test fun `class name uses first declared class`() {
        assertEquals(
            "-only-testing:App/LoginTests",
            CommandTemplate.expand(
                "-only-testing:App/{class_name}",
                screen(fileName = "Login", classNames = listOf("LoginTests", "Other")),
            ),
        )
    }

    @Test fun `class name falls back to file name when no class declared`() {
        assertEquals(
            "run Login",
            CommandTemplate.expand("run {class_name}", screen(fileName = "Login")),
        )
    }

    @Test fun `file path is substituted only when provided`() {
        assertEquals(
            "pytest tests/login",
            CommandTemplate.expand("pytest {file_path}", screen(), relativePath = "tests/login"),
        )
    }

    @Test fun `unknown file path is left untouched so misconfiguration is visible`() {
        assertEquals(
            "pytest {file_path}",
            CommandTemplate.expand("pytest {file_path}", screen(), relativePath = null),
        )
    }

    @Test fun `command without placeholders is returned verbatim`() {
        assertEquals(
            "./gradlew verifyRoborazziDebug",
            CommandTemplate.expand("./gradlew verifyRoborazziDebug", screen()),
        )
    }
}
