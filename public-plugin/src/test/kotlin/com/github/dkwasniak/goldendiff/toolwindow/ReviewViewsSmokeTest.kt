package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.compare.CompareView
import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import com.github.dkwasniak.goldendiff.project.projectSnapshotTree
import com.github.dkwasniak.goldendiff.scan.GoldenSourceVariant
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

/** Renders and paints the Review views off-screen so renderer/paint regressions fail without an IDE. */
class ReviewViewsSmokeTest {

    @Test
    fun `files list renders rows, paints a sticky header and reports picks`() {
        val actions = RecordingActions()
        val panel = FilesListPanel(actions)
        val files = (1..30).map { view("ui/f${it % 3}/Screen$it.kt", if (it % 4 == 0) SnapshotFileStatus.FAILED else SnapshotFileStatus.NEW) }
        val presentation = filesListPresentation(
            projection = projectSnapshotTree(files),
            totalViewCount = files.size,
            queryActive = false,
            collapsedFolders = emptySet(),
            expandedViews = setOf(files.first().relativePath),
            selection = SnapshotSelection(files.first().relativePath),
            statusOfGolden = { null },
        )

        panel.render(FilesListRenderState(presentation, "", emptySet(), indexing = false, hasIndex = true, hiddenActiveViewName = "Hidden"))
        paint(panel, 320, 400)

        val list = findAll(panel).filterIsInstance<JList<*>>().single()
        assertEquals(presentation.rows.size, list.model.size)
        list.ensureIndexIsVisible(list.model.size - 1)
        paint(panel, 320, 400)

        val viewIndex = presentation.rows.indexOfFirst { it is FilesRow.View }
        list.selectedIndex = viewIndex
        list.keyListeners.forEach {
            it.keyPressed(java.awt.event.KeyEvent(list, java.awt.event.KeyEvent.KEY_PRESSED, 0, 0, java.awt.event.KeyEvent.VK_ENTER, '\n'))
        }
        assertEquals(SnapshotFilesSelection.View((presentation.rows[viewIndex] as FilesRow.View).viewId), actions.picked.single())
    }

    @Test
    fun `files list shows an empty state for a query without matches`() {
        val panel = FilesListPanel(RecordingActions())
        val presentation = filesListPresentation(projectSnapshotTree(emptyList(), "zzz"), 3, true, emptySet(), emptySet(), SnapshotSelection()) { null }

        panel.render(FilesListRenderState(presentation, "zzz", emptySet(), indexing = false, hasIndex = true, hiddenActiveViewName = null))
        paint(panel, 300, 300)
    }

    @Test
    fun `golden strip paints built-in items with every status`() {
        val selected = mutableListOf<String>()
        val strip = GoldenStripPanel(
            trimTransparentPadding = { false },
            statusOf = { GoldenDisplayStatus.entries[(it.file.name.length) % GoldenDisplayStatus.entries.size] },
            onSelect = { id, _ -> selected += id },
            onContextMenu = { _, _, _, _ -> },
        )
        val items = (1..5).map { index ->
            val golden = File("/tmp/missing/golden_$index.png")
            GoldenGridItem.BuiltIn(MergedGoldenItem(golden, GoldenSourceVariant(golden, ExtraComparisonItemStatus.MODIFIED), null))
        }

        strip.render(items, items[2].id)
        paint(strip, 600, strip.preferredSize.height)

        assertTrue("programmatic selection must not echo an intent", selected.isEmpty())
    }

    @Test
    fun `golden strip sizes each thumbnail by its own aspect and reports clicks`() {
        val dir = kotlin.io.path.createTempDirectory("strip").toFile()
        val wide = File(dir, "wide.png").also { javax.imageio.ImageIO.write(BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB), "png", it) }
        val tall = File(dir, "tall.png").also { javax.imageio.ImageIO.write(BufferedImage(50, 100, BufferedImage.TYPE_INT_ARGB), "png", it) }
        val selected = mutableListOf<Pair<String, String>>()
        val strip = GoldenStripPanel({ false }, { GoldenDisplayStatus.FAILED }, { id, trigger -> selected += id to trigger }, { _, _, _, _ -> })
        val items = listOf(wide, tall).map {
            GoldenGridItem.BuiltIn(MergedGoldenItem(it, GoldenSourceVariant(it, ExtraComparisonItemStatus.MODIFIED), null))
        }

