package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import com.github.dkwasniak.goldendiff.project.projectSnapshotTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesListPresentationTest {

    private val location = view("ui/location/LocationScreen.kt", SnapshotFileStatus.FAILED, cases = listOf("dark", "light"))
    private val picker = view("ui/location/PickerScreen.kt", SnapshotFileStatus.UNCHANGED)
    private val welcome = view("ui/onboarding/WelcomeScreen.kt", SnapshotFileStatus.NEW)
    private val files = listOf(location, picker, welcome)

    @Test
    fun `flattens nested folders into headers with direct View counts`() {
        val presentation = present()

        val headers = presentation.rows.filterIsInstance<FilesRow.Folder>()
        assertEquals(listOf("ui/location", "ui/onboarding"), headers.map { it.path })
        assertEquals(listOf(1, 0), headers.map { it.failedCount })
        assertEquals(listOf(2, 1), headers.map { it.viewCount })
        assertEquals(
            listOf("folder:ui/location", "view:${location.relativePath}", "view:${picker.relativePath}", "folder:ui/onboarding", "view:${welcome.relativePath}"),
            presentation.rows.map { it.id },
        )
    }

    @Test
    fun `collapsed folder hides its Views but keeps them in navigation order`() {
        val presentation = present(collapsedFolders = setOf("ui/location"))

        assertTrue(presentation.rows.filterIsInstance<FilesRow.Folder>().first().collapsed)
        assertEquals(listOf(welcome.relativePath), presentation.rows.filterIsInstance<FilesRow.View>().map { it.viewId })
        assertEquals(listOf(location.relativePath, picker.relativePath, welcome.relativePath), presentation.viewOrder)
    }

    @Test
    fun `active query expands collapsed folders and Views`() {
        val presentation = present(query = "Location", collapsedFolders = setOf("ui/location"))

        assertFalse(presentation.rows.filterIsInstance<FilesRow.Folder>().single().collapsed)
        assertEquals(listOf("dark", "light"), presentation.rows.filterIsInstance<FilesRow.Case>().map { it.entry.methodName })
    }

    @Test
    fun `Cases are shown only for expanded Views or the View owning the selected Case`() {
        assertTrue(present().rows.none { it is FilesRow.Case })
        assertEquals(2, present(expandedViews = setOf(location.relativePath)).rows.count { it is FilesRow.Case })

        val selectedCase = location.cases.last()
        val rows = present(selection = SnapshotSelection(location.relativePath, selectedCase.id)).rows
        val caseRows = rows.filterIsInstance<FilesRow.Case>()
        assertEquals(2, caseRows.size)
        assertEquals(listOf(false, true), caseRows.map { it.selected })
        assertTrue(rows.filterIsInstance<FilesRow.View>().first().expanded)
    }

    @Test
    fun `selection marks only the selected View when no Case is selected`() {
        val views = present(selection = SnapshotSelection(picker.relativePath)).rows.filterIsInstance<FilesRow.View>()

        assertEquals(listOf(false, true, false), views.map { it.selected })
        assertEquals(listOf(true, false, false), views.map { it.hasCases })
    }

    @Test
    fun `View status uses golden display statuses and falls back to the index`() {
        val accepted = mapOf("${welcome.relativePath}.png" to GoldenDisplayStatus.ACCEPTED)
        val views = present(statusOfGolden = accepted::get).rows.filterIsInstance<FilesRow.View>()

        assertEquals(
            listOf(GoldenDisplayStatus.FAILED, GoldenDisplayStatus.UNCHANGED, GoldenDisplayStatus.ACCEPTED),
            views.map { it.status },
        )
    }

    @Test
    fun `count label reports visible of total Views`() {
        val filtered = present(filters = setOf(SnapshotFileStatus.FAILED))

        assertEquals("1 of 3 files", filtered.countLabel)
        assertEquals(listOf(location.relativePath), filtered.viewOrder)
        assertEquals(listOf("ui/location", "ui/onboarding"), present().folderPaths)
    }

    private fun present(
        query: String = "",
        filters: Set<SnapshotFileStatus> = emptySet(),
        collapsedFolders: Set<String> = emptySet(),
        expandedViews: Set<String> = emptySet(),
        selection: SnapshotSelection = SnapshotSelection(),
        statusOfGolden: (String) -> GoldenDisplayStatus? = { null },
    ): FilesListPresentation =
        filesListPresentation(
            projection = projectSnapshotTree(files, query, filters),
            totalViewCount = files.size,
            queryActive = query.isNotBlank(),
            collapsedFolders = collapsedFolders,
            expandedViews = expandedViews,
            selection = selection,
            statusOfGolden = statusOfGolden,
        )

    private fun view(path: String, status: SnapshotFileStatus, cases: List<String> = emptyList()): SnapshotSourceFile {
        val caseEntries = cases.map { method ->
            SnapshotCase(
                id = "$path\u0000$method",
                methodName = method,
                generatorPath = path,
                className = null,
                goldenPaths = listOf("$path.$method.png"),
                status = status,
                failedCount = if (status == SnapshotFileStatus.FAILED) 1 else 0,
            )
        }
        return SnapshotSourceFile(
            relativePath = path,
            goldenPaths = listOf("$path.png") + caseEntries.flatMap { it.goldenPaths },
            status = status,
            failedCount = if (status == SnapshotFileStatus.FAILED) 1 else 0,
            cases = caseEntries,
        )
    }
}
