package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.command.CommandTemplate
import com.github.dkwasniak.goldendiff.command.CompletionCommandKind
import com.github.dkwasniak.goldendiff.command.CompletionCommandScope
import com.github.dkwasniak.goldendiff.command.CompletionNotificationPolicy
import com.github.dkwasniak.goldendiff.command.CompletionResult
import com.github.dkwasniak.goldendiff.command.GoldenAccept
import com.github.dkwasniak.goldendiff.compare.CompareView
import com.github.dkwasniak.goldendiff.compare.FixedBottomRightOverlayPanel
import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartResolution
import com.github.dkwasniak.goldendiff.compare.ImageBytes
import com.github.dkwasniak.goldendiff.compare.ImagePainting
import com.github.dkwasniak.goldendiff.compare.PixelDiff
import com.github.dkwasniak.goldendiff.compare.toArgbImage
import com.github.dkwasniak.goldendiff.git.GitCli
import com.github.dkwasniak.goldendiff.match.GenericScreenExtractor
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.project.ProjectFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotFileIndex
import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.scan.ChangeScanner
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.scan.MergedGoldenStatus
import com.github.dkwasniak.goldendiff.scan.automaticBuiltInSource
import com.github.dkwasniak.goldendiff.settings.ScreenshotConfigurable
import com.github.dkwasniak.goldendiff.settings.ScreenshotSettings
import com.github.dkwasniak.goldendiff.telemetry.BuildBadge
import com.github.dkwasniak.goldendiff.telemetry.PluginTelemetryService
import com.github.dkwasniak.goldendiff.telemetry.TelemetryAcceptFailure
import com.github.dkwasniak.goldendiff.telemetry.TelemetryAcceptMode
import com.github.dkwasniak.goldendiff.telemetry.TelemetryAcceptResult
import com.github.dkwasniak.goldendiff.telemetry.TelemetryBuckets
import com.github.dkwasniak.goldendiff.telemetry.TelemetryCommandKind
import com.github.dkwasniak.goldendiff.telemetry.TelemetryCommandResult
import com.github.dkwasniak.goldendiff.telemetry.TelemetryCommandScope
import com.github.dkwasniak.goldendiff.telemetry.TelemetrySpanStatus
import com.github.dkwasniak.goldendiff.telemetry.acceptCompleted
import com.github.dkwasniak.goldendiff.telemetry.commandCompleted
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonResult
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSources
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.AbstractAction
import javax.swing.AbstractButton
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * Snapshot-aware public-plugin Tool Window. It deliberately shares only models with the standalone
 * app; all rendering remains Swing/JBUI for the plugin's open-ended 241+ compatibility range.
 */
