package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.CellRendererPane
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Everything the Files list renders; built by the shell from [PluginSnapshotState]. */
internal data class FilesListRenderState(
    val presentation: FilesListPresentation,
    val query: String,
    val filters: Set<SnapshotFileStatus>,
    val indexing: Boolean,
    val hasIndex: Boolean,
    /** Name of the active editor's View when search/filters hide it; null when visible or absent. */
    val hiddenActiveViewName: String?,
)

/**
 * Files list shared by the Files tab and the Review overlay: search, status chips, meta row and a
 * flat Folder / View / Case list with sticky folder headers. It renders [FilesListRenderState] and
 * reports intents; no durable state lives here besides the keyboard cursor.
 */
internal class FilesListPanel(private val actions: Actions) : JPanel(BorderLayout()) {

    interface Actions {
        fun queryChanged(query: String)
        fun filterChanged(status: SnapshotFileStatus, enabled: Boolean)
        fun folderToggled(path: String, collapsed: Boolean)
        fun viewExpansionToggled(viewId: String, expanded: Boolean)
        fun picked(selection: SnapshotFilesSelection)
        fun contextMenu(selection: SnapshotFilesSelection, component: Component, x: Int, y: Int)
        fun expandAll()
        fun collapseAll()
        fun clearSearchAndFilters()
        fun revealActive()
    }

    private val search = SearchTextField(false)
    private val failed = StatusChipToggle("Failed", GoldenDiffColors.failed)
    private val modified = StatusChipToggle("Modified", GoldenDiffColors.modified)
    private val new = StatusChipToggle("New", GoldenDiffColors.new)
    private val countLabel = JBLabel()
    private val expandAll = ActionLink("Expand all") { actions.expandAll() }
    private val collapseAll = ActionLink("Collapse all") { actions.collapseAll() }
    private val model = DefaultListModel<FilesRow>()
    private val list = StickyHeaderList(model)
    private val scroll = JBScrollPane(list)
    private val indexingIcon = AsyncProcessIcon("Indexing snapshots")
    private val indexingBanner = banner(JBLabel("Indexing snapshots…"), indexingIcon)
    private val revealButton = ActionLink("Reveal") { actions.revealActive() }
    private val revealBanner = banner(JBLabel("Active file hidden by filters"), revealButton)
    private val cards = JPanel(CardLayout())
    private val emptyLabel = JBLabel("", SwingConstants.CENTER)
    private val emptyAction = ActionLink("") { actions.clearSearchAndFilters() }
    private var rendering = false

