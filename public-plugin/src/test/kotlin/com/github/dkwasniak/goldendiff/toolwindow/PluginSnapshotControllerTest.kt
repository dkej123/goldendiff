package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.match.Screen
import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.project.SnapshotIndexFingerprint
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import com.github.dkwasniak.goldendiff.scan.GoldenSourceVariant
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonResult
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSource
import com.github.dkwasniak.goldendiff.settings.ScreenshotSettings
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PluginSnapshotControllerTest {

    @Test
    fun `stale refresh cannot replace a newer result`() {
        val controller = PluginSnapshotController {}
        val stale = controller.beginRefresh()
        val current = controller.beginRefresh()

        controller.completeRefresh(stale, index(view("stale.kt", "/stale.png")), emptyList(), null)
        assertNull(controller.state.index)

        controller.completeRefresh(current, index(view("current.kt", "/current.png")), emptyList(), null)
        assertEquals("current.kt", controller.state.selection.viewId)
    }

    @Test
    fun `editor tab selects its View and same file is a no-op`() {
        var publications = 0
        val controller = PluginSnapshotController { publications++ }
        val generation = controller.beginRefresh()
        controller.completeRefresh(
            generation,
            index(view("A.kt", "/a.png"), view("B.kt", "/b.png")),
            emptyList(),
            "A.kt",
        )
        controller.editorChanged("B.kt")
        val afterChange = publications

        controller.editorChanged("B.kt")

        assertEquals("B.kt", controller.state.selection.viewId)
        assertEquals(afterChange, publications)
    }

    @Test
    fun `stable View Case and golden ids survive equivalent refresh`() {
        val golden = File("/tmp/golden.png")
        val case = snapshotCase("A.kt", "testA", golden.path)
        val firstView = view("A.kt", golden.path, listOf(case))
        val item = MergedGoldenItem(
            golden,
            GoldenSourceVariant(golden, ExtraComparisonItemStatus.MODIFIED),
            null,
        )
        val controller = PluginSnapshotController {}
        val first = controller.beginRefresh()
        controller.completeRefresh(first, index(firstView), listOf(item), null)
        controller.selectCase("A.kt", case.id)
        controller.selectGolden(golden.path)

        val second = controller.beginRefresh()
        controller.completeRefresh(second, index(firstView.copy()), listOf(item.copy()), null)

        assertEquals("A.kt", controller.state.selection.viewId)
        assertEquals(case.id, controller.state.selection.caseId)
        assertEquals(golden.path, controller.state.selection.goldenId)
    }

    @Test
    fun `missing Case falls back deterministically to its View`() {
        val firstGolden = "/tmp/first.png"
        val secondGolden = "/tmp/second.png"
        val case = snapshotCase("A.kt", "testA", secondGolden)
        val controller = PluginSnapshotController {}
        val first = controller.beginRefresh()
        controller.completeRefresh(first, index(view("A.kt", firstGolden, listOf(case))), emptyList(), null)
        controller.selectCase("A.kt", case.id)

        val second = controller.beginRefresh()
        controller.completeRefresh(second, index(view("A.kt", firstGolden)), emptyList(), null)

        assertEquals("A.kt", controller.state.selection.viewId)
        assertNull(controller.state.selection.caseId)
        assertEquals(firstGolden, controller.state.selection.goldenId)
    }

    @Test
    fun `applyMergedItemUpdates patches only matching goldens without touching load state`() {
        val touched = File("/tmp/touched.png")
        val untouched = File("/tmp/untouched.png")
        val controller = PluginSnapshotController {}
        val refresh = controller.beginRefresh()
        controller.completeRefresh(
            refresh,
            index(view("A.kt", touched.path), view("B.kt", untouched.path)),
            listOf(
                MergedGoldenItem(touched, GoldenSourceVariant(touched, ExtraComparisonItemStatus.MODIFIED), null),
                MergedGoldenItem(untouched, GoldenSourceVariant(untouched, ExtraComparisonItemStatus.UNCHANGED), null),
            ),
            null,
        )
        val stateBefore = controller.state

        controller.applyMergedItemUpdates(
            controller.beginMergedItemUpdate(),
            listOf(MergedGoldenItem(touched, GoldenSourceVariant(touched, ExtraComparisonItemStatus.UNCHANGED), null)),
        )

        assertEquals(
            listOf(ExtraComparisonItemStatus.UNCHANGED, ExtraComparisonItemStatus.UNCHANGED),
            controller.state.mergedItems.map { it.workingCopy?.status },
        )
        assertEquals(stateBefore.loadState, controller.state.loadState)
        assertEquals(stateBefore.generation, controller.state.generation)
        assertEquals(SnapshotFileStatus.UNCHANGED, controller.state.index?.files?.first()?.status)
        assertEquals(SnapshotFileStatus.MODIFIED, controller.state.index?.files?.last()?.status)
    }

    @Test
    fun `applyMergedItemUpdates with no matching goldens is a no-op`() {
        val golden = File("/tmp/golden.png")
        val controller = readyController(golden)
        val stateBefore = controller.state

        controller.applyMergedItemUpdates(
            controller.beginMergedItemUpdate(),
            listOf(
                MergedGoldenItem(
                    File("/tmp/other.png"),
                    GoldenSourceVariant(File("/tmp/other.png"), ExtraComparisonItemStatus.MODIFIED),
                    null,
                ),
            ),
        )

        assertEquals(stateBefore, controller.state)
    }

    @Test
    fun `stale or superseded merged updates cannot patch current refresh`() {
        val golden = File("/tmp/golden.png")
        val controller = readyController(golden)
        val staleByNewerUpdate = controller.beginMergedItemUpdate()
        val latest = controller.beginMergedItemUpdate()
        val changed = listOf(
            MergedGoldenItem(golden, GoldenSourceVariant(golden, ExtraComparisonItemStatus.UNCHANGED), null),
        )

        controller.applyMergedItemUpdates(staleByNewerUpdate, changed)
        assertEquals(ExtraComparisonItemStatus.MODIFIED, controller.state.mergedItems.single().workingCopy?.status)

        val staleByRefresh = latest
        controller.beginRefresh()
        controller.applyMergedItemUpdates(staleByRefresh, changed)
        assertEquals(ExtraComparisonItemStatus.MODIFIED, controller.state.mergedItems.single().workingCopy?.status)
    }

    @Test
    fun `merged update started during refresh cannot patch completed replacement`() {
        val golden = File("/tmp/golden.png")
        val controller = readyController(golden)
        val refresh = controller.beginRefresh()
        val inFlightUpdate = controller.beginMergedItemUpdate()
        controller.completeRefresh(
            refresh,
            index(view("A.kt", golden.path)),
            listOf(MergedGoldenItem(golden, GoldenSourceVariant(golden, ExtraComparisonItemStatus.NEW), null)),
            null,
        )

        controller.applyMergedItemUpdates(
            inFlightUpdate,
            listOf(MergedGoldenItem(golden, GoldenSourceVariant(golden, ExtraComparisonItemStatus.UNCHANGED), null)),
        )

        assertEquals(ExtraComparisonItemStatus.NEW, controller.state.mergedItems.single().workingCopy?.status)
    }

    @Test
    fun `built in and extension items merge with stable source-qualified ids`() {
        val golden = File("/tmp/golden.png")
        val controller = readyController(golden)
        val generation = controller.beginExtraRefresh("A.kt")
        controller.completeExtraRefresh(
            generation,
            "A.kt",
            listOf(extension("/tmp/figma.png", "testA")),
        )

        assertEquals(
            listOf("built-in:${golden.path}", "extension:figma:/tmp/figma.png"),
            controller.state.gridItems.map { it.id },
        )
    }

    @Test
    fun `selecting extension preserves built in golden selection`() {
        val golden = File("/tmp/golden.png")
        val controller = readyController(golden)
        val generation = controller.beginExtraRefresh("A.kt")
        val extension = extension("/tmp/figma.png", "testA")
        controller.completeExtraRefresh(generation, "A.kt", listOf(extension))

        controller.selectGridItem(extension.id)

        assertEquals(extension.id, controller.state.selectedGridId)
        assertEquals(golden.path, controller.state.selection.goldenId)
        assertEquals(golden, controller.state.selectedGolden?.golden)
    }

    @Test
    fun `stale extension result cannot replace current editor result`() {
        val controller = readyController(File("/tmp/golden.png"))
        val stale = controller.beginExtraRefresh("A.kt")
        val current = controller.beginExtraRefresh("B.kt")

        controller.completeExtraRefresh(stale, "A.kt", listOf(extension("/tmp/stale.png", "testA")))
        controller.completeExtraRefresh(current, "B.kt", listOf(extension("/tmp/current.png", "testB")))

        assertEquals(listOf("/tmp/current.png"), controller.state.extraItems.map { it.file.path })
    }

    @Test
    fun `extension filter shows only extensions and resets when selected Case has none`() {
        val golden = File("/tmp/golden.png")
        val matching = snapshotCase("A.kt", "testA", golden.path)
        val other = snapshotCase("A.kt", "testB", golden.path)
        val item = MergedGoldenItem(
            golden,
            GoldenSourceVariant(golden, ExtraComparisonItemStatus.MODIFIED),
            null,
        )
        val controller = PluginSnapshotController {}
        val refresh = controller.beginRefresh()
        controller.completeRefresh(refresh, index(view("A.kt", golden.path, listOf(matching, other))), listOf(item), null)
        val extra = controller.beginExtraRefresh("A.kt")
        controller.completeExtraRefresh(extra, "A.kt", listOf(extension("/tmp/figma.png", "testA")))
        controller.setExtraOnly(true)

        assertEquals(listOf("extension:figma:/tmp/figma.png"), controller.state.visibleGridItems.map { it.id })

        controller.selectCase("A.kt", other.id)

        assertEquals(false, controller.state.extraOnly)
        assertEquals(listOf("built-in:${golden.path}"), controller.state.visibleGridItems.map { it.id })
    }

    private fun readyController(golden: File): PluginSnapshotController {
        val item = MergedGoldenItem(
            golden,
            GoldenSourceVariant(golden, ExtraComparisonItemStatus.MODIFIED),
            null,
        )
        return PluginSnapshotController {}.also { controller ->
            val refresh = controller.beginRefresh()
            controller.completeRefresh(refresh, index(view("A.kt", golden.path)), listOf(item), "A.kt")
        }
    }

    private fun extension(path: String, title: String): GoldenGridItem.Extension =
        GoldenGridItem.Extension(
            source = FakeExtraSource,
            item = ExtraComparisonItem(File(path), title, isLoading = true),
            screen = Screen(listOf(title), listOf("A"), "A", title),
        )

    private fun index(vararg views: SnapshotSourceFile): SnapshotFileIndex =
        SnapshotFileIndex(
            root = File("/tmp"),
            files = views.toList(),
            failedFilesByDirectory = emptyMap(),
            fingerprint = SnapshotIndexFingerprint(
                projectPath = "/tmp",
                goldenPaths = emptyList(),
                matchMode = MatchMode.ANNOTATED_METHOD,
                annotatedFunctionRegex = "",
                goldenFilePatterns = emptyList(),
                excludedSuffixes = emptyList(),
            ),
            goldenInventory = views.flatMapTo(linkedSetOf()) { it.goldenPaths },
        )

    private fun view(
        path: String,
        golden: String,
        cases: List<SnapshotCase> = emptyList(),
    ): SnapshotSourceFile =
        SnapshotSourceFile(
            relativePath = path,
            goldenPaths = (listOf(golden) + cases.flatMap { it.goldenPaths }).distinct(),
            status = SnapshotFileStatus.MODIFIED,
            failedCount = 0,
            cases = cases,
        )

    private fun snapshotCase(parent: String, method: String, golden: String): SnapshotCase =
        SnapshotCase(
            id = "$parent\u0000$parent\u0000$method",
            methodName = method,
            generatorPath = parent,
            className = "A",
            goldenPaths = listOf(golden),
            status = SnapshotFileStatus.MODIFIED,
            failedCount = 0,
        )

    private object FakeExtraSource : ExtraComparisonSource {
        override val id = "figma"
        override val title = "Figma"

        override fun findFiles(
            project: Project,
            screen: Screen,
            settings: ScreenshotSettings,
        ): List<File> = emptyList()

        override fun loadComparison(
            project: Project,
            file: File,
            screen: Screen,
            settings: ScreenshotSettings,
        ): ExtraComparisonResult = ExtraComparisonResult.Single(null, "unused")
    }
}