class RedesignedScreenshotPanel internal constructor(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val telemetry: PluginTelemetryReporter,
) : JPanel(BorderLayout()), Disposable {

    constructor(project: Project, toolWindow: ToolWindow) : this(project, toolWindow, PluginTelemetryReporter.live())

    private val settings = ScreenshotSettings.getInstance(project)
    private val projectRoot get() = project.basePath?.let(::File)
    private val controller = PluginSnapshotController(::renderState)
    private val goldenModel = DefaultListModel<GoldenGridItem>()
    private var cardWidth = JBUI.scale(60)
    private var gridColumns = 3
    private val goldenRenderer = MergedGoldenCellRenderer(project) { cardWidth }
    private val goldens = object : JBList<GoldenGridItem>(goldenModel) {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }.apply {
        cellRenderer = goldenRenderer
        layoutOrientation = JList.HORIZONTAL_WRAP
        visibleRowCount = -1
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellWidth = cardWidth
        fixedCellHeight = -1
        border = JBUI.Borders.empty(0, 10)
        accessibleContext.accessibleName = "Goldens"
        installGoldenArrowNavigation()
    }
    private val goldensScroll = JBScrollPane(goldens).apply { border = JBUI.Borders.empty() }

    private val files = SnapshotFilesPanel(
        onSelection = ::onFilesSelection,
        onOpen = ::openFilesSelection,
        onRevealActive = { controller.editorChanged(activeEditorPath()) },
        onCollapse = ::toggleFilesPane,
        onContextMenu = ::showFilesContextMenu,
    )
    private var comparisonGeneration = 0L
    private val resolvedAcceptCandidates = HashMap<String, GoldenAccept.FrozenCandidate>()
    private val compare = createCompareView(detachable = true)
    private var detachedWindow: JDialog? = null

    private val viewTitle = JBLabel("Select a View").apply {
        font = UIUtil.getLabelFont().deriveFont(
            java.awt.Font.BOLD,
            JBUI.scaleFontSize(12f).toFloat(),
        )
    }
    private val goldenCount = JBLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(10f).toFloat())
        foreground = UIUtil.getContextHelpForeground()
    }
    private val extraFilter = ExtraSourceFilterChip().apply {
        isVisible = false
        addActionListener { controller.setExtraOnly(isSelected) }
    }
    private val acceptAll = AcceptActionButton("", AcceptColor, pill = true).apply {
        isVisible = false
        addActionListener { acceptAllForSelectedView() }
    }
    private val gridColumnButtons = (1..3).map { columns ->
        GridColumnButton(columns).apply {
            isSelected = columns == gridColumns
            addActionListener { setGridColumns(columns) }
        }
    }
    private val gridColumnsPanel = object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(1), 0)) {
        override fun paintComponent(graphics: java.awt.Graphics) {
            val g = graphics.create() as java.awt.Graphics2D
            try {
                g.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
                )
                g.color = HeaderColor
                g.fillRoundRect(0, 0, width, height, JBUI.scale(10), JBUI.scale(10))
                g.color = BorderColor
                g.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(10), JBUI.scale(10))
            } finally {
                g.dispose()
            }
            super.paintComponent(graphics)
        }
    }.apply {
        isOpaque = false
        border = JBUI.Borders.empty(3, 4)
        val group = ButtonGroup()
        gridColumnButtons.forEach {
            group.add(it)
            add(it)
        }
    }
    private val generateProject = FlatHeaderButton("Generate", AllIcons.Actions.Execute).apply {
        addActionListener { runHeaderCommand(CommandKind.GENERATE) }
    }
    private val verifyProject = FlatHeaderButton("Verify", AllIcons.Actions.Checked).apply {
        addActionListener { runHeaderCommand(CommandKind.VERIFY) }
    }
    private val generateScope = scopeMenuButton("Choose Generate scope") { anchor ->
        showCommandScopeMenu(CommandKind.GENERATE, anchor)
    }
    private val verifyScope = scopeMenuButton("Choose Verify scope") { anchor ->
        showCommandScopeMenu(CommandKind.VERIFY, anchor)
    }
    private val settingsButton = iconButton(AllIcons.General.GearPlain, "Golden Diff settings") {
        ShowSettingsUtil.getInstance().editConfigurable(project, ScreenshotConfigurable(project))
        refresh(force = true, trigger = "config_change")
    }
    private val refreshButton = iconButton(AllIcons.Actions.Refresh, "Refresh snapshots") {
        refresh(force = true, trigger = "manual_refresh")
    }
    private val stopButton = JButton("Stop", AllIcons.Actions.Suspend).apply {
        makeCompactTextButton()
        isVisible = false
        addActionListener { activeBatch?.cancel() }
    }
    private val commandStatus = JBLabel().apply {
        foreground = AcceptColor
        isVisible = false
        border = JBUI.Borders.empty(2, 8)
    }
    private val statusBar = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.customLine(BorderColor, 1, 0, 0, 0)
        add(buildVersionFooter(), BorderLayout.WEST)
        add(commandStatus, BorderLayout.CENTER)
        add(buildBadgeFooter(), BorderLayout.EAST)
    }

    private val goldensPane = JPanel(BorderLayout()).apply {
        minimumSize = Dimension(JBUI.scale(120), 0)
        add(buildGoldensHeader(), BorderLayout.NORTH)
        add(
            FixedBottomRightOverlayPanel(goldensScroll, gridColumnsPanel, margin = 10),
            BorderLayout.CENTER,
        )
    }
    private val comparePane = JPanel(BorderLayout()).apply {
        minimumSize = Dimension(JBUI.scale(160), 0)
        add(compare, BorderLayout.CENTER)
    }
    private val center = JPanel(BorderLayout())
    private val narrowCards = JPanel(CardLayout())
    private val narrowTabs = JPanel(java.awt.GridLayout(1, 3))
    private var widthClass: WidthClass? = null
    private var narrowPane = Pane.FILES
    private var mediumFilesVisible = false
    private var filesOnBottom = false
    private var renderedFilesOnBottom = false
    private var filesHeightPx = JBUI.scale(160)
    private var filesHeightAdjusted = false
    private var expandedFilesHeightPx = filesHeightPx
    private var filesCollapsed = false
    private var goldensWidthPx = JBUI.scale(220)
    private var activeFilesSplitter: JBSplitter? = null
    private var activeGoldensSplitter: JBSplitter? = null
    private var applyingPixelSplitterSizes = false
    private var commandRunning = false
    private var generateTarget = CommandTarget.PROJECT
    private var verifyTarget = CommandTarget.PROJECT
    private var activeBatch: BatchCommandExecution.Handle? = null
    private var updatingGoldenModel = false
    private var nextSelectionTrigger = "automatic"
    private var lastReportedComparisonKey: String? = null
    private val vfsTimer = Timer(250) { refresh(force = true, trigger = "filesystem_change") }
        .apply { isRepeats = false }

    init {
        telemetry.panelOpened(project)
        border = JBUI.Borders.empty()
        add(buildHeader(), BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)

        goldens.addListSelectionListener { event ->
            if (event.valueIsAdjusting || updatingGoldenModel) return@addListSelectionListener
            val item = goldens.selectedValue ?: return@addListSelectionListener
            if (nextSelectionTrigger != "keyboard") nextSelectionTrigger = "grid"
            controller.selectGridItem(item.id)
            if (currentWidthClass() == WidthClass.NARROW) {
                narrowPane = Pane.COMPARE
                rebuildResponsiveLayout()
            }
        }
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val next = currentWidthClass()
                if (next != widthClass) {
                    rebuildResponsiveLayout()
                } else {
                    applyPixelSplitterSizes()
                }
            }
        })
        goldensScroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = updateGoldenGridGeometry()
        })
        subscribe()
        reportEditorSelection()
        rebuildResponsiveLayout()
        refresh(force = true, trigger = "automatic")
    }

    private fun buildHeader(): JPanel {
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            background = HeaderColor
            border = JBUI.Borders.customLine(BorderColor, 0, 0, 1, 0)
            minimumSize = Dimension(0, JBUI.scale(30))
            preferredSize = Dimension(0, JBUI.scale(30))
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), JBUI.scale(3))).apply {
                isOpaque = false
                add(commandScopeControl(generateProject, generateScope))
                add(commandScopeControl(verifyProject, verifyScope))
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(3))).apply {
                isOpaque = false
                add(stopButton)
                add(settingsButton)
                add(refreshButton)
            }
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }

    private fun createCompareView(detachable: Boolean): CompareView {
        val location = if (detachable) "main_pane" else "detached_window"
        return CompareView(
            onModeSelected = { mode ->
                telemetry.event(
                    "product.compare_mode_selected",
                    mapOf("mode" to mode, "location" to location),
                )
            },
            onZoomSelected = { zoom, action ->
                telemetry.event(
                    "product.zoom_selected",
                    mapOf("zoom" to zoom, "action" to action, "location" to location),
                )
            },
            onAccept = { controller.state.selectedGolden?.let(::acceptGolden) },
            onOpenSeparate = if (detachable) ::openDetachedComparison else null,
        )
    }

    private fun openDetachedComparison() {
        detachedWindow?.takeIf(Window::isDisplayable)?.let { window ->
            window.toFront()
            window.requestFocus()
            return
        }

        val detached = createCompareView(detachable = false)
        val owner = javax.swing.SwingUtilities.getWindowAncestor(this)
        val window = when (owner) {
            is Frame -> JDialog(owner)
            is Dialog -> JDialog(owner)
            else -> JDialog()
        }.apply {
            title = detachedWindowTitle()
            modalityType = Dialog.ModalityType.MODELESS
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            contentPane.layout = BorderLayout()
            contentPane.add(detached, BorderLayout.CENTER)
            minimumSize = Dimension(JBUI.scale(480), JBUI.scale(360))
            size = Dimension(JBUI.scale(1000), JBUI.scale(760))
            setLocationRelativeTo(owner)
            addWindowListener(
                object : WindowAdapter() {
                    override fun windowClosed(event: WindowEvent) {
                        compare.removeMirror(detached)
                        if (detachedWindow === event.window) {
                            detachedWindow = null
                        }
                    }
                },
            )
        }
        telemetry.event("product.feature_used", mapOf("feature" to "detached_comparison"))
        detachedWindow = window
        compare.addMirror(detached)
        window.isVisible = true
        loadSelectedComparison(reportViewed = false)
    }

    private fun detachedWindowTitle(): String =
        controller.state.selectedGridItem?.let {
            when (it) {
                is GoldenGridItem.BuiltIn -> it.item.golden.name
                is GoldenGridItem.Extension -> it.item.title
            }
        }?.let { "Golden Diff — $it" }
            ?: "Golden Diff — Compare"

    private fun buildGoldensHeader(): JPanel {
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 8, 2, 8)
            val title = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(viewTitle)
                add(goldenCount)
            }
            add(title, BorderLayout.CENTER)
        }
        val bottom = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 8, 5, 8)
            val filters = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(extraFilter)
            }
            val commands = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(3), 0)).apply {
                isOpaque = false
                add(acceptAll)
            }
            add(filters, BorderLayout.WEST)
            add(commands, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            background = HeaderColor
            border = JBUI.Borders.customLine(BorderColor, 0, 0, 1, 0)
            add(top, BorderLayout.NORTH)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    private fun buildVersionFooter(): JComponent {
        val service = PluginTelemetryService.getInstance()
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 8, 2, 0)
            add(JBLabel(service.appVersion).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(9f).toFloat())
            })
        }
    }

    private fun buildBadgeFooter(): JComponent {
        val service = PluginTelemetryService.getInstance()
        return JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 2, 8)
            service.metadata.badges.forEach { badge ->
                val color = when (badge) {
                    BuildBadge.DEV -> JBColor(Color(0xA9761A), Color(0xD9A441))
                    BuildBadge.BETA -> JBColor(Color(0x3574F0), Color(0x548AF7))
                }
                add(JBLabel(badge.label).apply {
                    foreground = color
                    font = UIUtil.getLabelFont().deriveFont(
                        java.awt.Font.BOLD,
                        JBUI.scaleFontSize(8f).toFloat(),
                    )
                    border = BorderFactory.createCompoundBorder(
                        RoundedLineBorder(color, JBUI.scale(5), 1),
                        JBUI.Borders.empty(0, 4),
                    )
                    accessibleContext.accessibleName = "${badge.label} build"
                })
            }
        }
    }

    private fun rebuildResponsiveLayout() {
        capturePixelSplitterSizes()
        activeFilesSplitter = null
        activeGoldensSplitter = null
        val target = currentWidthClass()
        widthClass = target
        detach(files)
        detach(goldensPane)
        detach(comparePane)
        // CardLayout.removeLayoutComponent auto-advances to the next card when the currently shown
        // one is removed, so detaching all three panes out of the NARROW card deck leaves at most one
        // of them visible=true and the rest permanently visible=false — plain container adds elsewhere
        // (BorderLayout/Splitter) never restore visibility on their own. Force it back before reuse.
        files.isVisible = true
        goldensPane.isVisible = true
        comparePane.isVisible = true
        center.removeAll()
        when (target) {
            WidthClass.WIDE -> {
                files.setCollapsePresentation(filesCollapsed, SnapshotFilesCollapsePresentation.WIDE)
                comparePane.minimumSize = Dimension(JBUI.scale(180), 0)
                goldensPane.minimumSize = Dimension(JBUI.scale(160), 0)
                val filesAndCompare = OnePixelSplitter(
                    true,
                    filesProportion().coerceIn(0.1f, 0.9f),
                    0.1f,
                    0.9f,
                ).apply {
                    setHonorComponentsMinimumSize(true)
                    firstComponent = if (filesOnBottom) comparePane else files
                    secondComponent = if (filesOnBottom) files else comparePane
                    installFilesPixelTracking(this)
                }
                activeFilesSplitter = filesAndCompare
                val goldensAndRest = OnePixelSplitter(
                    false,
                    goldensProportion().coerceIn(0.18f, 0.65f),
                    0.18f,
                    0.65f,
                ).apply {
                    setHonorComponentsMinimumSize(true)
                    firstComponent = goldensPane
                    secondComponent = filesAndCompare
                    installGoldensPixelTracking(this)
                }
                activeGoldensSplitter = goldensAndRest
                center.add(goldensAndRest, BorderLayout.CENTER)
            }
            WidthClass.MEDIUM -> {
                files.setCollapsePresentation(false, SnapshotFilesCollapsePresentation.OVERLAY)
                goldensPane.minimumSize = Dimension(JBUI.scale(120), 0)
                comparePane.minimumSize = Dimension(JBUI.scale(160), 0)
                val rail = JButton(AllIcons.Nodes.Folder).apply {
                    toolTipText = "Show Files"
                    accessibleContext.accessibleName = "Show Files"
                    preferredSize = Dimension(JBUI.scale(30), JBUI.scale(30))
                    addActionListener {
                        mediumFilesVisible = !mediumFilesVisible
                        rebuildResponsiveLayout()
                    }
                }
                val railPanel = JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.empty(4)
                        add(rail, BorderLayout.NORTH)
                }
                val split = OnePixelSplitter(
                    false,
                    goldensProportion().coerceIn(0.25f, 0.7f),
                    0.25f,
                    0.7f,
                ).apply {
                    setHonorComponentsMinimumSize(true)
                    firstComponent = goldensPane
                    secondComponent = comparePane
                    installGoldensPixelTracking(this)
                }
                activeGoldensSplitter = split
                val base = JPanel(BorderLayout()).apply {
                    add(railPanel, BorderLayout.WEST)
                    add(split, BorderLayout.CENTER)
                }
                center.add(
                    if (mediumFilesVisible) {
                        MediumFilesOverlayPanel(base, files) {
                            mediumFilesVisible = false
                            rebuildResponsiveLayout()
                        }
                    } else {
                        base
                    },
                    BorderLayout.CENTER,
                )
            }
            WidthClass.NARROW -> {
                goldensPane.minimumSize = Dimension(JBUI.scale(120), 0)
                narrowTabs.removeAll()
                Pane.entries.forEach { pane ->
                    narrowTabs.add(JToggleButton(pane.label).apply {
                        isSelected = pane == narrowPane
                        addActionListener {
                            narrowPane = pane
                            rebuildResponsiveLayout()
                        }
                    })
                }
                narrowCards.removeAll()
                narrowCards.add(files, Pane.FILES.name)
                narrowCards.add(goldensPane, Pane.GOLDENS.name)
                narrowCards.add(comparePane, Pane.COMPARE.name)
                (narrowCards.layout as CardLayout).show(narrowCards, narrowPane.name)
                center.add(narrowTabs, BorderLayout.NORTH)
                center.add(narrowCards, BorderLayout.CENTER)
            }
        }
        center.revalidate()
        center.repaint()
        renderedFilesOnBottom = filesOnBottom
        ApplicationManager.getApplication().invokeLater {
            applyPixelSplitterSizes()
            updateGoldenGridGeometry()
        }
    }

    private fun refresh(force: Boolean = false, trigger: String = "automatic") {
        val started = System.nanoTime()
        if (!settings.isConfigured) {
            controller.setConfigured(false)
            emitScan(emptyList(), trigger, started, "blocked", "no_configuration")
            return
        }
        val root = projectRoot ?: return
        val generation = controller.beginRefresh()
        val active = activeEditorPath()
        val extraGeneration = controller.beginExtraRefresh(active)
        files.setIndexing(controller.state.index != null)
        val config = settings.toConfig()
        val scanSpan = telemetry.startSpan(
            "golden.scan",
            mapOf("scope" to "project_tree", "source" to "merged"),
        )
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching {
                val projectIndex = ProjectFileIndex.scan(root)
                val scanner = ChangeScanner.forGitProject(root, config)
                val goldens = GoldenFinder.findAll(config.resolvedGoldenPaths(root), config.excludedSuffixes)
                val merged = scanner.mergedItemsFor(goldens)
                val screen = CurrentScreen.compute(project, settings.annotatedFunctionRegex)
                val extras = if (screen == null || active == null) {
                    emptyList()
                } else {
                    ExtraComparisonSources.all.flatMap { source ->
                        source.findItems(project, screen, settings).map { item ->
                            GoldenGridItem.Extension(source, item, screen)
                        }
                    }
                }
                Triple(SnapshotFileIndex.build(projectIndex, config, merged), merged, extras)
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || generation != controller.state.generation) {
                    scanSpan.finish(TelemetrySpanStatus.CANCELLED)
                    return@invokeLater
                }
                result.onSuccess { (index, merged, extras) ->
                    controller.completeRefresh(generation, index, merged, active)
                    controller.completeExtraRefresh(extraGeneration, active, extras)
                    scanSpan.finish()
                    emitScan(
                        merged,
                        trigger,
                        started,
                        if (merged.isEmpty()) "success_empty" else "success_nonempty",
                        "none",
                    )
                }.onFailure {
                    scanSpan.finish(TelemetrySpanStatus.ERROR)
                    telemetry.reportFailure("scan", it, projectRoot)
                    controller.failRefresh(generation, it)
                    emitScan(emptyList(), trigger, started, "failure", "none")
                }
            }
        }
    }

    private fun emitScan(
        items: List<MergedGoldenItem>,
        trigger: String,
        started: Long,
        result: String,
        blocker: String,
    ) {
        telemetry.event(
            "product.scan_completed",
            mapOf(
                "trigger" to trigger,
                "scope" to "project_tree",
                "source" to "merged",
                "result" to result,
                "blocker" to blocker,
                "duration_bucket" to TelemetryBuckets.duration((System.nanoTime() - started) / 1_000_000),
                "item_count_bucket" to TelemetryBuckets.count(items.size),
                "modified_count_bucket" to TelemetryBuckets.count(
                    items.count { it.displayStatus == MergedGoldenStatus.MODIFIED },
                ),
                "new_count_bucket" to TelemetryBuckets.count(
                    items.count { it.displayStatus == MergedGoldenStatus.NEW },
                ),
                "failed_count_bucket" to TelemetryBuckets.count(
                    items.count { it.displayStatus == MergedGoldenStatus.FAILED },
                ),
                "cache_hit" to "false",
            ),
        )
    }

    private fun renderState(state: PluginSnapshotState) {
        detachedWindow?.title = detachedWindowTitle()
        val selection = when {
            state.selection.viewId == null -> null
            state.selection.caseId != null ->
                SnapshotFilesSelection.Case(state.selection.viewId, state.selection.caseId)
            else -> SnapshotFilesSelection.View(state.selection.viewId)
        }
        files.update(
            index = state.index,
            activePath = state.activeEditorPath,
            selection = selection,
            indexing = state.loadState == SnapshotLoadState.INITIAL_INDEXING ||
                state.loadState == SnapshotLoadState.REFRESHING,
        )
        val selected = state.visibleGridItems
        val selectedId = state.selectedGridId
        updatingGoldenModel = true
        try {
            goldenModel.clear()
            selected.forEach(goldenModel::addElement)
            val selectionIndex = selected.indexOfFirst { it.id == selectedId }
            if (selectionIndex >= 0) goldens.selectedIndex = selectionIndex
            else goldens.clearSelection()
        } finally {
            updatingGoldenModel = false
        }
        viewTitle.text = state.selectedCase?.methodName
            ?: state.selectedView?.relativePath?.let { File(it).nameWithoutExtension }
            ?: when (state.loadState) {
                SnapshotLoadState.UNCONFIGURED -> "No configuration"
                SnapshotLoadState.INITIAL_INDEXING -> "Indexing snapshots…"
                SnapshotLoadState.EMPTY -> "No snapshot-related files"
                SnapshotLoadState.ERROR -> "Snapshot index unavailable"
                else -> "Select a View"
            }
        goldenCount.text = if (selected.isEmpty()) "" else "${selected.size} golden${if (selected.size == 1) "" else "s"}"
        val filterSource = state.extraFilterSource
        extraFilter.configure(filterSource?.title, filterSource?.filterIcon, state.extraOnly)
        val acceptCount = state.selectedView?.goldenPaths.orEmpty()
            .mapNotNull { path -> state.mergedItems.firstOrNull { it.golden.normalize().path == path } }
            .count(MergedGoldenItem::hasAcceptableTestOutput)
        acceptAll.text = "Accept all ($acceptCount)"
        acceptAll.isVisible = acceptCount > 0
        updateCommandButtons()
        if (state.selectedGridItem != null) loadSelectedComparison() else clearComparison(state)
    }

    private fun clearComparison(state: PluginSnapshotState) {
        compare.setTitle(null)
        if (state.loadState == SnapshotLoadState.UNCONFIGURED) {
            compare.showBlocked(
                "No configuration",
                "Choose at least one golden directory to start indexing snapshots.",
                "Open Settings",
            ) {
                ShowSettingsUtil.getInstance().editConfigurable(project, ScreenshotConfigurable(project))
                refresh(force = true, trigger = "config_change")
            }
            return
        }
        val message = when (state.loadState) {
            SnapshotLoadState.UNCONFIGURED -> error("handled above")
            SnapshotLoadState.INITIAL_INDEXING -> "Indexing snapshots…"
            SnapshotLoadState.ERROR -> state.errorMessage ?: "Snapshot indexing failed."
            SnapshotLoadState.EMPTY -> "No snapshot-related files."
            else -> if (!state.activeFileHasSnapshots) {
                "The active file has no associated snapshots. Select a View in Files."
            } else {
                "Select a golden to compare."
            }
        }
        compare.showSingle(null, message)
    }

    private fun loadSelectedComparison(reportViewed: Boolean = true) {
        when (val item = controller.state.selectedGridItem ?: return) {
            is GoldenGridItem.BuiltIn -> loadBuiltInComparison(item.item, reportViewed)
            is GoldenGridItem.Extension -> loadExtensionComparison(item)
        }
    }

    private fun loadBuiltInComparison(item: MergedGoldenItem, reportViewed: Boolean) {
        val generation = ++comparisonGeneration
        val source = automaticBuiltInSource(item)
        val variant = item.variant(source)
        val sourceWire = if (source == BuiltInSource.GENERATED) "test_output" else "working_copy"
        val comparisonKey = "${item.golden.normalize().path}:$sourceWire"
        val shouldReport = reportViewed && comparisonKey != lastReportedComparisonKey
        val selectionTrigger = nextSelectionTrigger
        nextSelectionTrigger = "automatic"
        val started = System.nanoTime()
        val comparisonSpan = telemetry.startSpan(
            "comparison.load",
            mapOf("scope" to "project_tree", "source" to sourceWire, "cache_hit" to "false"),
        )
        compare.setTitle(item.golden.name)
        val ambiguity = item.testOutputResolution as? GeneratedCounterpartResolution.Ambiguous
        if (ambiguity != null) {
            comparisonSpan.finish()
            compare.showBlocked(
                "Ambiguous Test output",
                "${ambiguity.candidates.size} generated files map to this golden. " +
                    "Comparison and Accept are blocked until the mapping is unambiguous.",
                "Open Settings",
            ) {
                ShowSettingsUtil.getInstance().editConfigurable(project, ScreenshotConfigurable(project))
            }
            return
        }
        if (variant == null) {
            comparisonSpan.finish()
            compare.showBlocked(
                "No comparison source",
                "Neither a Working copy nor a Test output is available for this golden yet.",
            )
            return
        }
        compare.showLoading("Loading comparison…")
        val trim = settings.trimTransparentPadding
        val root = projectRoot ?: return
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching {
                val frozen = if (source == BuiltInSource.GENERATED && item.hasAcceptableTestOutput) {
                    GoldenAccept.freeze(item.golden, variant.file).getOrNull()
                } else {
                    null
                }
                val gitSpan = telemetry.startSpan(
                    "git.head_read",
                    mapOf("scope" to "project_tree", "source" to sourceWire),
                )
                val headRaw = try {
                    GitCli(root).headBytes(item.golden)
                } finally {
                    gitSpan.finish()
                }
                val sourceRaw = ImageBytes.workingBytes(variant.file)
                val decodeSpan = telemetry.startSpan(
                    "image.decode",
                    mapOf("scope" to "project_tree", "source" to sourceWire),
                )
                val old: java.awt.image.BufferedImage?
                val new: java.awt.image.BufferedImage?
                try {
                    old = ImageBytes.decode(headRaw)?.let { ImagePainting.trimTransparentBorder(it, trim) }
                    new = ImageBytes.decode(sourceRaw)?.let { ImagePainting.trimTransparentBorder(it, trim) }
                } finally {
                    decodeSpan.finish()
                }
                val diffRatio = if (
                    shouldReport &&
                    telemetry.client.consent.analytics &&
                    old != null &&
                    new != null &&
                    !headRaw.contentEquals(sourceRaw)
                ) {
                    val diffSpan = telemetry.startSpan(
                        "pixel_diff.compute",
                        mapOf("scope" to "project_tree", "source" to sourceWire),
                    )
                    try {
                        PixelDiff.compute(old.toArgbImage(), new.toArgbImage())?.changedRatio
                    } finally {
                        diffSpan.finish()
                    }
                } else if (headRaw != null && sourceRaw != null && headRaw.contentEquals(sourceRaw)) {
                    0.0
                } else {
                    null
                }
                LoadedBuiltInComparison(frozen, headRaw, sourceRaw, old, new, diffRatio)
            }
            ApplicationManager.getApplication().invokeLater {
                if (generation != comparisonGeneration || project.isDisposed) {
                    comparisonSpan.finish(TelemetrySpanStatus.CANCELLED)
                    return@invokeLater
                }
                result.onFailure {
                    comparisonSpan.finish(TelemetrySpanStatus.ERROR)
                    telemetry.reportFailure("comparison_load", it, projectRoot)
                    compare.showRetry("Could not load comparison.") { loadSelectedComparison() }
                    return@invokeLater
                }
                val loaded = result.getOrThrow()
                comparisonSpan.finish()
                val goldenId = item.golden.normalize().path
                if (loaded.frozen == null) resolvedAcceptCandidates.remove(goldenId)
                else resolvedAcceptCandidates[goldenId] = loaded.frozen
                when {
                    loaded.sourceRaw == null -> compare.showBlocked(
                        "No comparison source",
                        "The automatically selected source is no longer available. Refresh to update its status.",
                    )
                    loaded.headRaw == null -> compare.showSingle(loaded.new, "New — no Git HEAD version")
                    loaded.headRaw.contentEquals(loaded.sourceRaw) ->
                        compare.showSingle(loaded.new, "Unchanged — identical to Git HEAD")
                    else -> compare.showComparison(
                        loaded.old,
                        loaded.new,
                        "${item.golden.name}: Git HEAD \u2194 ${if (source == BuiltInSource.GENERATED) "Test output" else "Working copy"}",
                        "HEAD",
                        if (source == BuiltInSource.GENERATED) "Test output" else "Working copy",
                    )
                }
                if (source == BuiltInSource.GENERATED && item.hasAcceptableTestOutput) compare.showAccept()
                if (shouldReport) {
                    val emitted = telemetry.event(
                        "product.comparison_viewed",
                        mapOf(
                            "source" to sourceWire,
                            "result" to when {
                                loaded.headRaw == null && loaded.sourceRaw == null -> "decode_failed"
                                loaded.sourceRaw == null -> "missing_counterpart"
                                loaded.headRaw == null -> "new"
                                loaded.headRaw.contentEquals(loaded.sourceRaw) -> "identical"
                                else -> "modified"
                            },
                            "load_duration_bucket" to TelemetryBuckets.duration(
                                (System.nanoTime() - started) / 1_000_000,
                            ),
                            "diff_ratio_bucket" to TelemetryBuckets.diffRatio(
                                loaded.diffRatio,
                                loaded.old != null && loaded.new != null,
                            ),
                            "dimensions" to when {
                                loaded.old == null || loaded.new == null -> "one_missing"
                                loaded.old.width == loaded.new.width && loaded.old.height == loaded.new.height -> "same"
                                else -> "different"
                            },
                            "cache_hit" to "false",
                            "selection_trigger" to selectionTrigger,
                            "scope" to "project_tree",
                        ),
                    )
                    if (emitted) {
                        lastReportedComparisonKey = comparisonKey
                        if (loaded.sourceRaw != null) telemetry.activationCompleted(sourceWire)
                    }
                }
            }
        }
    }

    private fun loadExtensionComparison(item: GoldenGridItem.Extension) {
        val generation = ++comparisonGeneration
        compare.setTitle(item.item.title)
        compare.showLoading(
            if (item.item.isLoading) "Loading ${item.source.title}…" else "Loading comparison…",
        )
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching {
                item.source.loadComparison(project, item.item.file, item.screen, settings)
            }
            ApplicationManager.getApplication().invokeLater {
                if (generation != comparisonGeneration || project.isDisposed) return@invokeLater
                result.onFailure { error ->
                    compare.showRetry(
                        error.message ?: "${item.source.title} comparison failed.",
                    ) { loadSelectedComparison() }
                }.onSuccess { comparison ->
                    if (item.item.isLoading && item.item.file.isFile) {
                        goldenRenderer.clear(item.item.file)
                        controller.markExtensionLoaded(item.id)
                        return@onSuccess
                    }
                    when (comparison) {
                        is ExtraComparisonResult.Comparison -> compare.showComparison(
                            comparison.old,
                            comparison.new,
                            comparison.statusText,
                            comparison.oldLabel,
                            comparison.newLabel,
                        )
                        is ExtraComparisonResult.Single -> {
                            if (comparison.image == null) {
                                compare.showRetry(comparison.statusText) { loadSelectedComparison() }
                            } else {
                                compare.showSingle(comparison.image, comparison.statusText)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun refreshExtraItems(active: String? = activeEditorPath()) {
        val generation = controller.beginExtraRefresh(active)
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching {
                val screen = CurrentScreen.compute(project, settings.annotatedFunctionRegex)
                if (screen == null || active == null) {
                    emptyList()
                } else {
                    ExtraComparisonSources.all.flatMap { source ->
                        source.findItems(project, screen, settings).map { item ->
                            GoldenGridItem.Extension(source, item, screen)
                        }
                    }
                }
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                result.onSuccess { controller.completeExtraRefresh(generation, active, it) }
            }
        }
    }

    private fun onFilesSelection(selection: SnapshotFilesSelection) {
        nextSelectionTrigger = "automatic"
        when (selection) {
            is SnapshotFilesSelection.View -> controller.selectView(selection.sourcePath)
            is SnapshotFilesSelection.Case -> controller.selectCase(selection.sourcePath, selection.caseId)
        }
        fileForSelection(selection)?.let { file ->
            val virtual = LocalFileSystem.getInstance().findFileByIoFile(file)
            telemetry.sourceFileSelected(
                memoryKey = file.normalize().path,
                trigger = "project_tree",
                fileFamily = fileFamily(file.extension),
                alreadyOpen = virtual?.let { FileEditorManager.getInstance(project).isFileOpen(it) } == true,
            )
        }
        if (currentWidthClass() == WidthClass.NARROW) {
            narrowPane = Pane.GOLDENS
            rebuildResponsiveLayout()
        }
    }

    private fun openFilesSelection(selection: SnapshotFilesSelection) {
        val relativePath = when (selection) {
            is SnapshotFilesSelection.View -> selection.sourcePath
            is SnapshotFilesSelection.Case ->
                controller.state.selectedView?.cases?.firstOrNull { it.id == selection.caseId }?.generatorPath
                    ?: selection.sourcePath
        }
        val file = projectRoot?.let { File(it, relativePath) } ?: return
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let {
            FileEditorManager.getInstance(project).openFile(it, true)
        }
    }

    private fun toggleFilesPane() {
        when (currentWidthClass()) {
            WidthClass.WIDE -> {
                if (filesCollapsed) {
                    filesHeightPx = expandedFilesHeightPx.coerceIn(JBUI.scale(80), JBUI.scale(420))
                    filesCollapsed = false
                } else {
                    expandedFilesHeightPx = filesHeightPx.coerceAtLeast(JBUI.scale(160))
                    filesHeightPx = JBUI.scale(80)
                    filesCollapsed = true
                }
                filesHeightAdjusted = true
                files.setCollapsePresentation(filesCollapsed, SnapshotFilesCollapsePresentation.WIDE)
                applyPixelSplitterSizes()
            }
            WidthClass.MEDIUM -> {
                mediumFilesVisible = false
                rebuildResponsiveLayout()
            }
            WidthClass.NARROW -> Unit
        }
    }

    private fun showFilesContextMenu(
        selection: SnapshotFilesSelection,
        component: Component,
        x: Int,
        y: Int,
    ) {
        val target = fileForSelection(selection) ?: return
        val isCase = selection is SnapshotFilesSelection.Case
        val popup = StyledContextMenu()
        popup.addHeader(if (isCase) controller.state.selectedCase?.methodName.orEmpty() else target.name)
        popup.addSectionSeparator()
        if (commandRunning) {
            popup.addAction("Show command log") {
                ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.RUN)?.activate(null)
            }
        } else {
            val config = settings.toConfig()
            if (config.generateFileCommand.isNotBlank()) {
                popup.addAction(
                    "Generate for ${if (isCase) "method" else "file"}",
                    AllIcons.Actions.Execute,
                ) { runScopedCommand(CommandKind.GENERATE) }
            }
            val verifyAvailable = if (isCase) {
                config.verifyCaseCommand.isNotBlank() || config.verifyFileCommand.isNotBlank()
            } else {
                config.verifyFileCommand.isNotBlank()
            }
            if (verifyAvailable) {
                popup.addAction(
                    "Verify for ${if (isCase) "method" else "file"}",
                    AllIcons.Actions.Compile,
                ) { runScopedCommand(CommandKind.VERIFY) }
            }
        }
        popup.addSectionSeparator()
        popup.addAction("Show in File Manager", AllIcons.Actions.MenuOpen) {
            telemetry.event("product.feature_used", mapOf("feature" to "reveal_in_file_manager"))
            RevealFileAction.openFile(target)
        }
        popup.addAction("Copy Absolute Path", AllIcons.Actions.Copy) {
            telemetry.event("product.feature_used", mapOf("feature" to "copy_path"))
            CopyPasteManager.getInstance().setContents(StringSelection(target.absolutePath))
        }
        popup.show(component, x, y)
    }

    private fun fileForSelection(selection: SnapshotFilesSelection): File? {
        val root = projectRoot ?: return null
        val relativePath = when (selection) {
            is SnapshotFilesSelection.View -> selection.sourcePath
            is SnapshotFilesSelection.Case -> controller.state.index?.files
                ?.firstOrNull { it.relativePath == selection.sourcePath }
                ?.cases
                ?.firstOrNull { it.id == selection.caseId }
                ?.generatorPath
                ?: return null
        }
        return File(root, relativePath)
    }

    private fun acceptGolden(item: MergedGoldenItem) {
        val generated = item.testOutput?.takeIf { item.hasAcceptableTestOutput }?.file ?: return
        val frozen = resolvedAcceptCandidates[item.golden.normalize().path] ?: run {
            telemetry.client.acceptCompleted(
                TelemetryAcceptMode.SINGLE,
                TelemetryAcceptResult.FAILURE,
                TelemetryAcceptFailure.STALE,
                candidates = 1,
                accepted = 0,
                failed = 1,
            )
            compare.showBlocked(
                "Test output changed",
                "Refresh the comparison before accepting this Test output.",
                "Refresh",
            ) { refresh(force = true, trigger = "manual_refresh") }
            return
        }
        compare.showAccepting()
        AppExecutorUtil.getAppExecutorService().execute {
            val result = GoldenAccept.acceptFrozen(frozen)
            ApplicationManager.getApplication().invokeLater {
                val accepted = if (result is GoldenAccept.ResolvedOutcome.Success) 1 else 0
                telemetry.client.acceptCompleted(
                    TelemetryAcceptMode.SINGLE,
                    when (result) {
                        is GoldenAccept.ResolvedOutcome.Success -> TelemetryAcceptResult.SUCCESS
                        is GoldenAccept.ResolvedOutcome.NothingToAccept -> TelemetryAcceptResult.NOTHING_TO_ACCEPT
                        else -> TelemetryAcceptResult.FAILURE
                    },
                    when (result) {
                        is GoldenAccept.ResolvedOutcome.Success,
                        is GoldenAccept.ResolvedOutcome.NothingToAccept,
                        -> TelemetryAcceptFailure.NONE
                        is GoldenAccept.ResolvedOutcome.Ambiguous -> TelemetryAcceptFailure.AMBIGUOUS
                        is GoldenAccept.ResolvedOutcome.StaleSource -> TelemetryAcceptFailure.STALE
                        is GoldenAccept.ResolvedOutcome.Io -> TelemetryAcceptFailure.IO
                    },
                    candidates = 1,
                    accepted = accepted,
                    failed = 1 - accepted,
                )
                telemetry.event(
                    "product.feature_used",
                    mapOf(
                        "feature" to if (accepted == 1) "accept_golden" else "accept_golden_failed",
                    ),
                )
                when (result) {
                    is GoldenAccept.ResolvedOutcome.Success -> {
                        resolvedAcceptCandidates.remove(item.golden.normalize().path)
                        LocalFileSystem.getInstance().refreshIoFiles(listOf(item.golden, generated))
                        refresh(force = true, trigger = "command_finished")
                    }
                    is GoldenAccept.ResolvedOutcome.NothingToAccept ->
                        compare.showSingle(null, "Nothing to accept")
                    is GoldenAccept.ResolvedOutcome.Ambiguous ->
                        compare.showBlocked(
                            "Ambiguous Test output",
                            "More than one generated file maps to this golden. Resolve the mapping in Settings.",
                        )
                    is GoldenAccept.ResolvedOutcome.StaleSource ->
                        compare.showBlocked(
                            "Test output changed",
                            "The displayed Test output changed or moved. Refresh before accepting.",
                            "Refresh",
                        ) { refresh(force = true, trigger = "manual_refresh") }
                    is GoldenAccept.ResolvedOutcome.Io -> {
                        compare.showAccept()
                        Messages.showErrorDialog(
                            project,
                            "Could not accept ${item.golden.name}. The Test output was left untouched.",
                            "Accept Test Output",
                        )
                    }
                }
            }
        }
    }

    private fun acceptAllForSelectedView() {
        val view = controller.state.selectedView ?: return
        val candidates = view.goldenPaths.mapNotNull { path ->
            controller.state.mergedItems.firstOrNull {
                it.golden.normalize().path == path && it.hasAcceptableTestOutput
            }
        }
        if (candidates.isEmpty()) return
        val answer = Messages.showYesNoDialog(
            project,
            "Accept all Test outputs for ${File(view.relativePath).nameWithoutExtension}?\n\n" +
                "${candidates.size} snapshot${if (candidates.size == 1) "" else "s"} will be promoted.",
            "Accept All Test Outputs",
            "Accept all (${candidates.size})",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        commandStatus.text = "Accepting 0 of ${candidates.size}…"
        commandStatus.isVisible = true
        val displayedCandidates = resolvedAcceptCandidates.toMap()
        AppExecutorUtil.getAppExecutorService().execute {
            val frozen = candidates.map { item ->
                val key = item.golden.normalize().path
                displayedCandidates[key]
                    ?: item.testOutput?.file?.let { GoldenAccept.freeze(item.golden, it).getOrNull() }
                    ?: GoldenAccept.FrozenCandidate(item.golden, GoldenAccept.FrozenSource.Missing)
            }
            val result = GoldenAccept.acceptAllResolved(frozen) { done, total ->
                ApplicationManager.getApplication().invokeLater {
                    commandStatus.text = "Accepting $done of $total…"
                }
            }
            ApplicationManager.getApplication().invokeLater {
                commandStatus.isVisible = false
                val failures = result.items.count { it.outcome !is GoldenAccept.ResolvedOutcome.Success }
                val nothingToAccept = result.items.count {
                    it.outcome is GoldenAccept.ResolvedOutcome.NothingToAccept
                }
                val dominantFailure = result.items.asSequence()
                    .map { it.outcome }
                    .firstOrNull { it !is GoldenAccept.ResolvedOutcome.Success }
                telemetry.client.acceptCompleted(
                    TelemetryAcceptMode.BULK,
                    when {
                        result.accepted.size == candidates.size -> TelemetryAcceptResult.SUCCESS
                        result.accepted.isNotEmpty() -> TelemetryAcceptResult.PARTIAL
                        nothingToAccept == candidates.size -> TelemetryAcceptResult.NOTHING_TO_ACCEPT
                        else -> TelemetryAcceptResult.FAILURE
                    },
                    when (dominantFailure) {
                        null, is GoldenAccept.ResolvedOutcome.Success,
                        is GoldenAccept.ResolvedOutcome.NothingToAccept,
                        -> TelemetryAcceptFailure.NONE
                        is GoldenAccept.ResolvedOutcome.Ambiguous -> TelemetryAcceptFailure.AMBIGUOUS
                        is GoldenAccept.ResolvedOutcome.StaleSource -> TelemetryAcceptFailure.STALE
                        is GoldenAccept.ResolvedOutcome.Io -> TelemetryAcceptFailure.IO
                    },
                    candidates = candidates.size,
                    accepted = result.accepted.size,
                    failed = failures,
                )
                LocalFileSystem.getInstance().refreshIoFiles(
                    candidates.flatMap { listOfNotNull(it.golden, it.testOutput?.file) },
                )
                refresh(force = true, trigger = "command_finished")
                if (failures > 0) {
                    Messages.showWarningDialog(
                        project,
                        "${result.accepted.size} of ${candidates.size} accepted. " +
                            "$failures could not be written or had changed.",
                        "Accept All Partially Completed",
                    )
                }
            }
        }
    }

    private fun runProjectCommand(kind: CommandKind) {
        val config = settings.toConfig()
        val command = if (kind == CommandKind.GENERATE) config.generateAllCommand else config.verifyAllCommand
        runCommand(kind, TelemetryCommandScope.PROJECT, "whole project", command)
    }

    private fun runHeaderCommand(kind: CommandKind) {
        if (commandRunning) {
            ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.RUN)?.activate(null)
            return
        }
        val target = if (kind == CommandKind.GENERATE) generateTarget else verifyTarget
        when (target) {
            CommandTarget.PROJECT -> runProjectCommand(kind)
            CommandTarget.CURRENT -> runScopedCommand(kind)
        }
    }

    private fun showCommandScopeMenu(kind: CommandKind, anchor: JComponent) {
        val config = settings.toConfig()
        val popup = StyledContextMenu()
        val projectCommand = if (kind == CommandKind.GENERATE) {
            config.generateAllCommand
        } else {
            config.verifyAllCommand
        }
        popup.addAction(
            text = "Whole project",
            enabled = projectCommand.isNotBlank() && !commandRunning,
        ) {
                setCommandTarget(kind, CommandTarget.PROJECT)
                runProjectCommand(kind)
        }

        val view = controller.state.selectedView
        val snapshotCase = controller.state.selectedCase
        val currentLabel = when {
            kind == CommandKind.VERIFY && snapshotCase != null -> "Current case: ${snapshotCase.methodName}"
            view != null -> "Current file: ${File(view.relativePath).name}"
            else -> "Current file"
        }
        val currentConfigured = when (kind) {
            CommandKind.GENERATE -> config.generateFileCommand.isNotBlank()
            CommandKind.VERIFY -> snapshotCase?.let {
                config.verifyCaseCommand.isNotBlank() || config.verifyFileCommand.isNotBlank()
            } ?: config.verifyFileCommand.isNotBlank()
        }
        popup.addAction(
            text = currentLabel,
            enabled = view != null && currentConfigured && !commandRunning,
        ) {
                setCommandTarget(kind, CommandTarget.CURRENT)
                runScopedCommand(kind)
        }
        popup.show(anchor, 0, anchor.height)
    }

    private fun setCommandTarget(kind: CommandKind, target: CommandTarget) {
        if (kind == CommandKind.GENERATE) generateTarget = target else verifyTarget = target
        updateCommandButtons()
    }

    private fun runScopedCommand(kind: CommandKind) {
        val view = controller.state.selectedView ?: return
        val case = controller.state.selectedCase
        val config = settings.toConfig()
        val sourceFile = File(projectRoot, view.relativePath)
        val screen = GenericScreenExtractor.extract(
            sourceFile.nameWithoutExtension,
            runCatching { sourceFile.readText() }.getOrDefault(""),
            settings.annotatedFunctionRegex,
            includeTestFunctions = true,
        )
        val template = when {
            kind == CommandKind.GENERATE -> config.generateFileCommand
            case != null && config.verifyCaseCommand.isNotBlank() -> config.verifyCaseCommand
            else -> config.verifyFileCommand
        }
        val generatorPath = case?.generatorPath ?: view.relativePath
        val snapshot = case?.let {
            CommandTemplate.SnapshotContext(
                fileName = File(it.generatorPath).nameWithoutExtension,
                className = it.className,
                filePath = it.generatorPath.substringBeforeLast('.'),
                methodName = it.methodName,
            )
        }
        val expanded = CommandTemplate.expand(
            template,
            screen,
            view.relativePath.substringBeforeLast('.'),
            snapshot,
        )
        val scope = case?.let { "Case ${it.methodName}" }
            ?: "View ${File(view.relativePath).nameWithoutExtension}"
        val telemetryScope = if (case == null) TelemetryCommandScope.VIEW else TelemetryCommandScope.CASE
        if (kind == CommandKind.VERIFY && case == null && view.cases.isNotEmpty()) {
            val commands = view.cases.map { snapshotCase ->
                val context = CommandTemplate.SnapshotContext(
                    fileName = File(snapshotCase.generatorPath).nameWithoutExtension,
                    className = snapshotCase.className,
                    filePath = snapshotCase.generatorPath.substringBeforeLast('.'),
                    methodName = snapshotCase.methodName,
                )
                CommandTemplate.expand(
                    config.verifyFileCommand,
                    screen,
                    view.relativePath.substringBeforeLast('.'),
                    context,
                )
            }.filter(String::isNotBlank).distinct()
            runCommandBatch(kind, telemetryScope, scope, commands)
        } else {
            runCommand(kind, telemetryScope, scope, expanded)
        }
    }

    private fun runCommandBatch(
        kind: CommandKind,
        telemetryScope: TelemetryCommandScope,
        scope: String,
        commands: List<String>,
    ) {
        if (commands.size <= 1) {
            runCommand(kind, telemetryScope, scope, commands.firstOrNull().orEmpty())
            return
        }
        if (commandRunning) return
        val root = projectRoot ?: return
        commandRunning = true
        commandStatus.text = "${kind.label} · $scope · ${commands.size} commands"
        commandStatus.isVisible = true
        telemetry.event(
            "product.feature_used",
            mapOf("feature" to if (kind == CommandKind.GENERATE) "generate_run" else "verify_run"),
        )
        updateCommandButtons()
        activeBatch = BatchCommandExecution.run(
            project = project,
            commands = commands,
            workingDir = root,
            title = "Golden Diff — ${kind.label} $scope",
            onCompleted = { outcome ->
                ApplicationManager.getApplication().invokeLater {
                    commandRunning = false
                    activeBatch = null
                    commandStatus.text = when (outcome) {
                        is BatchCommandOutcome.Success -> "${kind.label} completed"
                        is BatchCommandOutcome.Failure -> "${kind.label} failed"
                        is BatchCommandOutcome.PartialFailure -> "${kind.label} partially failed"
                        is BatchCommandOutcome.Cancelled -> "${kind.label} cancelled"
                    }
                    val result = when (outcome) {
                        is BatchCommandOutcome.Success -> CompletionResult.SUCCESS
                        is BatchCommandOutcome.Cancelled -> CompletionResult.CANCELLED
                        else -> CompletionResult.FAILURE
                    }
                    telemetry.client.commandCompleted(
                        kind.telemetryKind(),
                        telemetryScope,
                        when (outcome) {
                            is BatchCommandOutcome.Success -> TelemetryCommandResult.SUCCESS
                            is BatchCommandOutcome.PartialFailure -> TelemetryCommandResult.PARTIAL_FAILURE
                            is BatchCommandOutcome.Failure -> TelemetryCommandResult.FAILURE
                            is BatchCommandOutcome.Cancelled -> TelemetryCommandResult.CANCELLED
                        },
                        commands.size,
                    )
                    notifyCommand(kind, scope, result)
                    updateCommandButtons()
                    LocalFileSystem.getInstance().refreshIoFiles(settings.resolvedGeneratedPaths(project))
                    refresh(force = true, trigger = "command_finished")
                    Timer(4000) { commandStatus.isVisible = false }.apply {
                        isRepeats = false
                        start()
                    }
                }
            },
        )
        if (activeBatch == null) {
            commandRunning = false
            telemetry.client.commandCompleted(
                kind.telemetryKind(),
                telemetryScope,
                TelemetryCommandResult.COULD_NOT_START,
                commands.size,
            )
            updateCommandButtons()
        }
    }

    private fun runCommand(
        kind: CommandKind,
        telemetryScope: TelemetryCommandScope,
        scope: String,
        command: String,
    ) {
        if (commandRunning) return
        if (command.isBlank()) {
            Messages.showInfoMessage(
                project,
                "Configure the ${kind.label} command for $scope in Settings.",
                "${kind.label} Not Configured",
            )
            return
        }
        val root = projectRoot ?: return
        commandRunning = true
        stopButton.isVisible = false
        commandStatus.text = "${kind.label} · $scope"
        commandStatus.isVisible = true
        telemetry.event(
            "product.feature_used",
            mapOf("feature" to if (kind == CommandKind.GENERATE) "generate_run" else "verify_run"),
        )
        updateCommandButtons()
        val completionReported = AtomicBoolean()
        val started = CommandExecution.run(
            project,
            command,
            root,
            "Golden Diff — ${kind.label} $scope",
            onTerminated = {
                ApplicationManager.getApplication().invokeLater {
                    if (!completionReported.compareAndSet(false, true)) return@invokeLater
                    commandRunning = false
                    commandStatus.text = when (it) {
                        0 -> "${kind.label} completed"
                        null -> "${kind.label} could not start"
                        else -> "${kind.label} failed (exit $it)"
                    }
                    notifyCommand(
                        kind,
                        scope,
                        if (it == 0) CompletionResult.SUCCESS else CompletionResult.FAILURE,
                    )
                    telemetry.client.commandCompleted(
                        kind.telemetryKind(),
                        telemetryScope,
                        when {
                            it == 0 -> TelemetryCommandResult.SUCCESS
                            it == null -> TelemetryCommandResult.COULD_NOT_START
                            else -> TelemetryCommandResult.FAILURE
                        },
                        commandCount = 1,
                    )
                    updateCommandButtons()
                    LocalFileSystem.getInstance().refreshIoFiles(settings.resolvedGeneratedPaths(project))
                    refresh(force = true, trigger = "command_finished")
                    Timer(4000) { commandStatus.isVisible = false }.apply {
                        isRepeats = false
                        start()
                    }
                }
            },
        )
        if (!started) {
            commandRunning = false
            if (completionReported.compareAndSet(false, true)) {
                telemetry.client.commandCompleted(
                    kind.telemetryKind(),
                    telemetryScope,
                    TelemetryCommandResult.COULD_NOT_START,
                    commandCount = 1,
                )
            }
            updateCommandButtons()
        }
    }

    private fun notifyCommand(kind: CommandKind, scope: String, result: CompletionResult) {
        val target = if (scope == "whole project") {
            CompletionCommandScope.Project(project.name)
        } else {
            CompletionCommandScope.File(scope)
        }
        val message = CompletionNotificationPolicy.decide(
            enabled = settings.notifyWhenTasksFinish,
            hostForeground = ApplicationManager.getApplication().isActive,
            kind = if (kind == CommandKind.GENERATE) {
                CompletionCommandKind.GENERATE
            } else {
                CompletionCommandKind.VERIFY
            },
            scope = target,
            result = result,
        ) ?: return
        val type = if (result == CompletionResult.SUCCESS) {
            NotificationType.INFORMATION
        } else {
            NotificationType.ERROR
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Golden Diff Tasks")
            .createNotification(message.title, message.body, type)
            .addAction(NotificationAction.createSimple("Open command log") {
                ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.RUN)?.activate(null)
            })
            .notify(project)
    }

    private fun updateCommandButtons() {
        if (controller.state.selectedView == null) {
            if (generateTarget == CommandTarget.CURRENT) generateTarget = CommandTarget.PROJECT
            if (verifyTarget == CommandTarget.CURRENT) verifyTarget = CommandTarget.PROJECT
        }
        val config = settings.toConfig()
        generateProject.isEnabled = !commandRunning && when (generateTarget) {
            CommandTarget.PROJECT -> config.generateAllCommand.isNotBlank()
            CommandTarget.CURRENT ->
                controller.state.selectedView != null && config.generateFileCommand.isNotBlank()
        }
        verifyProject.isEnabled = !commandRunning && when (verifyTarget) {
            CommandTarget.PROJECT -> config.verifyAllCommand.isNotBlank()
            CommandTarget.CURRENT -> controller.state.selectedView != null &&
                (config.verifyCaseCommand.isNotBlank() || config.verifyFileCommand.isNotBlank())
        }
        generateProject.toolTipText = commandTargetTooltip(CommandKind.GENERATE, generateTarget)
        verifyProject.toolTipText = commandTargetTooltip(CommandKind.VERIFY, verifyTarget)
        generateScope.isEnabled = !commandRunning
        verifyScope.isEnabled = !commandRunning
        stopButton.isVisible = activeBatch != null
    }

    private fun commandTargetTooltip(kind: CommandKind, target: CommandTarget): String {
        val action = kind.label
        if (target == CommandTarget.PROJECT) return "$action for whole project"
        val snapshotCase = controller.state.selectedCase
        return if (kind == CommandKind.VERIFY && snapshotCase != null) {
            "$action for Case ${snapshotCase.methodName}"
        } else {
            val viewName = controller.state.selectedView?.relativePath
                ?.let { File(it).nameWithoutExtension }
                ?: "current View"
            "$action for View $viewName"
        }
    }

    private fun setGridColumns(columns: Int) {
        gridColumns = columns.coerceIn(1, 3)
        updateGoldenGridGeometry()
    }

    private fun updateGoldenGridGeometry() {
        val viewportWidth = goldensScroll.viewport.extentSize.width
            .takeIf { it > 0 }
            ?: goldensWidthPx
        val geometry = goldenGridGeometry(
            viewportWidth = viewportWidth,
            columns = gridColumns,
            horizontalInsets = JBUI.scale(20),
            cellGap = JBUI.scale(8),
            minimumCellWidth = JBUI.scale(32),
            minimumCardWidth = JBUI.scale(24),
        )
        cardWidth = geometry.cardWidth
        goldens.fixedCellWidth = geometry.cellWidth
        goldens.fixedCellHeight = -1
        goldenRenderer.clearScaledCache()
        goldens.revalidate()
        goldens.repaint()
    }

    private fun JList<GoldenGridItem>.installGoldenArrowNavigation() {
        fun bind(keyCode: Int, actionKey: String, delta: () -> Int) {
            getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(keyCode, 0),
                actionKey,
            )
            actionMap.put(actionKey, object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) {
                    moveGoldenSelection(delta())
                }
            })
        }
        bind(KeyEvent.VK_LEFT, "golden.selectLeft") { -1 }
        bind(KeyEvent.VK_RIGHT, "golden.selectRight") { 1 }
        bind(KeyEvent.VK_UP, "golden.selectUp") { -gridColumns }
        bind(KeyEvent.VK_DOWN, "golden.selectDown") { gridColumns }
    }

    private fun moveGoldenSelection(delta: Int) {
        if (goldenModel.isEmpty) return
        val current = goldens.selectedIndex
        val next = if (current < 0) {
            0
        } else {
            (current + delta).coerceIn(0, goldenModel.size - 1)
        }
        if (next == current) return
        nextSelectionTrigger = "keyboard"
        goldens.selectedIndex = next
        goldens.ensureIndexIsVisible(next)
    }

    private fun capturePixelSplitterSizes() {
        if (applyingPixelSplitterSizes) return
        activeGoldensSplitter?.takeIf { it.width > 0 }?.let { splitter ->
            goldensWidthPx = splitter.firstComponent.width
                .takeIf { it > 0 }
                ?.coerceIn(JBUI.scale(120), JBUI.scale(700))
                ?: goldensWidthPx
        }
        activeFilesSplitter?.takeIf { it.height > 0 }?.let { splitter ->
            val filesComponent = if (renderedFilesOnBottom) splitter.secondComponent else splitter.firstComponent
            filesHeightPx = filesComponent.height
                .takeIf { it > 0 }
                ?.coerceIn(JBUI.scale(80), JBUI.scale(420))
                ?: filesHeightPx
            filesHeightAdjusted = true
        }
    }

    private fun applyPixelSplitterSizes() {
        applyingPixelSplitterSizes = true
        try {
            activeGoldensSplitter?.takeIf { it.width > 0 }?.let { splitter ->
                splitter.proportion = (goldensWidthPx.toFloat() / splitter.width)
                    .coerceIn(0.01f, 0.99f)
            }
            activeFilesSplitter?.takeIf { it.height > 0 }?.let { splitter ->
                val filesShare = if (filesHeightAdjusted) {
                    (filesHeightPx.toFloat() / splitter.height).coerceIn(0.01f, 0.99f)
                } else {
                    0.5f
                }
                splitter.proportion = if (renderedFilesOnBottom) 1f - filesShare else filesShare
            }
        } finally {
            applyingPixelSplitterSizes = false
        }
    }

    private fun goldensProportion(): Float {
        val available = center.width.takeIf { it > 0 }
            ?: width.takeIf { it > 0 }
            ?: JBUI.scale(570)
        return (goldensWidthPx.toFloat() / available).coerceIn(0.01f, 0.99f)
    }

    private fun filesProportion(): Float {
        if (!filesHeightAdjusted) return 0.5f
        val available = center.height.takeIf { it > 0 }
            ?: height.takeIf { it > 0 }
            ?: JBUI.scale(920)
        val filesShare = (filesHeightPx.toFloat() / available).coerceIn(0.01f, 0.99f)
        return if (filesOnBottom) 1f - filesShare else filesShare
    }

    private fun installGoldensPixelTracking(splitter: JBSplitter) {
        splitter.addPropertyChangeListener("proportion") {
            if (!applyingPixelSplitterSizes && splitter.width > 0) {
                goldensWidthPx = (splitter.width * splitter.proportion).toInt()
                    .coerceIn(JBUI.scale(120), JBUI.scale(700))
                updateGoldenGridGeometry()
            }
        }
    }

    private fun installFilesPixelTracking(splitter: JBSplitter) {
        splitter.addPropertyChangeListener("proportion") {
            if (!applyingPixelSplitterSizes && splitter.height > 0) {
                val filesShare = if (renderedFilesOnBottom) 1f - splitter.proportion else splitter.proportion
                filesHeightPx = (splitter.height * filesShare).toInt()
                    .coerceIn(JBUI.scale(80), JBUI.scale(420))
                filesHeightAdjusted = true
            }
        }
    }

    private fun JButton.makeCompactTextButton() {
        font = font.deriveFont(JBUI.scaleFontSize(11f).toFloat())
        margin = JBUI.insets(2, 8)
        preferredSize = Dimension(preferredSize.width, JBUI.scale(24))
        minimumSize = Dimension(minimumSize.width, JBUI.scale(24))
    }

    private fun subscribe() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val active = activeEditorPath()
                    controller.editorChanged(active)
                    refreshExtraItems(active)
                    reportEditorSelection()
                }

                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    val active = activeEditorPath()
                    controller.editorChanged(active)
                    refreshExtraItems(active)
                    reportEditorSelection()
                }
            },
        )
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val config = settings.toConfig()
                    val root = projectRoot
                    if (events.asSequence().map { File(it.path) }.any { file ->
                            file.extension.equals("png", true) &&
                                (config.containsGoldenFile(root, file) || config.containsGeneratedFile(root, file))
                        }
                    ) {
                        vfsTimer.restart()
                    }
                }
            },
        )
        ExtraComparisonSources.addChangeListener(this) {
            ApplicationManager.getApplication().invokeLater { refreshExtraItems() }
        }
    }

    private fun activeEditorPath(): String? {
        val root = projectRoot ?: return null
        val selected = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
        return runCatching {
            File(selected.path).normalize().relativeTo(root.normalize()).path.replace(File.separatorChar, '/')
        }.getOrNull()?.takeUnless { it.startsWith("..") }
    }

    private fun reportEditorSelection() {
        val selected = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        telemetry.sourceFileSelected(
            memoryKey = selected.path,
            trigger = "ide_editor",
            fileFamily = fileFamily(selected.extension.orEmpty()),
            alreadyOpen = true,
        )
    }

    private fun currentWidthClass(): WidthClass {
        val effective = width.takeIf { it > 0 } ?: preferredSize.width
        return when {
            effective <= JBUI.scale(320) -> WidthClass.NARROW
            effective <= JBUI.scale(480) -> WidthClass.MEDIUM
            else -> WidthClass.WIDE
        }
    }

    override fun dispose() {
        vfsTimer.stop()
        comparisonGeneration++
        detachedWindow?.dispose()
        detachedWindow = null
        telemetry.panelClosed()
    }

    private enum class WidthClass { WIDE, MEDIUM, NARROW }
    private enum class Pane(val label: String) { FILES("Files"), GOLDENS("Goldens"), COMPARE("Compare") }
    private enum class CommandKind(val label: String) {
        GENERATE("Generate"),
        VERIFY("Verify");

        fun telemetryKind(): TelemetryCommandKind =
            if (this == GENERATE) TelemetryCommandKind.GENERATE else TelemetryCommandKind.VERIFY
    }
    private enum class CommandTarget { PROJECT, CURRENT }

    private data class LoadedBuiltInComparison(
        val frozen: GoldenAccept.FrozenCandidate?,
        val headRaw: ByteArray?,
        val sourceRaw: ByteArray?,
        val old: java.awt.image.BufferedImage?,
        val new: java.awt.image.BufferedImage?,
        val diffRatio: Double?,
    )

    private companion object {
        val HeaderColor = com.intellij.ui.JBColor(Color(0xF2F3F5), Color(0x2B2D30))
        val BorderColor = com.intellij.ui.JBColor(Color(0xE1E3E8), Color(0x3A3C3F))
        val AcceptColor = com.intellij.ui.JBColor(Color(0xE0507E), Color(0xE0507E))

        fun iconButton(
            icon: javax.swing.Icon,
            accessibleName: String,
            size: Int = 24,
            action: () -> Unit,
        ): JButton =
            JButton(icon).apply {
                toolTipText = accessibleName
                accessibleContext.accessibleName = accessibleName
                margin = JBUI.emptyInsets()
                isOpaque = false
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                preferredSize = Dimension(JBUI.scale(size), JBUI.scale(size))
                minimumSize = preferredSize
                maximumSize = preferredSize
                addActionListener { action() }
            }

        fun scopeMenuButton(accessibleName: String, action: (JButton) -> Unit): JButton =
            FlatHeaderButton("", AllIcons.General.ArrowDownSmall, height = 24, horizontalPadding = 3).apply {
                toolTipText = accessibleName
                getAccessibleContext()?.accessibleName = accessibleName
                preferredSize = Dimension(JBUI.scale(20), JBUI.scale(24))
                minimumSize = preferredSize
                maximumSize = preferredSize
                addActionListener { action(this) }
            }

        fun commandScopeControl(main: JButton, scope: JButton): JPanel =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(main)
                add(scope)
            }

        fun detach(component: Component) {
            component.parent?.remove(component)
        }
    }
}