    init {
        isOpaque = true
        background = GoldenDiffColors.panel
        add(buildHeader(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)
        configureList()
    }

    fun focusSearch() {
        search.requestFocusInWindow()
    }

    fun render(state: FilesListRenderState) {
        rendering = true
        try {
            if (search.text != state.query) search.text = state.query
            failed.isSelected = SnapshotFileStatus.FAILED in state.filters
            modified.isSelected = SnapshotFileStatus.MODIFIED in state.filters
            new.isSelected = SnapshotFileStatus.NEW in state.filters
            countLabel.text = state.presentation.countLabel
            replaceRows(state.presentation.rows)
            indexingBanner.isVisible = state.indexing
            if (state.indexing) indexingIcon.resume() else indexingIcon.suspend()
            revealBanner.isVisible = state.hiddenActiveViewName != null
            revealButton.text = "Reveal ${state.hiddenActiveViewName.orEmpty()}"
            showCard(state)
        } finally {
            rendering = false
        }
        revalidate()
        repaint()
    }

    private fun showCard(state: FilesListRenderState) {
        val card = when {
            state.presentation.rows.isNotEmpty() -> CARD_LIST
            state.query.isNotBlank() -> empty("No files match.", "Clear search")
            state.filters.isNotEmpty() -> empty("No matching files.", "Clear filters")
            state.hasIndex && state.presentation.totalViewCount == 0 -> empty("No snapshot-related files.", null)
            else -> empty(if (state.indexing) "Indexing snapshots…" else "No snapshot index.", null)
        }
        (cards.layout as CardLayout).show(cards, card)
    }

    private fun empty(message: String, action: String?): String {
        emptyLabel.text = message
        emptyAction.text = action.orEmpty()
        emptyAction.isVisible = action != null
        return CARD_EMPTY
    }

    private fun replaceRows(rows: List<FilesRow>) {
        val cursorId = list.selectedValue?.id
        if (model.size() != rows.size || rows.indices.any { model[it] != rows[it] }) {
            val position = scroll.viewport.viewPosition
            model.clear()
            rows.forEach(model::addElement)
            scroll.viewport.viewPosition = position
        }
        val cursor = rows.indexOfFirst { it.id == cursorId }.takeIf { it >= 0 }
            ?: rows.indexOfFirst { it.isStateSelected() }
        if (cursor >= 0) list.selectedIndex = cursor else list.clearSelection()
    }

    private fun buildHeader(): JComponent {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false

        search.textEditor.emptyText.text = "Filter files…"
        search.textEditor.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11.5f).toFloat())
        search.textEditor.getAccessibleContext()?.accessibleName = "Filter files, Views, and cases"
        search.border = JBUI.Borders.empty(8, 8, 4, 8)
        search.alignmentX = LEFT_ALIGNMENT
        search.maximumSize = Dimension(Int.MAX_VALUE, search.preferredSize.height + JBUI.scale(12))
        search.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = queryChanged()
            override fun removeUpdate(e: DocumentEvent) = queryChanged()
            override fun changedUpdate(e: DocumentEvent) = queryChanged()
        })
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DOWN && model.size() > 0) {
                    list.requestFocusInWindow()
                    if (list.selectedIndex < 0) list.selectedIndex = 0
                    e.consume()
                }
            }
        })
        container.add(search)

        val chips = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 5, 8, 5)
            listOf(
                failed to SnapshotFileStatus.FAILED,
                modified to SnapshotFileStatus.MODIFIED,
                new to SnapshotFileStatus.NEW,
            ).forEach { (chip, status) ->
                chip.addActionListener { if (!rendering) actions.filterChanged(status, chip.isSelected) }
                add(chip)
            }
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        container.add(chips)

        val small = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(10f).toFloat())
        countLabel.font = small
        countLabel.foreground = GoldenDiffColors.faint
        listOf(expandAll, collapseAll).forEach {
            it.font = small
            it.setForeground(GoldenDiffColors.dim)
        }
        val meta = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(GoldenDiffColors.border, 0, 0, 1, 0),
                JBUI.Borders.empty(0, 10, 6, 10),
            )
            add(countLabel, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
                    isOpaque = false
                    add(expandAll)
                    add(collapseAll)
                },
                BorderLayout.EAST,
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        container.add(meta)
        return container
    }

    private fun buildBody(): JComponent {
        val banners = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(revealBanner)
            add(indexingBanner)
        }
        revealBanner.isVisible = false
        indexingBanner.isVisible = false
        scroll.border = JBUI.Borders.empty()
        scroll.viewport.background = GoldenDiffColors.panel
        // The sticky header is painted over the list, so blitting would smear it while scrolling.
        scroll.viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
        scroll.getAccessibleContext()?.accessibleName = "Snapshot files"
        cards.isOpaque = false
        cards.add(scroll, CARD_LIST)
        cards.add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(20, 10)
                emptyLabel.alignmentX = CENTER_ALIGNMENT
                emptyLabel.foreground = GoldenDiffColors.faint
                emptyAction.alignmentX = CENTER_ALIGNMENT
                add(emptyLabel)
                add(Box.createVerticalStrut(JBUI.scale(6)))
                add(emptyAction)
                add(Box.createVerticalGlue())
            },
            CARD_EMPTY,
        )
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(banners, BorderLayout.NORTH)
            add(cards, BorderLayout.CENTER)
        }
    }

    private fun configureList() {
        list.cellRenderer = FilesRowRenderer()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.background = GoldenDiffColors.panel
        list.border = JBUI.Borders.emptyBottom(4)
        list.getAccessibleContext()?.accessibleName = "Views and snapshot cases"
        list.emptyText.text = ""
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (handlePopup(e)) return
                if (e.button != MouseEvent.BUTTON1) return
                list.stickyFolderAt(e.point)?.let { folder ->
                    actions.folderToggled(folder.path, !folder.collapsed)
                    e.consume()
                    return
                }
                val index = list.rowIndexAt(e.point) ?: return
                activate(model[index], e.x - list.getCellBounds(index, index).x)
            }

            override fun mouseReleased(e: MouseEvent) {
                handlePopup(e)
            }

            private fun handlePopup(e: MouseEvent): Boolean {
                if (!e.isPopupTrigger) return false
                val index = list.rowIndexAt(e.point) ?: return true
                val selection = model[index].asSelection() ?: return true
                list.selectedIndex = index
                actions.contextMenu(selection, list, e.x, e.y)
                e.consume()
                return true
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val row = list.selectedValue ?: return
                when (e.keyCode) {
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> activate(row, Int.MAX_VALUE)
                    KeyEvent.VK_LEFT -> setExpanded(row, false)
                    KeyEvent.VK_RIGHT -> setExpanded(row, true)
                    else -> return
                }
                e.consume()
            }
        })
    }

    /** [x] is relative to the row; a click on a View's chevron toggles its Cases instead of picking. */
    private fun activate(row: FilesRow, x: Int) {
        when (row) {
            is FilesRow.Folder -> actions.folderToggled(row.path, !row.collapsed)
            is FilesRow.View ->
                if (row.hasCases && x < JBUI.scale(VIEW_CHEVRON_END)) {
                    actions.viewExpansionToggled(row.viewId, !row.expanded)
                } else {
                    actions.picked(SnapshotFilesSelection.View(row.viewId))
                }
            is FilesRow.Case -> actions.picked(SnapshotFilesSelection.Case(row.viewId, row.entry.id))
        }
    }

    private fun setExpanded(row: FilesRow, expanded: Boolean) {
        when (row) {
            is FilesRow.Folder -> if (row.collapsed == expanded) actions.folderToggled(row.path, !expanded)
            is FilesRow.View -> if (row.hasCases && row.expanded != expanded) {
                actions.viewExpansionToggled(row.viewId, expanded)
            }
            is FilesRow.Case -> Unit
        }
    }

    private fun queryChanged() {
        if (!rendering) actions.queryChanged(search.text)
    }

    /** List that paints the folder header of the first visible row pinned to the viewport top. */
    private class StickyHeaderList(model: DefaultListModel<FilesRow>) : JBList<FilesRow>(model) {
        private val headerPane = CellRendererPane()
        private var sticky: Pair<FilesRow.Folder, Rectangle>? = null

        init {
            add(headerPane)
        }

        override fun getScrollableTracksViewportWidth(): Boolean = true

        fun rowIndexAt(point: Point): Int? {
            val index = locationToIndex(point)
            if (index < 0 || getCellBounds(index, index)?.contains(point) != true) return null
            return index
        }

        fun stickyFolderAt(point: Point): FilesRow.Folder? =
            sticky?.takeIf { (_, bounds) -> bounds.contains(point) }?.first

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            sticky = null
            val visible = visibleRect
            val first = locationToIndex(Point(0, visible.y)).takeIf { it >= 0 } ?: return
            val folderIndex = (first downTo 0).firstOrNull { model.getElementAt(it) is FilesRow.Folder } ?: return
            val folderBounds = getCellBounds(folderIndex, folderIndex) ?: return
            if (folderBounds.y >= visible.y) return
            val headerHeight = folderBounds.height
            val next = (first + 1 until model.size).firstOrNull { model.getElementAt(it) is FilesRow.Folder }
            val nextTop = next?.let { getCellBounds(it, it)?.y } ?: Int.MAX_VALUE
            val y = minOf(visible.y, nextTop - headerHeight)
            val folder = model.getElementAt(folderIndex) as FilesRow.Folder
            val component = cellRenderer.getListCellRendererComponent(this, folder, folderIndex, false, false)
            headerPane.paintComponent(g, component, this, 0, y, width, headerHeight, true)
            sticky = folder to Rectangle(0, y, width, headerHeight)
        }
    }

    private inner class FilesRowRenderer : ListCellRenderer<FilesRow> {
        private val folder = FolderRowComponent()
        private val view = EntryRowComponent()

        override fun getListCellRendererComponent(
            list: JList<out FilesRow>,
            value: FilesRow,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component = when (value) {
            is FilesRow.Folder -> folder.bind(value)
            is FilesRow.View -> view.bindView(value, cursor = isSelected && list.hasFocus(), list.width)
            is FilesRow.Case -> view.bindCase(value, cursor = isSelected && list.hasFocus(), list.width)
        }
    }

    private class FolderRowComponent : JPanel(BorderLayout(JBUI.scale(6), 0)) {
        private val chevron = JBLabel()
        private val label = JBLabel()
        private val failedPill = PillLabel(9.5f)
        private val count = JBLabel()

        init {
            isOpaque = true
            background = GoldenDiffColors.panel
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(GoldenDiffColors.border, 0, 0, 1, 0),
                JBUI.Borders.empty(6, 10),
            )
            chevron.foreground = GoldenDiffColors.faint
            chevron.preferredSize = JBUI.size(10, 14)
            label.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(10f).toFloat())
            label.foreground = GoldenDiffColors.dim
            count.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(9.5f).toFloat())
            count.foreground = GoldenDiffColors.faint
            add(chevron, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(failedPill)
                    add(count)
                },
                BorderLayout.EAST,
            )
        }

        fun bind(row: FilesRow.Folder): Component {
            chevron.text = if (row.collapsed) "▸" else "▾"
            label.text = row.path.ifEmpty { "/" }.uppercase()
            failedPill.setPill(row.failedCount.takeIf { it > 0 }?.toString())
            count.text = row.viewCount.toString()
            toolTipText = row.path
            getAccessibleContext()?.accessibleName =
                "${row.path} folder, ${row.viewCount} files, ${row.failedCount} failed" +
                    if (row.collapsed) ", collapsed" else ""
            return this
        }
    }

    private class EntryRowComponent : JPanel(BorderLayout(JBUI.scale(7), 0)) {
        private val leading = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        private val chevron = JBLabel()
        private val icon = JBLabel()
        private val name = JBLabel()
        private val badge = PillLabel()
        private val baseFont = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(12f).toFloat())
        private val caseFont = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(11f))

        init {
            isOpaque = true
            leading.isOpaque = false
            chevron.foreground = GoldenDiffColors.faint
            chevron.preferredSize = JBUI.size(12, 14)
            icon.border = JBUI.Borders.emptyLeft(2)
            leading.add(chevron)
            leading.add(icon)
            add(leading, BorderLayout.WEST)
            add(name, BorderLayout.CENTER)
            add(badge, BorderLayout.EAST)
        }

        fun bindView(row: FilesRow.View, cursor: Boolean, listWidth: Int): Component {
            val fileName = File(row.viewId).name
            bind(
                indent = 6,
                chevronText = if (!row.hasCases) "" else if (row.expanded) "▾" else "▸",
                rowIcon = fileIcon(fileName),
                text = File(row.viewId).nameWithoutExtension,
                font = baseFont,
                status = row.status,
                selected = row.selected,
                cursor = cursor,
            )
            toolTipText = boundedPathTooltip(row.viewId, listWidth, getFontMetrics(baseFont))
            getAccessibleContext()?.accessibleName = "${File(row.viewId).nameWithoutExtension}, ${row.status.label}" +
                if (row.hasCases) if (row.expanded) ", expanded" else ", collapsed" else ""
            return this
        }

        fun bindCase(row: FilesRow.Case, cursor: Boolean, listWidth: Int): Component {
            bind(
                indent = 26,
                chevronText = "",
                rowIcon = AllIcons.Nodes.Method,
                text = row.entry.methodName,
                font = caseFont,
                status = row.status,
                selected = row.selected,
                cursor = cursor,
            )
            toolTipText = boundedPathTooltip(row.entry.generatorPath, listWidth, getFontMetrics(caseFont))
            getAccessibleContext()?.accessibleName = "${row.entry.methodName} snapshot case, ${row.status.label}"
            return this
        }

        private fun bind(
            indent: Int,
            chevronText: String,
            rowIcon: Icon,
            text: String,
            font: Font,
            status: GoldenDisplayStatus,
            selected: Boolean,
            cursor: Boolean,
        ) {
            border = JBUI.Borders.empty(5, indent, 5, 10)
            background = when {
                selected -> GoldenDiffColors.accentBackground
                cursor -> GoldenDiffColors.disabledFill
                else -> GoldenDiffColors.panel
            }
            chevron.text = chevronText
            icon.icon = rowIcon
            name.text = text
            name.font = font
            name.foreground = GoldenDiffColors.text
            badge.showStatus(status)
        }

        private fun fileIcon(name: String): Icon =
            runCatching { FileTypeManager.getInstance().getFileTypeByFileName(name).icon }.getOrNull()
                ?: AllIcons.FileTypes.Any_type
    }

    private companion object {
        const val CARD_LIST = "list"
        const val CARD_EMPTY = "empty"

        /** View rows start with a 6px inset plus a 12px chevron; clicks left of this toggle Cases. */
        const val VIEW_CHEVRON_END = 20

        fun banner(label: JComponent, trailing: JComponent): JPanel =
            JPanel(BorderLayout(JBUI.scale(7), 0)).apply {
                isOpaque = true
                background = GoldenDiffColors.header
                border = JBUI.Borders.empty(5, 10)
                add(label, BorderLayout.CENTER)
                add(trailing, BorderLayout.EAST)
            }
    }
}

