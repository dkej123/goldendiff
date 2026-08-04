package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.project.SnapshotFolderProjection
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import com.github.dkwasniak.goldendiff.project.SnapshotTreeProjection
import com.github.dkwasniak.goldendiff.project.SnapshotViewProjection
import com.github.dkwasniak.goldendiff.project.projectTree
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeCellRenderer

/**
 * Snapshot-aware Files pane for the plugin UI.
 *
 * This component only projects an already-built [SnapshotFileIndex]. Indexing and editor navigation
 * stay in the tool-window controller, and no app-only UI code or Compose dependency crosses this
 * boundary.
 */
class SnapshotFilesPanel(
    private val onSelection: (SnapshotFilesSelection) -> Unit,
    private val onOpen: (SnapshotFilesSelection) -> Unit,
    private val onRevealActive: () -> Unit = {},
    private val onCollapse: () -> Unit = {},
    private val onContextMenu: (SnapshotFilesSelection, Component, Int, Int) -> Unit = { _, _, _, _ -> },
) : JPanel(BorderLayout()) {
    private val search = SearchTextField(false)
    private val grouped = GroupToggle()
    private val collapse = JButton("▴")
    private val failed = statusToggle("Failed", StatusColors.failed)
    private val modified = statusToggle("Modified", StatusColors.modified)
    private val new = statusToggle("New", StatusColors.new)
    private val treeRoot = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = object : Tree(treeModel) {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }
    private val revealPanel = JPanel(BorderLayout())
    private val revealLabel = JBLabel("Active file hidden by filters")
    private val revealButton = JButton("Reveal")
    private val indexingIcon = AsyncProcessIcon("Indexing snapshots")
    private val indexingPanel = banner("Indexing snapshots…", indexingIcon)
    private val contentCards = JPanel(CardLayout())
    private val treeScroll = JBScrollPane(tree)
    private val emptyPanel = JPanel(BorderLayout())
    private val emptyLabel = JBLabel("", SwingConstants.CENTER)
    private val emptyAction = JButton()

    private var index: SnapshotFileIndex? = null
    private var activePath: String? = null
    private var requestedSelection: SnapshotFilesSelection? = null
    private var suppressSelectionCallback = false
    private var suppressExpansionTracking = false
    private var indexing = false
    private val expandedViews = linkedSetOf<String>()

    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.customLine(StatusColors.border, 0, 0, 1, 0)

        add(createHeaderAndFilters(), BorderLayout.NORTH)
        add(createBody(), BorderLayout.CENTER)
        configureTree()
        configureListeners()
        rebuild()
    }

    /**
     * Replaces the immutable index snapshot. A soft refresh can keep [index] visible while setting
     * [indexing] to true.
     */
    fun update(
        index: SnapshotFileIndex?,
        activePath: String? = this.activePath,
        selection: SnapshotFilesSelection? = requestedSelection,
        indexing: Boolean = false,
    ) {
        this.index = index
        this.activePath = activePath?.normalizedPath()
        this.requestedSelection = selection
        if (selection is SnapshotFilesSelection.Case) expandedViews += selection.sourcePath
        this.indexing = indexing
        rebuild()
    }

    fun setActivePath(path: String?) {
        activePath = path?.normalizedPath()
        refreshBanners()
    }

    /**
     * Selects a stable View/Case identity without firing [onSelection]. When [reveal] is true, the
     * row is scrolled into view but the user's search and filters are left intact.
     */
    fun setSelection(selection: SnapshotFilesSelection?, reveal: Boolean = false) {
        requestedSelection = selection
        if (selection is SnapshotFilesSelection.Case) {
            expandedViews += selection.sourcePath
            rebuild(revealSelection = reveal)
            return
        }
        restoreSelection(reveal)
        refreshBanners()
    }

    fun focusSearch() {
        search.requestFocusInWindow()
    }

    fun setCollapsePresentation(
        collapsed: Boolean,
        presentation: SnapshotFilesCollapsePresentation,
    ) {
        collapse.text = when (presentation) {
            SnapshotFilesCollapsePresentation.WIDE -> if (collapsed) "▾" else "▴"
            SnapshotFilesCollapsePresentation.OVERLAY -> "‹"
        }
        collapse.toolTipText = when (presentation) {
            SnapshotFilesCollapsePresentation.WIDE -> if (collapsed) "Expand Files" else "Collapse Files"
            SnapshotFilesCollapsePresentation.OVERLAY -> "Close Files"
        }
        collapse.accessibleContext.accessibleName = collapse.toolTipText
    }

    private fun createHeaderAndFilters(): JComponent {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false

        val header = JPanel(BorderLayout())
        header.isOpaque = true
        header.background = StatusColors.header
        header.border = JBUI.Borders.empty(5, 10)
        header.preferredSize = Dimension(0, JBUI.scale(30))
        val title = JBLabel("FILES")
        title.font = title.font.deriveFont(Font.BOLD, UIUtil.getLabelFont().size2D - 1f)
        title.foreground = UIUtil.getContextHelpForeground()
        title.accessibleContext.accessibleName = "Files pane"
        grouped.isFocusable = true
        grouped.toolTipText = "Group Views by folder"
        grouped.accessibleContext.accessibleName = "Group Views by folder"
        header.add(title, BorderLayout.WEST)
        val groupControl = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0))
        groupControl.isOpaque = false
        groupControl.add(JBLabel("Group").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1f)
        })
        groupControl.add(grouped)
        collapse.isOpaque = false
        collapse.isContentAreaFilled = false
        collapse.isBorderPainted = false
        collapse.isFocusPainted = true
        collapse.margin = JBUI.emptyInsets()
        collapse.preferredSize = JBUI.size(20, 20)
        collapse.toolTipText = "Collapse Files"
        collapse.accessibleContext.accessibleName = "Collapse Files"
        groupControl.add(collapse)
        header.add(groupControl, BorderLayout.EAST)
        container.add(header)

        search.textEditor.emptyText.text = "Filter files, Views, cases…"
        search.textEditor.font = UIUtil.getLabelFont().deriveFont(11f)
        search.textEditor.accessibleContext.accessibleName = "Filter files, Views, and cases"
        search.border = JBUI.Borders.empty(4, 8, 3, 8)
        search.maximumSize = Dimension(Int.MAX_VALUE, search.preferredSize.height + JBUI.scale(6))
        container.add(search)

        val chips = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        chips.isOpaque = false
        chips.border = JBUI.Borders.empty(0, 7, 5, 7)
        chips.add(failed)
        chips.add(modified)
        chips.add(new)
        container.add(chips)
        return container
    }

    private fun createBody(): JComponent {
        val body = JPanel(BorderLayout())
        body.isOpaque = false
        val banners = JPanel()
        banners.layout = BoxLayout(banners, BoxLayout.Y_AXIS)
        banners.isOpaque = false

        revealPanel.isOpaque = true
        revealPanel.background = StatusColors.notice
        revealPanel.border = JBUI.Borders.empty(5, 9)
        revealLabel.foreground = UIUtil.getLabelForeground()
        revealLabel.accessibleContext.accessibleName = "Active file hidden by filters"
        revealButton.isOpaque = false
        revealButton.isContentAreaFilled = false
        revealButton.isBorderPainted = false
        revealButton.foreground = StatusColors.link
        revealButton.accessibleContext.accessibleName = "Reveal active View"
        revealPanel.add(revealLabel, BorderLayout.CENTER)
        revealPanel.add(revealButton, BorderLayout.EAST)
        banners.add(revealPanel)
        banners.add(indexingPanel)
        body.add(banners, BorderLayout.NORTH)

        treeScroll.border = BorderFactory.createEmptyBorder()
        treeScroll.viewport.background = StatusColors.panel
        treeScroll.accessibleContext.accessibleName = "Snapshot files"
        contentCards.add(treeScroll, CARD_TREE)
        emptyPanel.isOpaque = false
        val center = JPanel()
        center.layout = BoxLayout(center, BoxLayout.Y_AXIS)
        center.isOpaque = false
        center.add(Box.createVerticalGlue())
        emptyLabel.alignmentX = CENTER_ALIGNMENT
        emptyLabel.foreground = UIUtil.getContextHelpForeground()
        center.add(emptyLabel)
        center.add(Box.createVerticalStrut(JBUI.scale(8)))
        emptyAction.alignmentX = CENTER_ALIGNMENT
        center.add(emptyAction)
        center.add(Box.createVerticalGlue())
        emptyPanel.add(center, BorderLayout.CENTER)
        contentCards.add(emptyPanel, CARD_EMPTY)
        body.add(contentCards, BorderLayout.CENTER)
        return body
    }

    private fun configureTree() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.rowHeight = JBUI.scale(22)
        tree.background = StatusColors.panel
        tree.border = JBUI.Borders.empty(0, 6, 8, 6)
        tree.cellRenderer = FilesTreeRenderer()
        tree.setExpandableItemsEnabled(false)
        tree.emptyText.text = ""
        tree.accessibleContext.accessibleName = "Views and snapshot cases"
        tree.selectionModel.selectionMode =
            javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    private fun configureListeners() {
        search.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })
        listOf(grouped, failed, modified, new).forEach { button ->
            button.addActionListener { rebuild() }
        }
        collapse.addActionListener { onCollapse() }
        revealButton.addActionListener {
            val target = activePath ?: return@addActionListener
            search.text = ""
            failed.isSelected = false
            modified.isSelected = false
            new.isSelected = false
            requestedSelection = SnapshotFilesSelection.View(target)
            rebuild(revealSelection = true)
            onRevealActive()
            onSelection(SnapshotFilesSelection.View(target))
        }
        emptyAction.addActionListener {
            if (search.text.isNotBlank()) {
                search.text = ""
            } else {
                failed.isSelected = false
                modified.isSelected = false
                new.isSelected = false
                rebuild()
            }
        }
        tree.addTreeSelectionListener {
            if (suppressSelectionCallback) return@addTreeSelectionListener
            val selection = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)
                ?.userObject.asSelection() ?: return@addTreeSelectionListener
            requestedSelection = selection
            onSelection(selection)
            refreshBanners()
        }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                if (suppressExpansionTracking) return
                ((event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ViewRow)
                    ?.let { expandedViews += it.file.relativePath }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                if (suppressExpansionTracking) return
                ((event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ViewRow)
                    ?.let { expandedViews -= it.file.relativePath }
            }
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = showContextMenu(e)

            override fun mouseReleased(e: MouseEvent) = showContextMenu(e)

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = tree.getPathForLocation(e.x, e.y)?.lastPathComponent as? DefaultMutableTreeNode
                node?.userObject.asSelection()?.let(onOpen)
            }

            private fun showContextMenu(event: MouseEvent) {
                if (!event.isPopupTrigger) return
                val path = tree.getPathForLocation(event.x, event.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val selection = node.userObject.asSelection() ?: return
                tree.selectionPath = path
                onContextMenu(selection, tree, event.x, event.y)
                event.consume()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)
                    ?.userObject.asSelection()?.let(onOpen)
            }
        })
    }

    private fun rebuild(revealSelection: Boolean = false) {
        val previousScroll = treeScroll.viewport.viewPosition
        val projection = projectRows()
        treeRoot.removeAllChildren()
        if (grouped.isSelected) {
            appendGrouped(projection)
        } else {
            projection.views.forEach { projected ->
                treeRoot.add(viewNode(projected))
            }
        }
        suppressExpansionTracking = true
        try {
            treeModel.reload()
            expandInitialRows()
        } finally {
            suppressExpansionTracking = false
        }
        restoreSelection(revealSelection)
        refreshBanners(projection)
        if (!revealSelection) {
            restoreScroll(previousScroll)
        }
    }

    private fun restoreScroll(position: Point) {
        javax.swing.SwingUtilities.invokeLater {
            val view = treeScroll.viewport.view ?: return@invokeLater
            treeScroll.viewport.viewPosition = Point(
                position.x.coerceIn(0, (view.width - treeScroll.viewport.width).coerceAtLeast(0)),
                position.y.coerceIn(0, (view.height - treeScroll.viewport.height).coerceAtLeast(0)),
            )
        }
    }

    private fun projectRows(): SnapshotTreeProjection =
        index?.projectTree(search.text, selectedFilters()) ?: SnapshotTreeProjection(emptyList(), emptyList())

    private fun appendGrouped(projection: SnapshotTreeProjection) {
        projection.folders.forEach { appendFolder(treeRoot, it) }
        projection.rootViews.forEach { treeRoot.add(viewNode(it)) }
    }

    private fun appendFolder(parent: DefaultMutableTreeNode, folder: SnapshotFolderProjection) {
        val node = DefaultMutableTreeNode(FolderRow(folder.path, folder.name, folder.failedViewCount))
        parent.add(node)
        folder.views.forEach { node.add(viewNode(it)) }
        folder.folders.forEach { appendFolder(node, it) }
    }

    private fun viewNode(projected: SnapshotViewProjection): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(ViewRow(projected.entry))
        projected.cases
            .forEach { node.add(DefaultMutableTreeNode(CaseRow(projected.entry.relativePath, it.entry))) }
        return node
    }

    private fun expandInitialRows() {
        val queryActive = search.text.isNotBlank()
        var row = 0
        while (row < tree.rowCount) {
            val path = tree.getPathForRow(row)
            when (val value = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
                is FolderRow -> tree.expandPath(path)
                is ViewRow -> if (queryActive || value.file.relativePath in expandedViews) tree.expandPath(path)
            }
            row++
        }
    }

    private fun restoreSelection(reveal: Boolean) {
        val wanted = requestedSelection
        suppressSelectionCallback = true
        try {
            val path = tree.paths().firstOrNull { treePath ->
                (treePath.lastPathComponent as DefaultMutableTreeNode).userObject.asSelection() == wanted
            }
            tree.selectionPath = path
            if (reveal && path != null) tree.scrollPathToVisible(path)
        } finally {
            suppressSelectionCallback = false
        }
    }

    private fun refreshBanners(projection: SnapshotTreeProjection = projectRows()) {
        indexingPanel.isVisible = indexing
        if (indexing) indexingIcon.resume() else indexingIcon.suspend()
        val normalizedActive = activePath
        val activeExists = normalizedActive != null &&
            index?.files.orEmpty().any { it.relativePath.normalizedPath() == normalizedActive }
        val activeVisible = normalizedActive != null &&
            projection.views.any { it.entry.relativePath.normalizedPath() == normalizedActive }
        revealPanel.isVisible = activeExists && !activeVisible
        if (revealPanel.isVisible) {
            val name = File(normalizedActive!!).nameWithoutExtension
            revealButton.text = "Reveal $name"
            revealButton.accessibleContext.accessibleName = "Reveal active View $name"
        }

        val card = when {
            projection.views.isNotEmpty() -> CARD_TREE
            search.text.isNotBlank() -> {
                emptyLabel.text = "No files match your search"
                emptyAction.text = "Clear search"
                CARD_EMPTY
            }
            selectedFilters().isNotEmpty() -> {
                emptyLabel.text = "No matching Views"
                emptyAction.text = "Clear filters"
                CARD_EMPTY
            }
            index != null && index!!.files.isEmpty() -> {
                emptyLabel.text = "No snapshot-related files"
                emptyAction.text = ""
                CARD_EMPTY
            }
            else -> {
                emptyLabel.text = if (indexing) "Indexing snapshots…" else "No snapshot index"
                emptyAction.text = ""
                CARD_EMPTY
            }
        }
        emptyAction.isVisible = emptyAction.text.isNotEmpty()
        (contentCards.layout as CardLayout).show(contentCards, card)
        revalidate()
        repaint()
    }

    private fun selectedFilters(): Set<SnapshotFileStatus> = buildSet {
        if (failed.isSelected) add(SnapshotFileStatus.FAILED)
        if (modified.isSelected) add(SnapshotFileStatus.MODIFIED)
        if (new.isSelected) add(SnapshotFileStatus.NEW)
    }

    private inner class FilesTreeRenderer : JPanel(BorderLayout()), TreeCellRenderer {
        private val iconLabel = JBLabel()
        private val textLabel = JBLabel()
        private val badge = BadgeLabel()
        private val baseFont = UIUtil.getLabelFont()

        init {
            isOpaque = true
            border = JBUI.Borders.empty(0, 3)
            iconLabel.border = JBUI.Borders.emptyRight(5)
            add(iconLabel, BorderLayout.WEST)
            add(textLabel, BorderLayout.CENTER)
            add(badge, BorderLayout.EAST)
        }

        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): Component {
            background = if (selected) {
                UIManager.getColor("Tree.selectionBackground") ?: StatusColors.selection
            } else {
                StatusColors.panel
            }
            iconLabel.icon = null
            textLabel.text = ""
            textLabel.font = baseFont
            textLabel.foreground = if (selected) {
                UIManager.getColor("Tree.selectionForeground") ?: UIUtil.getLabelForeground()
            } else {
                UIUtil.getLabelForeground()
            }
            badge.reset()
            toolTipText = null
            val item = (value as? DefaultMutableTreeNode)?.userObject
            when (item) {
                is FolderRow -> {
                    iconLabel.icon = AllIcons.Nodes.Folder
                    textLabel.text = item.name
                    textLabel.font = baseFont.deriveFont(Font.BOLD, (baseFont.size2D - 2f).coerceAtLeast(9f))
                    if (!selected) textLabel.foreground = UIUtil.getContextHelpForeground()
                    if (item.failedViews > 0) {
                        badge.show(item.failedViews.toString(), StatusColors.failed, Color.WHITE)
                    }
                    toolTipText = boundedPathTooltip(
                        item.path,
                        tree.visibleRect.width,
                        textLabel.getFontMetrics(textLabel.font),
                    )
                    accessibleContext?.accessibleName =
                        "${item.name} folder, ${item.failedViews} failing Views"
                }
                is ViewRow -> {
                    iconLabel.icon = AllIcons.FileTypes.Any_type
                    textLabel.text = File(item.file.relativePath).nameWithoutExtension
                    textLabel.font = baseFont.deriveFont(Font.PLAIN, (baseFont.size2D - 1f).coerceAtLeast(10f))
                    badge.showStatus(item.file.status, selected)
                    toolTipText = boundedPathTooltip(
                        item.file.relativePath,
                        tree.visibleRect.width,
                        textLabel.getFontMetrics(textLabel.font),
                    )
                    accessibleContext?.accessibleName =
                        "${File(item.file.relativePath).nameWithoutExtension}, ${item.file.status.label}"
                }
                is CaseRow -> {
                    iconLabel.icon = AllIcons.Nodes.Method
                    textLabel.text = item.case.methodName
                    textLabel.font = Font(Font.MONOSPACED, Font.PLAIN, (baseFont.size - 2).coerceAtLeast(9))
                    if (!selected) textLabel.foreground = UIUtil.getContextHelpForeground()
                    badge.showStatus(item.case.status, selected)
                    toolTipText = boundedPathTooltip(
                        item.case.generatorPath,
                        tree.visibleRect.width,
                        textLabel.getFontMetrics(textLabel.font),
                    )
                    accessibleContext?.accessibleName =
                        "${item.case.methodName} snapshot case, ${item.case.status.label}"
                }
            }
            // Do not call tree.getRowBounds(row) here: IntelliJ computes row bounds by invoking the
            // renderer, so doing that recursively overflows the EDT and blanks the entire tool window.
            val nodeDepth = (value as? DefaultMutableTreeNode)?.level ?: 0
            val indentPerLevel = (
                UIManager.getInt("Tree.leftChildIndent") +
                    UIManager.getInt("Tree.rightChildIndent")
                ).takeIf { it > 0 } ?: JBUI.scale(20)
            val availableWidth = tree.visibleRect.width -
                tree.insets.left -
                indentPerLevel * nodeDepth
            preferredSize = Dimension(availableWidth.coerceAtLeast(JBUI.scale(120)), tree.rowHeight)
            return this
        }
    }

    private class BadgeLabel : JLabel("", SwingConstants.CENTER) {
        private var fill: Color = StatusColors.panel

        init {
            isOpaque = false
            border = JBUI.Borders.empty(0, 6)
            font = UIUtil.getLabelFont().deriveFont(
                Font.BOLD,
                (UIUtil.getLabelFont().size2D - 3f).coerceAtLeast(9f),
            )
        }

        fun reset() {
            text = ""
            isVisible = false
            fill = StatusColors.panel
            foreground = UIUtil.getLabelForeground()
        }

        fun show(text: String, background: Color, foreground: Color) {
            this.text = text
            fill = background
            this.foreground = foreground
            isVisible = true
        }

        fun showStatus(status: SnapshotFileStatus, selected: Boolean) {
            if (status == SnapshotFileStatus.UNCHANGED) return
            val color = status.color
            show(
                status.label,
                if (selected) color.withAlpha(90) else color.withAlpha(if (JBColor.isBright()) 26 else 36),
                if (selected) Color.WHITE else color,
            )
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = fill
                g.fillRoundRect(0, JBUI.scale(2), width, height - JBUI.scale(4), height, height)
            } finally {
                g.dispose()
            }
            super.paintComponent(graphics)
        }
    }

    private data class FolderRow(val path: String, val name: String, val failedViews: Int)
    private data class ViewRow(val file: SnapshotSourceFile)
    private data class CaseRow(val parentPath: String, val case: SnapshotCase)

    private fun Any?.asSelection(): SnapshotFilesSelection? = when (this) {
        is ViewRow -> SnapshotFilesSelection.View(file.relativePath)
        is CaseRow -> SnapshotFilesSelection.Case(parentPath, case.id)
        else -> null
    }

    private fun Tree.paths(): Sequence<TreePath> = sequence {
        for (row in 0 until rowCount) yield(getPathForRow(row))
    }

    private companion object {
        const val CARD_TREE = "tree"
        const val CARD_EMPTY = "empty"

        fun statusToggle(label: String, color: Color): JToggleButton =
            StatusToggle(label, color).apply {
                isFocusable = true
                toolTipText = "Show $label Views and cases"
                accessibleContext.accessibleName = "$label status filter"
            }

        fun banner(text: String, icon: Component): JPanel =
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(7), JBUI.scale(5))).apply {
                isOpaque = true
                background = StatusColors.notice
                border = JBUI.Borders.empty(0, 3)
                add(icon)
                add(JBLabel(text).apply {
                    accessibleContext.accessibleName = text
                })
            }
    }

    private class GroupToggle : JToggleButton("", true) {
        init {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = true
            preferredSize = JBUI.size(24, 14)
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val trackHeight = JBUI.scale(13)
                val trackY = (height - trackHeight) / 2
                g.color = if (isSelected) StatusColors.link else StatusColors.border
                g.fillRoundRect(0, trackY, width, trackHeight, trackHeight, trackHeight)
                val knob = JBUI.scale(10)
                val inset = JBUI.scale(2)
                val knobX = if (isSelected) width - knob - inset else inset
                g.color = Color.WHITE
                g.fillOval(knobX, (height - knob) / 2, knob, knob)
            } finally {
                g.dispose()
            }
        }
    }

    private class StatusToggle(
        text: String,
        private val statusColor: Color,
    ) : JToggleButton(text) {
        init {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = true
            font = UIUtil.getLabelFont().deriveFont(
                Font.BOLD,
                (UIUtil.getLabelFont().size2D - 2f).coerceAtLeast(9f),
            )
            val metrics = getFontMetrics(font)
            preferredSize = Dimension(
                metrics.stringWidth(text) + JBUI.scale(14),
                JBUI.scale(20),
            )
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = if (isSelected) statusColor else statusColor.withAlpha(
                    if (JBColor.isBright()) 26 else 36,
                )
                g.fillRoundRect(0, 0, width, height, height, height)
                g.font = font
                g.color = if (isSelected) Color.WHITE else statusColor
                val metrics = g.fontMetrics
                val x = (width - metrics.stringWidth(text)) / 2
                val y = (height - metrics.height) / 2 + metrics.ascent
                g.drawString(text, x, y)
            } finally {
                g.dispose()
            }
        }
    }
}

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