private fun fileFamily(extension: String): String = when (extension.lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "jsx", "ts", "tsx" -> "js_ts"
    "swift" -> "swift"
    "png" -> "png"
    else -> "other"
}

private class MediumFilesOverlayPanel(
    private val content: JComponent,
    private val files: JComponent,
    onDismiss: () -> Unit,
) : JPanel(null) {
    private val scrim = JPanel().apply {
        isOpaque = true
        background = Color(0, 0, 0, 76)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) = onDismiss()
        })
    }

    init {
        add(content)
        add(scrim)
        add(files)
        setComponentZOrder(files, 0)
        setComponentZOrder(scrim, 1)
        setComponentZOrder(content, 2)
    }

    override fun doLayout() {
        content.setBounds(0, 0, width, height)
        scrim.setBounds(0, 0, width, height)
        files.setBounds(0, 0, minOf(JBUI.scale(220), width), height)
    }

    override fun getPreferredSize(): Dimension = content.preferredSize
    override fun getMinimumSize(): Dimension = content.minimumSize
}

internal data class GoldenGridGeometry(
    val columns: Int,
    val cellWidth: Int,
    val cardWidth: Int,
)

internal fun goldenGridGeometry(
    viewportWidth: Int,
    columns: Int,
    horizontalInsets: Int = 20,
    cellGap: Int = 8,
    minimumCellWidth: Int = 32,
    minimumCardWidth: Int = 24,
): GoldenGridGeometry {
    val contentWidth = (viewportWidth - horizontalInsets).coerceAtLeast(1)
    val safeColumns = columns.coerceIn(1, 3)
    val cellWidth = (contentWidth / safeColumns).coerceAtLeast(minimumCellWidth)
    val cardWidth = (cellWidth - cellGap)
        .coerceAtLeast(1)
        .coerceAtLeast(minimumCardWidth.coerceAtMost(cellWidth))
    return GoldenGridGeometry(safeColumns, cellWidth, cardWidth)
}