private fun FilesRow.isStateSelected(): Boolean = when (this) {
    is FilesRow.Folder -> false
    is FilesRow.View -> selected
    is FilesRow.Case -> selected
}

private fun FilesRow.asSelection(): SnapshotFilesSelection? = when (this) {
    is FilesRow.Folder -> null
    is FilesRow.View -> SnapshotFilesSelection.View(viewId)
    is FilesRow.Case -> SnapshotFilesSelection.Case(viewId, entry.id)
}

private val GoldenDisplayStatus.label: String
    get() = badgeLabel ?: "Unchanged"

internal fun boundedPathTooltip(path: String, availableWidth: Int, metrics: FontMetrics): String {
    val width = (availableWidth - JBUI.scale(24))
        .coerceAtLeast(JBUI.scale(48))
        .coerceAtMost(JBUI.scale(420))
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    path.forEach { character ->
        current.append(character)
        if (current.length > 1 && metrics.stringWidth(current.toString()) > width) {
            current.setLength(current.length - 1)
            lines += current.toString()
            current.clear()
            current.append(character)
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    val escaped = lines.joinToString("<br>") { line ->
        line
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
    return "<html>$escaped</html>"
}

sealed interface SnapshotFilesSelection {
    val sourcePath: String

    data class View(override val sourcePath: String) : SnapshotFilesSelection

    data class Case(
        override val sourcePath: String,
        val caseId: String,
    ) : SnapshotFilesSelection
}