enum class SnapshotFilesCollapsePresentation {
    WIDE,
    OVERLAY,
}

private val SnapshotFileStatus.label: String
    get() = when (this) {
        SnapshotFileStatus.FAILED -> "Failed"
        SnapshotFileStatus.MODIFIED -> "Modified"
        SnapshotFileStatus.NEW -> "New"
        SnapshotFileStatus.UNCHANGED -> "Unchanged"
    }

private val SnapshotFileStatus.color: Color
    get() = when (this) {
        SnapshotFileStatus.FAILED -> StatusColors.failed
        SnapshotFileStatus.MODIFIED -> StatusColors.modified
        SnapshotFileStatus.NEW -> StatusColors.new
        SnapshotFileStatus.UNCHANGED -> UIUtil.getContextHelpForeground()
    }

private fun String.normalizedPath(): String = replace('\\', '/')

private fun Color.withAlpha(alpha: Int): Color = Color(red, green, blue, alpha)

private object StatusColors {
    val header = JBColor(Color(0xF2F3F5), Color(0x2B2D30))
    val panel = JBColor(Color(0xFFFFFF), Color(0x242629))
    val selection = JBColor(Color(0xE8F0FE), Color(0x2D4B7D))
    val border = JBColor(Color(0xE1E3E8), Color(0x3A3C3F))
    val notice = JBColor(Color(0xF2F3F5), Color(0x2B2D30))
    val link = JBColor(Color(0x3574F0), Color(0x548AF7))
    val failed = JBColor(Color(0xC9424A), Color(0xE0656B))
    val modified = JBColor(Color(0xA9761F), Color(0xD9A441))
    val new = JBColor(Color(0x2F8F52), Color(0x66B578))
}
