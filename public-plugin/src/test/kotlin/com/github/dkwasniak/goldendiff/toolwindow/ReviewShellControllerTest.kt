package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.project.SnapshotIndexFingerprint
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import com.github.dkwasniak.goldendiff.scan.GoldenSourceVariant
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Files / Review shell transitions and the session-scoped Accepted overlay. */
class ReviewShellControllerTest {

    private val goldenA = File("/tmp/a.png")
    private val goldenB = File("/tmp/b.png")
    private val goldenC = File("/tmp/c.png")
    private val caseB = SnapshotCase(
        id = "ui/B.kt\u0000testB",
        methodName = "testB",
        generatorPath = "ui/B.kt",
        className = null,
        goldenPaths = listOf(goldenB.path),
        status = SnapshotFileStatus.FAILED,
        failedCount = 1,
    )

    @Test
    fun `shell starts on Review with the overlay closed`() {
        val state = ready().state

        assertEquals(ReviewTab.REVIEW, state.tab)
        assertFalse(state.filesOverlayOpen)
    }

    @Test
    fun `switching tabs closes the files overlay`() {
        val controller = ready()
        controller.toggleFilesOverlay()
        assertTrue(controller.state.filesOverlayOpen)

        controller.showTab(ReviewTab.FILES)

        assertEquals(ReviewTab.FILES, controller.state.tab)
        assertFalse(controller.state.filesOverlayOpen)
    }

    @Test
    fun `overlay only opens on the Review tab`() {
        val controller = ready()
        controller.showTab(ReviewTab.FILES)

        controller.toggleFilesOverlay()

        assertFalse(controller.state.filesOverlayOpen)
    }

    @Test
    fun `opening a View from the Files tab jumps to Review`() {
        val controller = ready()
        controller.showTab(ReviewTab.FILES)

        controller.openView("ui/C.kt")

        assertEquals(ReviewTab.REVIEW, controller.state.tab)
        assertEquals("ui/C.kt", controller.state.selection.viewId)
        assertEquals(goldenC.path, controller.state.selection.goldenId)
    }

    @Test
    fun `opening a Case from the overlay closes it and narrows the goldens`() {
        val controller = ready()
        controller.toggleFilesOverlay()

        controller.openCase("ui/B.kt", caseB.id)

        assertFalse(controller.state.filesOverlayOpen)
        assertEquals(caseB.id, controller.state.selection.caseId)
        assertEquals(listOf(goldenB.path), controller.state.selectedItems.map { it.golden.path })
    }

    @Test
    fun `editor change selects its View without leaving the Files tab`() {
        val controller = ready()
        controller.showTab(ReviewTab.FILES)

        controller.editorChanged("ui/C.kt")

        assertEquals(ReviewTab.FILES, controller.state.tab)
        assertEquals("ui/C.kt", controller.state.selection.viewId)
    }

    @Test
    fun `review queue holds only changed Views in Files order`() {
        assertEquals(listOf("ui/B.kt", "ui/C.kt"), ready().state.reviewQueue)
    }

    @Test
    fun `stepping walks the queue one View at a time, wraps and clears the Case`() {
        val controller = ready()
        controller.openCase("ui/B.kt", caseB.id)

        controller.stepView(1)
        assertEquals("ui/C.kt", controller.state.selection.viewId)
        assertNull(controller.state.selection.caseId)
        assertEquals("2 / 2", controller.state.reviewPosition)

        controller.stepView(1)
        assertEquals("ui/B.kt", controller.state.selection.viewId)
    }

    @Test
    fun `an unchanged View opened by hand is outside the queue and steps back into it`() {
        val controller = ready()
        controller.openView("ui/A.kt")

        assertNull(controller.state.reviewPosition)
        assertEquals(GoldenDisplayStatus.UNCHANGED, controller.state.selectedViewStatus)

        controller.stepView(1)
        assertEquals("ui/B.kt", controller.state.selection.viewId)
    }