        strip.render(items, items[0].id)
        val canvas = findAll(strip).filterIsInstance<JComponent>().first { it.getAccessibleContext()?.accessibleName == "Goldens" }
        val expectedWidth = 10 + 208 + 8 + 52 + 10
        assertEquals(expectedWidth, canvas.preferredSize.width)
        paint(strip, 400, strip.preferredSize.height)

        canvas.mouseListeners.forEach {
            it.mousePressed(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, 0, 10 + 208 + 8 + 20, 30, 1, false, MouseEvent.BUTTON1))
        }
        assertEquals(listOf(items[1].id to "grid"), selected)
        dir.deleteRecursively()
    }

    @Test
    fun `compare view paints every mode and accept state`() {
        val view = CompareView()
        val old = BufferedImage(40, 80, BufferedImage.TYPE_INT_ARGB)
        val new = BufferedImage(40, 80, BufferedImage.TYPE_INT_ARGB).apply { setRGB(3, 3, 0xFFFF0000.toInt()) }

        view.showComparison(old, new, "status", "HEAD", "Test output")
        AcceptButtonState.entries.forEach { state ->
            view.setAcceptState(state)
            paint(view, 360, 500)
        }
        findAll(view).filterIsInstance<javax.swing.JToggleButton>().forEach { mode ->
            mode.doClick()
            paint(view, 360, 500)
        }
        view.showBlocked("Ambiguous Test output", "Two files map here.", "Open Settings") {}
        paint(view, 360, 500)
        view.showSingle(new, "New — no Git HEAD version")
        paint(view, 360, 500)
    }

    @Test
    fun `overlay layer anchors its host below the anchor`() {
        val anchor = JPanel().apply { setSize(300, 34) }
        val content = JPanel(null).apply { add(anchor) }
        var dismissed = 0
        val layer = FilesOverlayLayer(content, anchor) { dismissed++ }
        layer.setSize(300, 500)

        layer.setOverlay(true)
        layer.doLayout()
        paint(layer, 300, 500)

        assertTrue(layer.host.isVisible)
        assertEquals(500 - 8, layer.host.y + layer.host.height)
    }

    private fun paint(component: JComponent, width: Int, height: Int) {
        component.setSize(width, height)
        layoutTree(component)
        val image = BufferedImage(width, height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
    }

    private fun layoutTree(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutTree)
        }
    }

    private fun findAll(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::findAll) ?: emptyList())

    private fun view(path: String, status: SnapshotFileStatus): SnapshotSourceFile {
        val case = SnapshotCase("$path\u0000test", "test", path, null, listOf("$path.test.png"), status, 0)
        return SnapshotSourceFile(path, listOf("$path.png", "$path.test.png"), status, 0, listOf(case))
    }

    private class RecordingActions : FilesListPanel.Actions {
        val picked = mutableListOf<SnapshotFilesSelection>()
        override fun queryChanged(query: String) = Unit
        override fun filterChanged(status: SnapshotFileStatus, enabled: Boolean) = Unit
        override fun folderToggled(path: String, collapsed: Boolean) = Unit
        override fun viewExpansionToggled(viewId: String, expanded: Boolean) = Unit
        override fun picked(selection: SnapshotFilesSelection) {
            picked += selection
        }
        override fun contextMenu(selection: SnapshotFilesSelection, component: Component, x: Int, y: Int) = Unit
        override fun expandAll() = Unit
        override fun collapseAll() = Unit
        override fun clearSearchAndFilters() = Unit
        override fun revealActive() = Unit
    }
}