internal class ExtraSourceFilterChip : JToggleButton() {
    init {
        font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(10f).toFloat())
        iconTextGap = JBUI.scale(4)
        margin = JBUI.emptyInsets()
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        horizontalAlignment = CENTER
        preferredSize = Dimension(JBUI.scale(62), JBUI.scale(20))
        minimumSize = preferredSize
        maximumSize = Dimension(JBUI.scale(160), JBUI.scale(20))
    }

    fun configure(title: String?, sourceIcon: Icon?, selected: Boolean) {
        isVisible = title != null
        if (title == null) {
            isSelected = false
            return
        }
        text = title
        icon = sourceIcon
        isSelected = selected
        toolTipText = "Show only $title references"
        getAccessibleContext()?.accessibleName = "$title golden filter"
        val contentWidth = getFontMetrics(font).stringWidth(title) +
            (sourceIcon?.iconWidth ?: 0) +
            (if (sourceIcon == null) 0 else iconTextGap) +
            JBUI.scale(14)
        preferredSize = Dimension(contentWidth, JBUI.scale(20))
        minimumSize = preferredSize
        revalidate()
        repaint()
    }

    override fun paintComponent(graphics: java.awt.Graphics) {
        val g = graphics.create() as java.awt.Graphics2D
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
            )
            g.color = if (isSelected) {
                JBColor(Color(0xDCE7FC), Color(0x394B6A))
            } else {
                JBColor(Color(0xE8E9EC), Color(0x36383C))
            }
            g.fillRoundRect(0, 0, width, height, height, height)
            if (isSelected || hasFocus()) {
                g.color = if (hasFocus()) {
                    JBColor(Color(0x3574F0), Color(0x548AF7))
                } else {
                    JBColor(Color(0x8AA9DC), Color(0x627DAA))
                }
                g.drawRoundRect(0, 0, width - 1, height - 1, height - 1, height - 1)
            }
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
    }
}

