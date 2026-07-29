package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotIndexFingerprint
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SnapshotFileProjectionTest {
    private fun file(path: String, status: SnapshotFileStatus) =
        SnapshotSourceFile(path, emptyList(), status, if (status == SnapshotFileStatus.FAILED) 1 else 0)

    @Test
    fun `flat projection combines status filters with search and sorts labeled statuses first`() {
        val files = listOf(
            file("x/Unchanged.kt", SnapshotFileStatus.UNCHANGED),
            file("c/New.kt", SnapshotFileStatus.NEW),
            file("z/Login.kt", SnapshotFileStatus.MODIFIED),
            file("a/Profile.kt", SnapshotFileStatus.FAILED),
            file("b/Login.kt", SnapshotFileStatus.FAILED),
        )

        val (all, _) = projectSnapshotFiles(files, "", emptySet())
        val (filtered, _) = projectSnapshotFiles(files, "b/", setOf(SnapshotFileStatus.FAILED))

        assertEquals(
            listOf("a/Profile.kt", "b/Login.kt", "z/Login.kt", "c/New.kt", "x/Unchanged.kt"),
            all.map { it.relativePath },
        )
        assertEquals(listOf("b/Login.kt"), filtered.map { it.relativePath })
    }

    @Test
    fun `multiple status filters use or logic and exclude unchanged files`() {
        val files = SnapshotFileStatus.entries.map { status -> file("${status.name}.kt", status) }

        val (filtered, _) = projectSnapshotFiles(
            files,
            "",
            setOf(SnapshotFileStatus.FAILED, SnapshotFileStatus.MODIFIED, SnapshotFileStatus.NEW),
        )

        assertEquals(
            listOf("FAILED.kt", "MODIFIED.kt", "NEW.kt"),
            filtered.map { it.relativePath },
        )
    }

    @Test
    fun `group header survives while any file matches and count ignores search`() {
        val files = listOf(
            file("ui/Login.kt", SnapshotFileStatus.FAILED),
            file("ui/Profile.kt", SnapshotFileStatus.FAILED),
            file("data/Store.kt", SnapshotFileStatus.MODIFIED),
        )

        val (_, groups) = projectSnapshotFiles(files, "Login", setOf(SnapshotFileStatus.FAILED))

        assertEquals(listOf("ui"), groups.map { it.folder })
        assertEquals(listOf("ui/Login.kt"), groups.single().files.map { it.relativePath })
        assertEquals(2, groups.single().failedFileCount)
    }

    @Test
    fun `arrow navigation follows visible files and expanded cases and stops at boundaries`() {
        val first = file("a/First.kt", SnapshotFileStatus.FAILED)
        val second = file("b/Second.kt", SnapshotFileStatus.MODIFIED)
        val third = file("c/Third.kt", SnapshotFileStatus.NEW)
        val firstCase = SnapshotCase(
            "first-case",
            "FirstPreview",
            first.relativePath,
            "First",
            listOf("a"),
            SnapshotFileStatus.FAILED,
            1,
        )
        val secondCase = SnapshotCase(
            "second-case",
            "SecondPreview",
            second.relativePath,
            "Second",
            listOf("b"),
            SnapshotFileStatus.MODIFIED,
            0,
        )
        val rows = listOf(
            SnapshotTreeRow.Folder("a", "a", 1, listOf("a"), true, 0),
            SnapshotTreeRow.File(first, expanded = true, depth = 1),
            SnapshotTreeRow.Case(first, firstCase, depth = 2),
            SnapshotTreeRow.File(second, expanded = true, depth = 1),
            SnapshotTreeRow.Case(second, secondCase, depth = 2),
            SnapshotTreeRow.File(third, expanded = false, depth = 1),
        )

        assertEquals(rows[1], nextSnapshotRow(rows, selectedPath = null, selectedCaseId = null, delta = 1))
        assertEquals(
            rows[2],
            nextSnapshotRow(rows, selectedPath = first.relativePath, selectedCaseId = null, delta = 1),
        )
        assertEquals(
            rows[3],
            nextSnapshotRow(rows, selectedPath = first.relativePath, selectedCaseId = firstCase.id, delta = 1),
        )
        assertEquals(
            rows[3],
            nextSnapshotRow(rows, selectedPath = second.relativePath, selectedCaseId = secondCase.id, delta = -1),
        )
        assertEquals(
            rows[1],
            nextSnapshotRow(rows, selectedPath = first.relativePath, selectedCaseId = null, delta = -1),
        )
        assertEquals(
            rows[5],
            nextSnapshotRow(rows, selectedPath = third.relativePath, selectedCaseId = null, delta = 1),
        )
        assertEquals(rows[5], nextSnapshotRow(rows, selectedPath = null, selectedCaseId = null, delta = -1))
    }

    @Test
    fun `arrow navigation resumes from parent when selected case is collapsed`() {
        val parent = file("ui/Button.kt", SnapshotFileStatus.FAILED)
        val next = file("ui/Card.kt", SnapshotFileStatus.MODIFIED)
        val rows = listOf(
            SnapshotTreeRow.File(parent, expanded = false, depth = 0),
            SnapshotTreeRow.File(next, expanded = false, depth = 0),
        )

        assertEquals(
            rows[1],
            nextSnapshotRow(rows, selectedPath = parent.relativePath, selectedCaseId = "hidden-case", delta = 1),
        )
    }

    @Test
    fun `grouped projection hides source roots and shared package prefix while preserving modules`() {
        val files = listOf(
            file(
                "feature/routeplanner/src/main/kotlin/com/example/android/feature/planroute/PlanRouteScreen.kt",
                SnapshotFileStatus.FAILED,
            ),
            file(
                "feature/routeplanner/src/main/kotlin/com/example/android/feature/planroute/summary/RouteSummaryScreen.kt",
                SnapshotFileStatus.MODIFIED,
            ),
            file(
                "designsystem/src/main/kotlin/com/example/android/designsystem/modal/SampleBottomSheet.kt",
                SnapshotFileStatus.MODIFIED,
            ),
        )

        val rows = projectSnapshotTree(files, query = "", statusFilters = emptySet())
        val folders = rows.filterIsInstance<SnapshotTreeRow.Folder>()
        val planRoute = folders.single { it.path == "feature/routeplanner/planroute" }

        assertEquals(
            listOf("designsystem", "modal", "feature", "routeplanner", "planroute", "summary"),
            folders.map { it.name },
        )
        assertEquals(1, folders.count { it.path == "feature/routeplanner" })
        assertEquals(1, planRoute.failedFileCount)
        assertEquals(2, planRoute.depth)
        assertEquals(
            listOf("SampleBottomSheet.kt", "PlanRouteScreen.kt", "RouteSummaryScreen.kt"),
            rows.filterIsInstance<SnapshotTreeRow.File>().map { File(it.entry.relativePath).name },
        )
    }

    @Test
    fun `collapsed folder hides descendants without affecting siblings`() {
        val files = listOf(
            file("one/src/main/kotlin/com/example/one/a/First.kt", SnapshotFileStatus.MODIFIED),
            file("two/src/main/kotlin/com/example/two/b/Second.kt", SnapshotFileStatus.MODIFIED),
        )

        val rows = projectSnapshotTree(files, query = "", statusFilters = emptySet(), collapsedFolders = setOf("one"))

        assertEquals(
            listOf("Second.kt"),
            rows.filterIsInstance<SnapshotTreeRow.File>().map { File(it.entry.relativePath).name },
        )
        assertEquals(false, rows.filterIsInstance<SnapshotTreeRow.Folder>().first { it.path == "one" }.expanded)
    }

    @Test
    fun `view cases are collapsed by default and expand below their parent`() {
        val view = file("ui/Button.kt", SnapshotFileStatus.FAILED).copy(
            cases = listOf(
                SnapshotCase("button-primary", "PrimaryPreview", "ui/Button.kt", "Button", listOf("a"), SnapshotFileStatus.FAILED, 1),
                SnapshotCase("button-disabled", "DisabledPreview", "ui/Button.kt", "Button", listOf("b"), SnapshotFileStatus.UNCHANGED, 0),
            ),
        )

        val collapsed = projectSnapshotTree(listOf(view), "", statusFilters = emptySet())
        val expanded = projectSnapshotTree(
            listOf(view),
            "",
            statusFilters = emptySet(),
            expandedViews = setOf(view.relativePath),
        )

        assertEquals(0, collapsed.filterIsInstance<SnapshotTreeRow.Case>().size)
        assertEquals(
            listOf("DisabledPreview", "PrimaryPreview"),
            expanded.filterIsInstance<SnapshotTreeRow.Case>().map { it.entry.methodName },
        )
    }

    @Test
    fun `case search reveals ancestors and failed-only keeps only failed cases`() {
        val view = file("ui/Button.kt", SnapshotFileStatus.FAILED).copy(
            cases = listOf(
                SnapshotCase("primary", "PrimaryPreview", "tests/ButtonTests.kt", "ButtonTests", listOf("a"), SnapshotFileStatus.FAILED, 1),
                SnapshotCase("disabled", "DisabledPreview", "tests/ButtonTests.kt", "ButtonTests", listOf("b"), SnapshotFileStatus.UNCHANGED, 0),
            ),
        )

        val searched = projectSnapshotTree(listOf(view), "Primary", statusFilters = emptySet())
        val failed = projectSnapshotTree(
            listOf(view),
            "",
            statusFilters = setOf(SnapshotFileStatus.FAILED),
            expandedViews = setOf(view.relativePath),
        )

        assertEquals(listOf("ui/Button.kt"), searched.filterIsInstance<SnapshotTreeRow.File>().map { it.entry.relativePath })
        assertEquals(listOf("PrimaryPreview"), searched.filterIsInstance<SnapshotTreeRow.Case>().map { it.entry.methodName })
        assertEquals(listOf("PrimaryPreview"), failed.filterIsInstance<SnapshotTreeRow.Case>().map { it.entry.methodName })
    }

    @Test
    fun `quick open searches snapshot methods and retains their parent file`() {
        val view = file("ui/Button.kt", SnapshotFileStatus.UNCHANGED).copy(
            goldenPaths = listOf(File("goldens/Primary.png").normalize().path),
            cases = listOf(
                SnapshotCase(
                    "primary",
                    "PrimaryButtonPreview",
                    "tests/ButtonTests.kt",
                    "ButtonTests",
                    listOf(File("goldens/Primary.png").normalize().path),
                    SnapshotFileStatus.UNCHANGED,
                    0,
                ),
            ),
        )
        val index = snapshotIndex(view)

        assertEquals(
            QuickOpenResult("ui/Button.kt", "PrimaryButtonPreview", "primary"),
            snapshotQuickOpenResults(index, "PrimaryPreview").single(),
        )
    }

    @Test
    fun `golden target resolves the most precise case under the preferred parent`() {
        val golden = File("goldens/Primary.png").normalize().path
        val view = file("ui/Button.kt", SnapshotFileStatus.FAILED).copy(
            goldenPaths = listOf(golden),
            cases = listOf(
                SnapshotCase(
                    "primary",
                    "PrimaryButtonPreview",
                    "tests/ButtonTests.kt",
                    "ButtonTests",
                    listOf(golden),
                    SnapshotFileStatus.FAILED,
                    1,
                ),
            ),
        )

        assertEquals(
            GoldenTreeLocation("ui/Button.kt", "primary"),
            goldenTreeLocation(snapshotIndex(view), "ui/Button.kt", golden),
        )
    }

    private fun snapshotIndex(vararg files: SnapshotSourceFile): SnapshotFileIndex =
        SnapshotFileIndex(
            root = File("."),
            files = files.toList(),
            failedFilesByDirectory = emptyMap(),
            fingerprint = SnapshotIndexFingerprint.from(File("."), GoldenDiffConfig()),
            goldenInventory = files.flatMapTo(linkedSetOf()) { it.goldenPaths },
        )
}
