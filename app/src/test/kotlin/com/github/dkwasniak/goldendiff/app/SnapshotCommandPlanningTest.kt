package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotCommandPlanningTest {

    @Test
    fun `view refresh uses each generator once in stable order`() {
        val view = SnapshotSourceFile(
            relativePath = "Sources/LoginView.swift",
            goldenPaths = listOf("a", "b"),
            status = SnapshotFileStatus.FAILED,
            failedCount = 2,
            cases = listOf(
                case("loading", "Tests/ZTests.swift"),
                case("default", "Tests/ATests.swift"),
                case("dark", "Tests/ZTests.swift"),
            ),
        )

        assertEquals(
            listOf("Tests/ATests.swift", "Tests/ZTests.swift"),
            snapshotViewGeneratorPaths(view, view.relativePath),
        )
    }

    @Test
    fun `identical expanded commands are deduplicated`() {
        assertEquals(
            listOf("verify Login", "verify Profile"),
            deduplicateSnapshotCommands(listOf("verify Login", "verify Login", "verify Profile")),
        )
    }

    @Test
    fun `view without cases verifies its parent`() {
        assertEquals(listOf("ui/Login.kt"), snapshotViewGeneratorPaths(null, "ui/Login.kt"))
    }

    private fun case(method: String, generator: String) = SnapshotCase(
        id = "$generator:$method",
        methodName = method,
        generatorPath = generator,
        className = "SnapshotTests",
        goldenPaths = listOf(method),
        status = SnapshotFileStatus.FAILED,
        failedCount = 1,
    )
}