internal class GridColumnButton(columns: Int) : JToggleButton(GridColumnsIcon(columns)) {
    init {
        val columnLabel = if (columns == 1) "column" else "columns"
        toolTipText = "Show goldens in $columns $columnLabel"
        getAccessibleContext()?.accessibleName = toolTipText
        margin = JBUI.emptyInsets()
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        preferredSize = Dimension(JBUI.scale(24), JBUI.scale(22))
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(graphics: java.awt.Graphics) {
        if (isSelected || model.isRollover) {
            val g = graphics.create() as java.awt.Graphics2D
            try {
                g.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
                )
                g.color = if (isSelected) {
                    JBColor(Color(0x3574F0), Color(0x3574F0))
                } else {
                    JBColor(Color(0xDFE1E5), Color(0x43454A))
                }
                g.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
            } finally {
                g.dispose()
            }
        }
        super.paintComponent(graphics)
    }
}

private class GridColumnsIcon(private val columns: Int) : Icon {
    private val width = JBUI.scale(14)
    private val height = JBUI.scale(12)

    override fun getIconWidth(): Int = width
    override fun getIconHeight(): Int = height

    override fun paintIcon(component: Component?, graphics: java.awt.Graphics, x: Int, y: Int) {
        val g = graphics.create() as java.awt.Graphics2D
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
            )
            val selected = (component as? AbstractButton)?.isSelected == true
            g.color = if (selected) Color.WHITE else UIUtil.getContextHelpForeground()
            val gap = JBUI.scale(1)
            val columnWidth = ((width - gap * (columns - 1)) / columns).coerceAtLeast(1)
            repeat(columns) { index ->
                val left = x + index * (columnWidth + gap)
                g.fillRoundRect(left, y, columnWidth, height, JBUI.scale(2), JBUI.scale(2))
            }
        } finally {
            g.dispose()
        }
    }
}
