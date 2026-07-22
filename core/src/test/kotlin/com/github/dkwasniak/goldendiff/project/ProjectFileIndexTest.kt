package com.github.dkwasniak.goldendiff.project

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectFileIndexTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("golden-diff-index").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(relative: String) {
        File(root, relative).apply {
            parentFile.mkdirs()
            writeText("x")
        }
    }

    @Test
    fun `indexes files as project-relative paths with forward slashes`() {
        write("app/src/main/Login.kt")

        assertEquals(listOf("app/src/main/Login.kt"), ProjectFileIndex.scan(root).paths)
    }

    @Test
    fun `build and vcs directories are skipped entirely`() {
        write("src/Keep.kt")
        write("build/generated/Huge.kt")
        write(".git/objects/abc")
        write("node_modules/pkg/index.js")

        // On a real Android project these dwarf the source tree; descending into them would dominate
        // both scan time and the memory held by the result.
        assertEquals(listOf("src/Keep.kt"), ProjectFileIndex.scan(root).paths)
    }

    @Test
    fun `a directory named like a skipped one but nested is still skipped`() {
        write("modules/feature/build/Generated.kt")
        write("modules/feature/src/Real.kt")

        assertEquals(listOf("modules/feature/src/Real.kt"), ProjectFileIndex.scan(root).paths)
    }

    @Test
    fun `ordering is case-insensitive with a deterministic tiebreaker`() {
        // Tested against the comparator rather than the filesystem on purpose: macOS volumes are
        // case-insensitive by default, so `Alpha.kt` and `alpha.kt` are the same file there and a
        // disk-based version of this test would assert nothing.
        val sorted = listOf("beta.kt", "alpha.kt", "Alpha.kt").sortedWith(ProjectFileIndex.PATH_ORDER)

        assertEquals(listOf("Alpha.kt", "alpha.kt", "beta.kt"), sorted)
    }

    @Test
    fun `scanning twice yields the same order`() {
        write("src/Zeta.kt")
        write("src/alpha.kt")
        write("Beta.kt")

        assertEquals(ProjectFileIndex.scan(root).paths, ProjectFileIndex.scan(root).paths)
    }

    @Test
    fun `a symlinked directory pointing at an ancestor does not hang the scan`() {
        write("src/Real.kt")
        Files.createSymbolicLink(File(root, "src/loop").toPath(), root.toPath())

        val paths = ProjectFileIndex.scan(root).paths

        assertTrue(paths.contains("src/Real.kt"))
        assertFalse("must not descend through the link", paths.any { it.contains("loop/") })
    }

    @Test
    fun `file resolves an indexed path back to disk`() {
        write("app/Login.kt")
        val index = ProjectFileIndex.scan(root)

        assertTrue(index.file(index.paths.single()).isFile)
    }
}