    @Test
    fun `the queue ignores Files search and filters`() {
        val controller = ready()
        controller.setFileQuery("C.kt")
        controller.setStatusFilter(SnapshotFileStatus.NEW, enabled = true)
        controller.openView("ui/B.kt")

        assertEquals("1 / 2", controller.state.reviewPosition)
        controller.stepView(1)
        assertEquals("ui/C.kt", controller.state.selection.viewId)
    }

    @Test
    fun `without changed Views there is no queue to navigate`() {
        val controller = ready()

        refresh(controller, working(goldenB, ExtraComparisonItemStatus.UNCHANGED), includeC = false)

        assertTrue(controller.state.reviewQueue.isEmpty())
        assertNull(controller.state.reviewPosition)
    }

    @Test
    fun `an accepted View keeps its queue position and shows as Accepted`() {
        val controller = ready()
        controller.openView("ui/B.kt")
        controller.acceptSucceeded(listOf(goldenB.path))
        controller.applyMergedItemUpdates(
            controller.beginMergedItemUpdate(),
            listOf(working(goldenB, ExtraComparisonItemStatus.MODIFIED)),
        )

        assertEquals(listOf("ui/B.kt", "ui/C.kt"), controller.state.reviewQueue)
        assertEquals("1 / 2", controller.state.reviewPosition)
        assertEquals(GoldenDisplayStatus.ACCEPTED, controller.state.selectedViewStatus)
    }

    @Test
    fun `filters hiding the selected View keep the durable selection`() {
        val controller = ready()
        controller.openView("ui/A.kt")

        controller.setFileQuery("C.kt")

        assertEquals("ui/A.kt", controller.state.selection.viewId)
    }

    @Test
    fun `clearing search and filters restores the full navigation order`() {
        val controller = ready()
        controller.setFileQuery("C.kt")
        controller.setStatusFilter(SnapshotFileStatus.NEW, enabled = true)

        controller.clearFileFilters()

        assertEquals("", controller.state.fileQuery)
        assertTrue(controller.state.statusFilters.isEmpty())
        assertEquals(3, controller.state.filesList().viewOrder.size)
    }

    @Test
    fun `collapse all and expand all cover every visible folder`() {
        val controller = ready()

        controller.collapseAllFolders()
        assertTrue(controller.state.filesList().rows.filterIsInstance<FilesRow.Folder>().all { it.collapsed })
        assertTrue(controller.state.filesList().rows.none { it is FilesRow.View })

        controller.expandAllFolders()
        assertTrue(controller.state.collapsedFolders.isEmpty())
    }

    @Test
    fun `folder and View expansion toggles are independent`() {
        val controller = ready()

        controller.setFolderCollapsed("ui", collapsed = true)
        controller.setViewExpanded("ui/B.kt", expanded = true)

        assertEquals(setOf("ui"), controller.state.collapsedFolders)
        assertEquals(setOf("ui/B.kt"), controller.state.expandedViews)
    }

    @Test
    fun `failed View count drives the Files tab badge`() {
        assertEquals(1, ready().state.failedViewCount)
    }

    @Test
    fun `accepted golden shows Accepted until it fails again`() {
        val controller = ready()
        controller.acceptSucceeded(listOf(goldenB.path))
        val update = controller.beginMergedItemUpdate()
        controller.applyMergedItemUpdates(update, listOf(working(goldenB, ExtraComparisonItemStatus.MODIFIED)))

        assertEquals(GoldenDisplayStatus.ACCEPTED, controller.state.goldenDisplayStatus(goldenB.path))
        assertEquals(GoldenDisplayStatus.ACCEPTED, controller.state.filesList().viewStatus("ui/B.kt"))

        val regenerated = controller.beginMergedItemUpdate()
        controller.applyMergedItemUpdates(regenerated, listOf(failed(goldenB)))

        assertEquals(GoldenDisplayStatus.FAILED, controller.state.goldenDisplayStatus(goldenB.path))
        assertTrue(controller.state.acceptedGoldens.isEmpty())
    }

