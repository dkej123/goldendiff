package com.github.dkwasniak.goldendiff.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTreeTest {

    @Test
    fun `groups indexed paths and puts directories before files`() {
        val tree = buildProjectTree(
            listOf(
                "README.md",
                "feature/src/Screen.kt",
                "app/src/Main.kt",
                "app/build.gradle.kts",
            ),
        )

        assertEquals(listOf("app", "feature", "README.md"), tree.map { it.name })
        assertTrue(tree[0].isDirectory)
        assertEquals(listOf("src", "build.gradle.kts"), tree[0].children.map { it.name })
        assertFalse(tree.last().isDirectory)
    }

    @Test
    fun `keeps project-relative paths on every node`() {
        val app = buildProjectTree(listOf("app/src/main/Main.kt")).single()
        val src = app.children.single()
        val main = src.children.single()
        val file = main.children.single()

        assertEquals("app/src/main/Main.kt", file.path)
    }
}
