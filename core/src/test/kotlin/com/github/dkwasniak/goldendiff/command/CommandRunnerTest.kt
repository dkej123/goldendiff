package com.github.dkwasniak.goldendiff.command

import com.github.dkwasniak.goldendiff.platform.Os
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class CommandRunnerTest {

    private val posix get() = Os.current == Os.MAC || Os.current == Os.LINUX

    @Test fun `streams output and reports a zero exit code`() {
        assumeTrue("POSIX shell required", posix)
        val dir = Files.createTempDirectory("gd-run").toFile()
        val lines = mutableListOf<String>()

        val result = CommandRunner.run("echo hello && echo world", dir, onLine = { lines.add(it) })

        assertTrue(result.succeeded)
        assertEquals(listOf("hello", "world"), lines)
    }

    @Test fun `reports a non-zero exit code`() {
        assumeTrue("POSIX shell required", posix)
        val dir = Files.createTempDirectory("gd-run").toFile()

        val result = CommandRunner.run("exit 3", dir)

        assertFalse(result.succeeded)
        assertEquals(3, result.exitCode)
    }
}