    @Test
    fun `accepted overlay survives a full refresh while the golden is still changed`() {
        val controller = ready()
        controller.acceptSucceeded(listOf(goldenB.path))

        refresh(controller, working(goldenB, ExtraComparisonItemStatus.MODIFIED))

        assertEquals(GoldenDisplayStatus.ACCEPTED, controller.state.goldenDisplayStatus(goldenB.path))
    }

    @Test
    fun `full refresh drops the overlay once the golden is unchanged or gone`() {
        val controller = ready()
        controller.acceptSucceeded(listOf(goldenB.path, goldenC.path))

        refresh(controller, working(goldenB, ExtraComparisonItemStatus.UNCHANGED), includeC = false)

        assertTrue(controller.state.acceptedGoldens.isEmpty())
        assertEquals(GoldenDisplayStatus.UNCHANGED, controller.state.goldenDisplayStatus(goldenB.path))
    }

    @Test
    fun `external golden rewrite drops the overlay`() {
        val controller = ready()
        controller.acceptSucceeded(listOf(goldenB.path))

        controller.goldensChangedExternally(listOf(goldenB.path))

        assertTrue(controller.state.acceptedGoldens.isEmpty())
    }

    private fun ready(): PluginSnapshotController {
        val controller = PluginSnapshotController {}
        controller.completeRefresh(controller.beginRefresh(), index(), items(failed(goldenB)), null)
        return controller
    }

    private fun refresh(controller: PluginSnapshotController, b: MergedGoldenItem, includeC: Boolean = true) {
        val items = listOfNotNull(
            working(goldenA, ExtraComparisonItemStatus.UNCHANGED),
            b,
            working(goldenC, ExtraComparisonItemStatus.NEW).takeIf { includeC },
        )
        val views = index().files.filter { includeC || it.relativePath != "ui/C.kt" }
        controller.completeRefresh(controller.beginRefresh(), index(*views.toTypedArray()), items, null)
    }

    private fun items(b: MergedGoldenItem) = listOf(
        working(goldenA, ExtraComparisonItemStatus.UNCHANGED),
        b,
        working(goldenC, ExtraComparisonItemStatus.NEW),
    )

    private fun index(vararg views: SnapshotSourceFile): SnapshotFileIndex {
        val files = views.toList().ifEmpty {
            listOf(
                view("ui/A.kt", goldenA, SnapshotFileStatus.UNCHANGED),
                view("ui/B.kt", goldenB, SnapshotFileStatus.FAILED, listOf(caseB)),
                view("ui/C.kt", goldenC, SnapshotFileStatus.NEW),
            )
        }
        return SnapshotFileIndex(
            root = File("/tmp"),
            files = files,
            failedFilesByDirectory = emptyMap(),
            fingerprint = SnapshotIndexFingerprint(
                projectPath = "/tmp",
                goldenPaths = emptyList(),
                matchMode = MatchMode.ANNOTATED_METHOD,
                annotatedFunctionRegex = "",
                goldenFilePatterns = emptyList(),
                excludedSuffixes = emptyList(),
            ),
            goldenInventory = files.flatMapTo(linkedSetOf()) { it.goldenPaths },
        )
    }

    private fun view(
        path: String,
        golden: File,
        status: SnapshotFileStatus,
        cases: List<SnapshotCase> = emptyList(),
    ) = SnapshotSourceFile(
        relativePath = path,
        goldenPaths = listOf(golden.path),
        status = status,
        failedCount = if (status == SnapshotFileStatus.FAILED) 1 else 0,
        cases = cases,
    )

    private fun failed(golden: File) = MergedGoldenItem(
        golden,
        GoldenSourceVariant(golden, ExtraComparisonItemStatus.UNCHANGED),
        GoldenSourceVariant(File("/tmp/out/${golden.name}"), ExtraComparisonItemStatus.MODIFIED),
    )

    private fun working(golden: File, status: ExtraComparisonItemStatus) =
        MergedGoldenItem(golden, GoldenSourceVariant(golden, status), null)
}
