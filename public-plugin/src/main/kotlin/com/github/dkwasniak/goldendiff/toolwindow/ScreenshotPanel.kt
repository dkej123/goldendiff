package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.compare.CompareView
import com.github.dkwasniak.goldendiff.compare.GeneratedImageSource
import com.github.dkwasniak.goldendiff.compare.GitImageSource
import com.github.dkwasniak.goldendiff.compare.ImageBytes
import com.github.dkwasniak.goldendiff.compare.ImagePainting
import com.github.dkwasniak.goldendiff.compare.PixelDiff
import com.github.dkwasniak.goldendiff.compare.toArgbImage
import com.github.dkwasniak.goldendiff.command.GoldenAccept
import com.github.dkwasniak.goldendiff.git.GitCli
import com.github.dkwasniak.goldendiff.git.WorkingCopyStatus
import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.scan.ChangeScanner
import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.match.Screen
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.naming.shortGoldenName
import com.github.dkwasniak.goldendiff.settings.ScreenshotConfigurable
import com.github.dkwasniak.goldendiff.settings.ScreenshotSettings
import com.github.dkwasniak.goldendiff.telemetry.PluginTelemetryService
import com.github.dkwasniak.goldendiff.telemetry.NoOpSpan
import com.github.dkwasniak.goldendiff.telemetry.TelemetryBuckets
import com.github.dkwasniak.goldendiff.telemetry.TelemetrySpan
import com.github.dkwasniak.goldendiff.telemetry.TelemetrySpanStatus
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonResult
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSource
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSources
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.Timer

class ScreenshotPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : JPanel(BorderLayout()), Disposable {

    private val settings = ScreenshotSettings.getInstance(project)
    private val telemetryService = PluginTelemetryService.getInstance()
    private val telemetry = telemetryService.client

    // Inside the IDE the VCS integration beats shelling out to git; core's GitCli backs the same
    // interface for hosts that have no VCS layer.
    private val headBytesSource = GitImageSource(project)

    /**
     * Built fresh per scan rather than cached: settings and the project's git state both change
     * underneath the tool window, and a stale snapshot would quietly show the wrong list.
     *
     * Deliberately mixes implementations - the IDE's VCS layer for per-file HEAD reads, the git CLI
     * for the one-shot project-wide status.
     */
    private fun changeScanner(): ChangeScanner {
        val root = project.basePath?.let(::File)
        return ChangeScanner(
            projectRoot = root,
            config = settings.toConfig(),
            headBytes = headBytesSource,
            workingCopyStatus = root?.let(::GitCli) ?: WorkingCopyStatus { emptyList() },
        )
    }

    private val listModel = DefaultListModel<ExtraComparisonItem>()
    private val listRenderer = GoldenCellRenderer(project)
    private val list = object : JBList<ExtraComparisonItem>(listModel) {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }.apply {
        cellRenderer = listRenderer
        layoutOrientation = JList.HORIZONTAL_WRAP
        visibleRowCount = -1
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        addComponentListener(
            object : ComponentAdapter() {
                private var lastWidth = 0

                override fun componentResized(e: ComponentEvent) {
                    if (width == lastWidth) return
                    lastWidth = width
                    refreshListThumbnailLayout()
                }
            },
        )
    }
    private val compareView = CompareView(
        onModeSelected = { mode ->
            telemetry.event(
                "product.compare_mode_selected",
                mapOf("mode" to mode, "location" to "main_pane"),
            )
        },
        onZoomSelected = { zoom, action ->
            telemetry.event(
                "product.zoom_selected",
                mapOf("zoom" to zoom, "action" to action, "location" to "main_pane"),
            )
        },
        onCopyPath = {
            telemetry.event("product.feature_used", mapOf("feature" to "copy_path"))
        },
        onAccept = {
            loadedFile
                ?.takeIf { selectedSource() == ComparisonSource.GENERATED }
                ?.let(::acceptGeneratedGolden)
        },
    )
    private val statusLabel = JBLabel().apply { border = JBUI.Borders.empty(4, 6) }
    private val statusLegendLabel = JBLabel(STATUS_LEGEND_HTML).apply {
        border = JBUI.Borders.empty(0, 6, 4, 6)
        isVisible = false
    }
    private val directoriesButton = JButton()
    private val generateCombo = createCommandCombo(CommandKind.GENERATE)
    private val verifyCombo = createCommandCombo(CommandKind.VERIFY)
    // True while a Generate/Verify run is in flight, so the buttons stay disabled and the status line
    // reports progress. The full output streams into IntelliJ's Run tool window (see CommandExecution).
    private var commandRunning = false
    private val scopeCombo = ComboBox(Scope.entries.toTypedArray()).apply {
        selectedItem = Scope.CURRENT_FILE
        toolTipText = "Choose whether to show screenshots for the current file or all project golden changes"
        addActionListener {
            val previous = reportedScope
            if (selectedScope() == Scope.PROJECT_CHANGES && selectedSource().extra != null) {
                sourceCombo.selectedItem = ComparisonSource.WORKING_COPY
            }
            val next = selectedScope()
            if (previous != next) {
                telemetry.event(
                    "product.browse_scope_selected",
                    mapOf("from" to previous.wireValue(), "to" to next.wireValue()),
                )
                reportedScope = next
            }
            updateScopeControls()
            loadedFile = null
            lastNames = null
            scheduleRefresh(force = true, trigger = "scope_change")
        }
    }
    private var thumbnailScaleIndex = 0
    private val thumbnailScaleLabel = JBLabel().apply {
        border = JBUI.Borders.empty(6, 0)
        horizontalAlignment = SwingConstants.CENTER
        alignmentX = CENTER_ALIGNMENT
        preferredSize = Dimension(JBUI.scale(32), JBUI.scale(28))
        minimumSize = preferredSize
        maximumSize = preferredSize
    }
    private val thumbnailMinusButton = createThumbnailScaleButton("-", ThumbnailScaleIcon(false)).apply {
        toolTipText = "Decrease thumbnail size"
        addActionListener { changeThumbnailScale(1) }
    }
    private val thumbnailPlusButton = createThumbnailScaleButton("+", ThumbnailScaleIcon(true)).apply {
        toolTipText = "Increase thumbnail size"
        addActionListener { changeThumbnailScale(-1) }
    }
    private val thumbnailScalePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(2)
        isOpaque = false
        add(thumbnailPlusButton)
        add(thumbnailMinusButton)
        add(thumbnailScaleLabel)
    }
    private val comparisonSources = ComparisonSource.builtIns + ExtraComparisonSources.all.map { ComparisonSource.extra(it) }
    private val sourceCombo: ComboBox<ComparisonSource> = ComboBox(comparisonSources.toTypedArray()).apply {
        selectedItem = ComparisonSource.WORKING_COPY
        toolTipText = "Choose whether HEAD is compared with the working-copy golden or test output"
        addActionListener {
            val newSource = selectedSource()
            if (selectedScope() == Scope.PROJECT_CHANGES) {
                if (newSource.extra != null) {
                    selectedItem = ComparisonSource.WORKING_COPY
                    return@addActionListener
                }
            }
            val previous = reportedSource
            if (previous != newSource && previous.extra == null && newSource.extra == null) {
                telemetry.event(
                    "product.comparison_source_selected",
                    mapOf("from" to previous.wireValue(), "to" to newSource.wireValue()),
                )
            }
            reportedSource = newSource
            loadedFile = null
            loadedSource = newSource
            lastNames = null
            listModel.clear()
            refreshListThumbnailLayout()
            updateStatusLegend(emptyList())
            clearComparison()
            statusLabel.text = "Searching…"
            // Every source has source-specific items and statuses. Rebuild the list, and invalidate
            // any older asynchronous scan so it cannot restore stale Working-copy thumbnails.
            scheduleRefresh(force = true, trigger = "source_change")
        }
    }

    // Debounces list refreshes from editor/caret changes; fires once on the EDT after DEBOUNCE_MS.
    private val refreshTimer = Timer(DEBOUNCE_MS) { refresh() }.apply { isRepeats = false }
    private val pngRefreshTimer = Timer(PNG_DEBOUNCE_MS) { applyPngVfsChanges() }.apply { isRepeats = false }
    private val pendingPngChanges = mutableListOf<PngVfsChange>()

    // Names the list was last built from — used to avoid rebuilding when only the caret moves.
    private var lastNames: List<String>? = null
    // Current screen model used by variant-provided comparison sources.
    private var currentScreen: Screen? = null
    // File currently shown in the comparison view, to avoid reloading the same image.
    private var loadedFile: File? = null
    private var loadedSource = ComparisonSource.WORKING_COPY
    private var pendingForce = false
    private var pendingTrigger = "automatic"
    private var refreshGeneration = 0L
    private var reportedScope = Scope.CURRENT_FILE
    private var reportedSource = ComparisonSource.WORKING_COPY
    private var currentScanStarted = 0L
    private var currentScanTrigger = "automatic"
    private var nextSelectionTrigger = "automatic"
    private var pngUpdateGeneration = 0L
    private val suppressedPngPaths = HashMap<String, Long>()
    // Accepted rows remain in the current list so promotion never clears the grid or changes
    // selection. The marker also restores the confirmation when the user clicks away and back.
    private val acceptedGoldens = mutableSetOf<String>()
    private var acceptingGolden: File? = null
    // Tracks tool-window visibility so we can refresh once it is reopened.
    private var wasVisible = toolWindow.isVisible

    init {
        telemetryService.panelOpened(project)
        val listScrollPane = JBScrollPane(list).apply {
            minimumSize = Dimension(JBUI.scale(120), 0)
            preferredSize = Dimension(JBUI.scale(320), 0)
            viewport.addComponentListener(
                object : ComponentAdapter() {
                    private var lastWidth = 0

                    override fun componentResized(e: ComponentEvent) {
                        if (viewport.width == lastWidth) return
                        lastWidth = viewport.width
                        refreshListThumbnailLayout()
                    }
                },
            )
        }
        compareView.minimumSize = Dimension(JBUI.scale(240), 0)
        val splitter = JBSplitter(false, 0.35f).apply {
            setHonorComponentsMinimumSize(false)
            firstComponent = BottomRightOverlayPanel(listScrollPane, thumbnailScalePanel)
            secondComponent = compareView
        }
        add(buildHeader(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val trigger = nextSelectionTrigger
                nextSelectionTrigger = "grid"
                list.selectedValue?.let { loadComparison(it, trigger) }
            }
        }
        installListKeyboardNavigation()
        installListContextMenu()

        subscribeToEditor()
        updateThumbnailScaleControls()
        updateScopeControls()
        updateHeader()
        scheduleRefresh()
    }

    private fun refreshListThumbnailLayout() {
        listRenderer.clearScaledCache()
        val cellWidth = thumbnailCellWidth()
        list.fixedCellWidth = cellWidth
        list.fixedCellHeight = listRenderer.cellHeight(listModel.elements().toList(), cellWidth)
        list.invalidate()
        list.revalidate()
        list.repaint()
    }

    private fun installListKeyboardNavigation() {
        bindListKey(KeyEvent.VK_RIGHT, "goldenDiffSelectNext", 1)
        bindListKey(KeyEvent.VK_LEFT, "goldenDiffSelectPrevious", -1)
        bindPanelKey(KeyEvent.VK_RIGHT, "goldenDiffSelectNextInPanel", 1)
        bindPanelKey(KeyEvent.VK_LEFT, "goldenDiffSelectPreviousInPanel", -1)
        list.actionMap.put("selectNextColumn", listMoveAction(1))
        list.actionMap.put("selectPreviousColumn", listMoveAction(-1))
        list.actionMap.put("selectNextColumnExtendSelection", listMoveAction(1))
        list.actionMap.put("selectPreviousColumnExtendSelection", listMoveAction(-1))
        list.actionMap.put("selectNextColumnChangeLead", listMoveAction(1))
        list.actionMap.put("selectPreviousColumnChangeLead", listMoveAction(-1))
    }

    private fun installListContextMenu() {
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) = maybeShowMenu(event)
                override fun mouseReleased(event: MouseEvent) = maybeShowMenu(event)

                private fun maybeShowMenu(event: MouseEvent) {
                    if (!event.isPopupTrigger) return
                    val index = list.locationToIndex(event.point)
                    if (index < 0 || !list.getCellBounds(index, index).contains(event.point)) return
                    // Right-clicking a row outside the current selection selects just that row first, so
                    // the menu acts on what the user pointed at.
                    if (index !in list.selectedIndices) list.selectedIndex = index
                    showListContextMenu(event)
                }
            },
        )
    }

    private fun showListContextMenu(event: MouseEvent) {
        val clicked = list.selectedValue?.file
        val existingSelected = list.selectedValuesList.mapNotNull { it.file }.filter { it.isFile }
        val group = DefaultActionGroup().apply {
            add(
                listAction("Show in ${RevealFileAction.getFileManagerName()}", clicked?.isFile == true) {
                    telemetry.event("product.feature_used", mapOf("feature" to "reveal_in_file_manager"))
                    clicked?.let(RevealFileAction::openFile)
                },
            )
            add(
                listAction("Copy Absolute Path", clicked != null) {
                    telemetry.event("product.feature_used", mapOf("feature" to "copy_path"))
                    clicked?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.absolutePath)) }
                },
            )
            addSeparator()
            add(
                listAction(
                    if (existingSelected.size > 1) "Delete ${existingSelected.size} Files" else "Delete",
                    existingSelected.isNotEmpty(),
                ) { deleteGoldenFiles(existingSelected) },
            )
        }
        val popupMenu = ActionManager.getInstance().createActionPopupMenu(GOLDEN_LIST_POPUP_PLACE, group)
        popupMenu.setTargetComponent(list)
        popupMenu.component.show(list, event.x, event.y)
    }

    private fun listAction(text: String, enabled: Boolean, action: () -> Unit): AnAction =
        object : AnAction(text) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled
            }
            override fun actionPerformed(e: AnActionEvent) = action()
        }

    private fun deleteGoldenFiles(files: List<File>) {
        if (files.isEmpty()) return
        val confirmed = Messages.showYesNoDialog(
            project,
            "Delete the following golden file(s) from disk?\n\n" + files.joinToString("\n") { it.name },
            if (files.size > 1) "Delete ${files.size} Golden Files" else "Delete Golden File",
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return
        val failed = files.filterNot { runCatching { it.delete() }.getOrDefault(false) }
        LocalFileSystem.getInstance().refreshIoFiles(files)
        if (failed.isNotEmpty()) {
            Messages.showErrorDialog(
                project,
                "Could not delete:\n" + failed.joinToString("\n") { it.path },
                "Delete Golden File",
            )
        }
        scheduleRefresh()
    }

    private fun acceptGeneratedGolden(golden: File) {
        if (acceptingGolden != null || selectedSource() != ComparisonSource.GENERATED) return
        acceptingGolden = golden
        suppressPngEvent(golden)
        compareView.showAccepting()
        val config = settings.toConfig()
        val root = project.basePath?.let(::File)
        val trim = settings.trimTransparentPadding
        AppExecutorUtil.getAppExecutorService().execute {
            val result = GoldenAccept.accept(golden, config, root)
            val acceptedImage = result.getOrNull()?.let {
                ImagePainting.trimTransparentBorder(
                    ImageBytes.decode(ImageBytes.workingBytes(golden)),
                    trim,
                )
            }
            ApplicationManager.getApplication().invokeLater {
                acceptingGolden = null
                telemetry.event(
                    "product.feature_used",
                    mapOf("feature" to if (result.isSuccess) "accept_golden" else "accept_golden_failed"),
                )
                result.onSuccess { generated ->
                    suppressPngEvent(generated)
                    LocalFileSystem.getInstance().refreshIoFiles(listOf(golden, generated))
                    acceptedGoldens += golden.absolutePath
                    markItemAccepted(golden)
                    if (loadedFile == golden && selectedSource() == ComparisonSource.GENERATED) {
                        compareView.showAccepted(acceptedImage, "Accepted as golden \u2014 ${golden.name}")
                    }
                }
                result.onFailure { error ->
                    val message = when ((error as? GoldenAccept.AcceptException)?.failure) {
                        is GoldenAccept.Failure.NoGeneratedCounterpart ->
                            "No generated output was found for ${golden.name}."
                        else -> "Could not accept ${golden.name}."
                    }
                    if (loadedFile == golden && selectedSource() == ComparisonSource.GENERATED) {
                        compareView.showAccept()
                    }
                    Messages.showErrorDialog(project, message, "Accept Generated Golden")
                }
            }
        }
    }

    /** Updates one row in place; list order, scroll position and selection remain untouched. */
    private fun markItemAccepted(golden: File) {
        for (index in 0 until listModel.size()) {
            val item = listModel.getElementAt(index)
            if (item.file == golden) {
                listModel.setElementAt(item.copy(status = ExtraComparisonItemStatus.UNCHANGED), index)
                break
            }
        }
        listRenderer.clearFileCache(golden)
        updateStatusLegend(listModel.elements().toList())
        list.repaint()
    }

    private fun bindListKey(keyCode: Int, actionName: String, delta: Int) {
        val keyStroke = KeyStroke.getKeyStroke(keyCode, 0)
        list.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionName)
        list.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(keyStroke, actionName)
        list.actionMap.put(actionName, listMoveAction(delta))
    }

    private fun bindPanelKey(keyCode: Int, actionName: String, delta: Int) {
        val keyStroke = KeyStroke.getKeyStroke(keyCode, 0)
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(keyStroke, actionName)
        actionMap.put(actionName, listMoveAction(delta))
    }

    private fun listMoveAction(delta: Int): AbstractAction =
        object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                moveListSelectionBy(delta)
            }
        }

    private fun moveListSelectionBy(delta: Int) {
        if (listModel.isEmpty) return
        val current = list.selectedIndex.takeIf { it >= 0 } ?: if (delta > 0) -1 else listModel.size()
        val next = (current + delta).coerceIn(0, listModel.size() - 1)
        if (next == list.selectedIndex) return
        nextSelectionTrigger = "keyboard"
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    private fun thumbnailCellWidth(): Int {
        val viewportWidth = (list.parent?.width ?: list.width).coerceAtLeast(JBUI.scale(120))
        val targetWidth = JBUI.scale((BASE_THUMBNAIL_CELL_WIDTH * thumbnailScale()).toInt())
        return targetWidth
            .coerceAtLeast(JBUI.scale(80))
            .coerceAtMost(viewportWidth)
    }

    private fun changeThumbnailScale(delta: Int) {
        thumbnailScaleIndex = (thumbnailScaleIndex + delta).coerceIn(0, THUMBNAIL_SCALES.lastIndex)
        updateThumbnailScaleControls()
        refreshListThumbnailLayout()
    }

    private fun updateThumbnailScaleControls() {
        thumbnailScaleLabel.text = "${(thumbnailScale() * 100).toInt()}%"
        thumbnailPlusButton.isEnabled = thumbnailScaleIndex > 0
        thumbnailMinusButton.isEnabled = thumbnailScaleIndex < THUMBNAIL_SCALES.lastIndex
    }

    private fun buildHeader(): JPanel {
        directoriesButton.addActionListener { onDirectoriesClicked() }
        val toolbar = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(directoriesButton)
            add(generateCombo)
            add(verifyCombo)
            add(JBLabel("Scope:"))
            add(scopeCombo)
            add(JBLabel("Compare:"))
            add(sourceCombo)
        }
        updateCommandButtons()

        val statusPanel = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(statusLegendLabel, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 6, 4, 6)
            add(toolbar, BorderLayout.NORTH)
            add(statusPanel, BorderLayout.SOUTH)
        }
    }

    private fun subscribeToEditor() {
        // Refresh only when the selected file changes — not on caret moves, so clicking around the
        // editor never rebuilds the list or steals the user's manual selection.
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = scheduleRefresh()
                override fun fileOpened(source: com.intellij.openapi.fileEditor.FileEditorManager, file: VirtualFile) =
                    scheduleRefresh()
            },
        )
        // Catch up on file changes that happened while the tool window was hidden, once it reopens.
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    val visible = toolWindow.isVisible
                    if (visible && !wasVisible) scheduleRefresh()
                    wasVisible = visible
                }
            },
        )
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val changes = events.mapNotNull { event ->
                        event.path.takeIf { it.endsWith(".png", ignoreCase = true) }
                            ?.let { PngVfsChange(File(it), event !is VFileContentChangeEvent) }
                    }
                    if (changes.isNotEmpty()) {
                        pendingPngChanges += changes
                        pngRefreshTimer.restart()
                    }
                }
            },
        )
    }

    private fun applyPngVfsChanges() {
        if (commandRunning) {
            pendingPngChanges.clear()
            return
        }
        val now = System.currentTimeMillis()
        suppressedPngPaths.entries.removeIf { it.value < now }
        val root = project.basePath?.let(::File)
        val config = settings.toConfig()
        val source = selectedSource()
        val relevant = pendingPngChanges.toList()
            .also { pendingPngChanges.clear() }
            .distinctBy { "${it.structural}:${it.file.absolutePath}" }
            .filterNot { suppressedPngPaths.containsKey(it.file.absolutePath) }
            .filter {
                config.containsGoldenFile(root, it.file) ||
                    (source == ComparisonSource.GENERATED && config.containsGeneratedFile(root, it.file))
            }
        if (relevant.isEmpty()) return
        if (relevant.any(PngVfsChange::structural) || selectedScope() == Scope.PROJECT_CHANGES) {
            scheduleRefresh(force = true, trigger = "filesystem_change")
            return
        }

        val visible = listModel.elements().toList()
        val affected = linkedSetOf<File>()
        relevant.forEach { change ->
            visible.firstOrNull { it.file.absolutePath == change.file.absolutePath }?.file?.let(affected::add)
            if (source == ComparisonSource.GENERATED && config.containsGeneratedFile(root, change.file)) {
                generatedGoldenBase(change.file)?.let { base ->
                    visible.asSequence()
                        .filter { it.file.nameWithoutExtension.equals(base, ignoreCase = true) }
                        .mapTo(affected) { it.file }
                }
            }
        }
        if (affected.isNotEmpty()) refreshItemsInPlace(affected, source)
    }

    private fun refreshItemsInPlace(affected: Set<File>, source: ComparisonSource) {
        val generation = ++pngUpdateGeneration
        AppExecutorUtil.getAppExecutorService().execute {
            val refreshed = changeScanner().itemsFor(
                affected.toList(),
                if (source == ComparisonSource.GENERATED) BuiltInSource.GENERATED else BuiltInSource.WORKING_COPY,
            ).associateBy { it.file.absolutePath }
            ApplicationManager.getApplication().invokeLater {
                if (generation != pngUpdateGeneration || selectedSource() != source) return@invokeLater
                for (index in 0 until listModel.size()) {
                    val current = listModel.getElementAt(index)
                    refreshed[current.file.absolutePath]?.let { updated ->
                        listModel.setElementAt(updated.copy(title = current.title), index)
                        listRenderer.clearFileCache(current.file)
                    }
                }
                updateStatusLegend(listModel.elements().toList())
                list.repaint()
                list.selectedValue?.takeIf { it.file in affected }?.let { selectedItem ->
                    loadedFile = null
                    loadComparison(selectedItem, "filesystem", keepCurrentPreview = true)
                }
            }
        }
    }

    private fun generatedGoldenBase(file: File): String? {
        val regex = runCatching { Regex(settings.generatedFileRegex) }.getOrNull() ?: return null
        return regex.matchEntire(file.name)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun suppressPngEvent(file: File) {
        suppressedPngPaths[file.absolutePath] = System.currentTimeMillis() + PNG_SUPPRESSION_MS
    }

    private fun onDirectoriesClicked() {
        if (settings.isConfigured) {
            ShowSettingsUtil.getInstance().editConfigurable(project, ScreenshotConfigurable(project))
            updateHeader()
            updateCommandButtons()
            scheduleRefresh(force = true, trigger = "config_change")
        } else {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Screenshot Directory")
                .withDescription("Directory that contains screenshot golden files")
            val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
            settings.paths = settings.paths + storagePath(chosen.path)
            updateHeader()
            scheduleRefresh(force = true, trigger = "config_change")
        }
    }

    private fun updateHeader() {
        directoriesButton.text = if (settings.isConfigured) "Settings" else "Choose screenshots directory"
    }

    private fun updateScopeControls() {
        sourceCombo.isEnabled = true
    }

    // region screenshot-test commands

    private enum class CommandKind(val label: String) { GENERATE("Generate"), VERIFY("Verify") }

    private enum class CommandTarget(val label: String) {
        WHOLE_PROJECT("Whole project"),
        CURRENT_FILE("Current file"),
        ;

        override fun toString(): String = label
    }

    private fun commandFor(kind: CommandKind, perFile: Boolean): String {
        val config = settings.toConfig()
        return when (kind) {
            CommandKind.GENERATE -> if (perFile) config.generateFileCommand else config.generateAllCommand
            CommandKind.VERIFY -> if (perFile) config.verifyFileCommand else config.verifyAllCommand
        }
    }

    private fun createCommandCombo(kind: CommandKind): ComboBox<CommandTarget> {
        var resettingSelection = false
        return ComboBox(CommandTarget.entries.toTypedArray()).apply {
            selectedItem = null
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component {
                    val component = super.getListCellRendererComponent(
                        list,
                        value,
                        index,
                        isSelected,
                        cellHasFocus,
                    )
                    val target = value as? CommandTarget
                    text = target?.label ?: kind.label
                    if (target != null) {
                        val perFile = target == CommandTarget.CURRENT_FILE
                        val commandConfigured = commandFor(kind, perFile).isNotBlank()
                        val hasCurrentFile = !perFile || currentScreen != null
                        isEnabled = commandConfigured && hasCurrentFile && !commandRunning
                        toolTipText = when {
                            !commandConfigured -> "Set the ${target.label.lowercase()} ${kind.label} command in Settings."
                            !hasCurrentFile -> "Open a file in the editor first."
                            else -> commandFor(kind, perFile)
                        }
                    }
                    return component
                }
            }
            addActionListener {
                if (resettingSelection) return@addActionListener
                val target = selectedItem as? CommandTarget ?: return@addActionListener
                resettingSelection = true
                selectedItem = null
                resettingSelection = false

                val perFile = target == CommandTarget.CURRENT_FILE
                val configuredCommand = commandFor(kind, perFile)
                val command = if (perFile) {
                    val screen = currentScreen ?: return@addActionListener
                    com.github.dkwasniak.goldendiff.command.CommandTemplate.expand(configuredCommand, screen)
                } else {
                    configuredCommand
                }
                if (configuredCommand.isNotBlank()) {
                    runScreenshotCommand(kind, command, perFile)
                }
            }
        }
    }

    /**
     * Keeps the Generate/Verify buttons enabled only when at least one of their commands is set and no
     * run is in flight; a disabled button explains how to turn it on. Called after settings changes.
     */
    private fun updateCommandButtons() {
        fun sync(combo: ComboBox<CommandTarget>, kind: CommandKind) {
            val configured = commandFor(kind, perFile = false).isNotBlank() ||
                commandFor(kind, perFile = true).isNotBlank()
            combo.isEnabled = configured && !commandRunning
            combo.toolTipText = when {
                commandRunning -> "A screenshot command is already running."
                !configured -> "Set a ${kind.label} command in Settings → Golden Diff → Commands."
                else -> "${kind.label} screenshot goldens (whole project or current file)."
            }
            combo.repaint()
        }
        sync(generateCombo, CommandKind.GENERATE)
        sync(verifyCombo, CommandKind.VERIFY)
    }

    private fun runScreenshotCommand(kind: CommandKind, command: String, perFile: Boolean) {
        if (commandRunning || command.isBlank()) return
        val root = project.basePath?.let(::File) ?: run {
            statusLabel.text = "No project directory to run the command in."
            return
        }
        telemetry.event(
            "product.feature_used",
            mapOf("feature" to if (kind == CommandKind.GENERATE) "generate_run" else "verify_run"),
        )
        commandRunning = true
        updateCommandButtons()
        statusLabel.text = "${kind.label}${if (perFile) " (current file)" else ""} running…"
        val started = CommandExecution.run(
            project = project,
            command = command,
            workingDir = root,
            title = "Golden Diff: ${kind.label}${if (perFile) " (current file)" else ""}",
            onTerminated = { exitCode ->
                ApplicationManager.getApplication().invokeLater {
                    commandRunning = false
                    updateCommandButtons()
                    onScreenshotCommandFinished(kind, exitCode)
                }
            },
        )
        if (!started) {
            commandRunning = false
            updateCommandButtons()
            statusLabel.text = "Could not start the ${kind.label} command."
        }
    }

    private fun onScreenshotCommandFinished(kind: CommandKind, exitCode: Int?) {
        // Surface the freshly written output: refresh VFS for the generated dirs, switch to Test
        // output and rescan so the new comparison shows without a manual refresh.
        LocalFileSystem.getInstance().refreshIoFiles(settings.resolvedGeneratedPaths(project))
        statusLabel.text = when (exitCode) {
            0 -> "${kind.label} finished."
            null -> "${kind.label} could not be started."
            else -> "${kind.label} failed (exit $exitCode) — see the Run window."
        }
        if (exitCode == 0 && kind == CommandKind.VERIFY) {
            sourceCombo.selectedItem = ComparisonSource.GENERATED
        }
        loadedFile = null
        scheduleRefresh(force = true, trigger = "command_finished")
    }

    // endregion

    private fun createThumbnailScaleButton(accessibleName: String, icon: Icon): JButton =
        JButton(icon).apply {
            accessibleContext.accessibleName = accessibleName
            isFocusable = false
            alignmentX = CENTER_ALIGNMENT
            margin = JBUI.insets(0)
            preferredSize = Dimension(JBUI.scale(32), JBUI.scale(32))
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

    private fun storagePath(path: String): String {
        val basePath = project.basePath ?: return path
        val base = File(basePath).normalize()
        val file = File(path).normalize()
        return runCatching { file.relativeTo(base).path }
            .getOrNull()
            ?.takeIf { !it.startsWith("..") && it != "." }
            ?.replace(File.separatorChar, '/')
            ?: path
    }

    private fun scheduleRefresh(force: Boolean = false, trigger: String = "automatic") {
        if (force) pendingForce = true
        pendingTrigger = trigger
        refreshGeneration++
        refreshTimer.restart()
    }

    // Append any comparison sources contributed after this panel was constructed. Idempotent: only
    // sources not already in the combo are added, so selection and existing entries are untouched
    // (JComboBox.addItem does not change the current selection or fire a selection event).
    private fun syncExtraComparisonSources() {
        val present = (0 until sourceCombo.itemCount)
            .mapNotNull { sourceCombo.getItemAt(it)?.extra?.id }
            .toSet()
        ExtraComparisonSources.all
            .filter { it.id !in present }
            .forEach { sourceCombo.addItem(ComparisonSource.extra(it)) }
    }

    private fun refresh() {
        // Don't scan for goldens while the tool window is collapsed/hidden; a pending force is kept
        // so it is honored once the window is reopened.
        if (!toolWindow.isVisible) return

        // Extra comparison sources come from dependent plugins (e.g. Golden Diff — Figma) via an
        // extension point. A dependent plugin can finish loading after this panel was first built —
        // notably when the tool window is restored open on startup — so top up the source combo here
        // instead of trusting the one-time snapshot taken in the field initializer.
        syncExtraComparisonSources()

        val force = pendingForce
        pendingForce = false
        if (force) acceptedGoldens.clear()
        val trigger = pendingTrigger
        pendingTrigger = "automatic"
        val generation = refreshGeneration
        val scanStarted = System.nanoTime()
        val scanSpan = if (selectedSource().extra == null) {
            telemetry.startSpan(
                "golden.scan",
                mapOf("scope" to selectedScope().wireValue(), "source" to selectedSource().wireValue()),
            )
        } else {
            NoOpSpan
        }

        if (!settings.isConfigured) {
            statusLabel.text = "Choose a screenshots directory to begin."
            updateStatusLegend(emptyList())
            listModel.clear()
            lastNames = null
            currentScreen = null
            clearComparison()
            emitScan(emptyList(), trigger, scanStarted, "blocked", "no_configuration", false)
            scanSpan.finish()
            return
        }
        val roots = settings.resolvedPaths(project)
        if (force) loadedFile = null

        if (selectedScope() == Scope.PROJECT_CHANGES) {
            refreshProjectChanges(roots, force, generation, trigger, scanStarted, scanSpan)
            return
        }

        val screen = CurrentScreen.compute(project, settings.annotatedFunctionRegex)
        if (screen == null || screen.names.isEmpty()) {
            statusLabel.text = "Open a screen or test file, or switch scope to Project changes."
            updateStatusLegend(emptyList())
            listModel.clear()
            lastNames = null
            currentScreen = null
            clearComparison()
            emitScan(emptyList(), trigger, scanStarted, "success_empty", "none", false)
            scanSpan.finish()
            return
        }

        val source = selectedSource()
        val refreshKey = screen.names + source.id + (source.extra?.refreshKey(screen) ?: emptyList())

        // Same file/name-set/source as before: keep the current list and the user's selection untouched.
        if (!force && refreshKey == lastNames) {
            emitScan(
                listModel.elements().toList(),
                trigger,
                scanStarted,
                if (listModel.isEmpty) "success_empty" else "success_nonempty",
                "none",
                true,
            )
            scanSpan.finish()
            return
        }

        lastNames = refreshKey
        currentScreen = screen
        statusLabel.text = "Searching…"
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching {
                val items = source.extra?.findItems(project, screen, settings) ?: telemetry.measureSpan(
                    "golden.match",
                    mapOf("scope" to "current_file", "source" to source.wireValue()),
                ) {
                    changeScanner().itemsFor(
                        GoldenFinder.find(
                            roots,
                            screen,
                            settings.matchMode,
                            settings.excludedSuffixes,
                            settings.goldenFilePatterns,
                        ),
                        if (source == ComparisonSource.GENERATED) {
                            BuiltInSource.GENERATED
                        } else {
                            BuiltInSource.WORKING_COPY
                        },
                    )
                }
                items to source.extra?.listStatusForItems(items)
            }
            ApplicationManager.getApplication().invokeLater {
                if (generation != refreshGeneration || selectedSource() != source) {
                    scanSpan.finish(TelemetrySpanStatus.CANCELLED)
                    return@invokeLater
                }
                result.onFailure {
                    reportOperationFailure("scan", it, retryable = true)
                    emitScan(emptyList(), trigger, scanStarted, "failure", "none", false)
                    scanSpan.finish(TelemetrySpanStatus.ERROR)
                    statusLabel.text = "Could not scan screenshots."
                }
                result.onSuccess { (items, statusOverride) ->
                    populate(
                        items,
                        if (source.extra == null) screen.caretName else null,
                        statusOverride,
                        trigger,
                        scanStarted,
                        scanSpan,
                    )
                    if (source.extra == null) reportEditorSelection()
                }
            }
        }
    }

    private fun refreshProjectChanges(
        roots: List<File>,
        force: Boolean,
        generation: Long,
        trigger: String,
        scanStarted: Long,
        scanSpan: TelemetrySpan,
    ) {
        val source = selectedSource().takeIf { it.extra == null } ?: ComparisonSource.WORKING_COPY
        val refreshKey = listOf(
            "project-changes",
            source.id,
            settings.generatedFileRegex,
        ) + roots.map { it.path } + settings.resolvedGeneratedPaths(project).map { it.path }
        if (!force && refreshKey == lastNames) {
            emitScan(
                listModel.elements().toList(),
                trigger,
                scanStarted,
                if (listModel.isEmpty) "success_empty" else "success_nonempty",
                "none",
                true,
            )
            scanSpan.finish()
            return
        }

        lastNames = refreshKey
        currentScreen = null
        statusLabel.text = "Searching project changes..."
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching {
                when (source) {
                    ComparisonSource.GENERATED -> telemetry.measureSpan(
                        "generated.lookup",
                        mapOf("scope" to "project_changes", "source" to "test_output"),
                    ) { changeScanner().generatedChanges() }
                    else -> telemetry.measureSpan(
                        "git.status",
                        mapOf("scope" to "project_changes", "source" to "working_copy"),
                    ) { changeScanner().workingCopyChanges() }
                }.filter { it.status != ExtraComparisonItemStatus.UNCHANGED }
            }
            val sourceName = when (source) {
                ComparisonSource.GENERATED -> "test output"
                else -> "working copy"
            }
            ApplicationManager.getApplication().invokeLater {
                if (generation != refreshGeneration || selectedSource() != source) {
                    scanSpan.finish(TelemetrySpanStatus.CANCELLED)
                    return@invokeLater
                }
                result.onFailure {
                    reportOperationFailure("scan", it, retryable = true)
                    emitScan(emptyList(), trigger, scanStarted, "failure", "none", false)
                    scanSpan.finish(TelemetrySpanStatus.ERROR)
                    statusLabel.text = "Could not scan project changes."
                }
                result.onSuccess { items ->
                    populate(
                        items,
                        caretName = null,
                        statusOverride = if (items.isEmpty()) {
                            "No golden changes found in $sourceName."
                        } else {
                            "${items.size} changed screenshot(s) found in project $sourceName."
                        },
                        trigger = trigger,
                        scanStarted = scanStarted,
                        scanSpan = scanSpan,
                    )
                }
            }
        }
    }

    private fun populate(
        items: List<ExtraComparisonItem>,
        caretName: String?,
        statusOverride: String? = null,
        trigger: String,
        scanStarted: Long,
        scanSpan: TelemetrySpan,
    ) {
        val previouslyLoaded = loadedFile
        for (index in items.indices) {
            if (index < listModel.size()) {
                listModel.setElementAt(items[index], index)
            } else {
                listModel.addElement(items[index])
            }
        }
        while (listModel.size() > items.size) listModel.removeElementAt(listModel.size() - 1)
        refreshListThumbnailLayout()
        updateStatusLegend(items)
        statusLabel.text = statusOverride ?: if (items.isEmpty()) {
            "No screenshots found for this file. Check the directory or record screenshots."
        } else {
            "${items.size} screenshot(s) found."
        }
        emitScan(
            items,
            trigger,
            scanStarted,
            if (items.isEmpty()) "success_empty" else "success_nonempty",
            "none",
            false,
        )
        scanSpan.finish()
        if (items.isEmpty()) {
            clearComparison()
            return
        }
        // Prefer keeping whatever was already open; otherwise pick the caret match, else the first.
        val index = when {
            previouslyLoaded != null && items.indexOfFirst { it.file == previouslyLoaded } >= 0 ->
                items.indexOfFirst { it.file == previouslyLoaded }
            caretName != null -> items.indexOfFirst { it.file.name.contains(caretName, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
            else -> 0
        }
        nextSelectionTrigger = "automatic"
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        list.selectedValue?.let { loadComparison(it, "automatic") }
    }

    // Clears the "loading" spinner for a list item whose file has just been downloaded, in place,
    // so the list reflects the ready reference without rebuilding it or waiting for a manual refresh.
    private fun markItemLoaded(file: File) {
        for (index in 0 until listModel.size()) {
            val item = listModel.getElementAt(index)
            if (item.file == file && item.isLoading) {
                listModel.setElementAt(item.copy(isLoading = false), index)
                break
            }
        }
        listRenderer.clearScaledCache()
        list.repaint()
    }

    private fun updateStatusLegend(items: List<ExtraComparisonItem>) {
        statusLegendLabel.isVisible = items.any {
            it.status == ExtraComparisonItemStatus.MODIFIED || it.status == ExtraComparisonItemStatus.NEW
        }
    }

    private fun emitScan(
        items: List<ExtraComparisonItem>,
        trigger: String,
        started: Long,
        result: String,
        blocker: String,
        cacheHit: Boolean,
    ) {
        val source = selectedSource()
        if (source.extra != null) return
        telemetry.event(
            "product.scan_completed",
            mapOf(
                "trigger" to trigger,
                "scope" to selectedScope().wireValue(),
                "source" to source.wireValue(),
                "result" to result,
                "blocker" to blocker,
                "duration_bucket" to TelemetryBuckets.duration((System.nanoTime() - started) / 1_000_000),
                "item_count_bucket" to TelemetryBuckets.count(items.size),
                "modified_count_bucket" to TelemetryBuckets.count(
                    items.count { it.status == ExtraComparisonItemStatus.MODIFIED },
                ),
                "new_count_bucket" to TelemetryBuckets.count(
                    items.count { it.status == ExtraComparisonItemStatus.NEW },
                ),
                "cache_hit" to cacheHit.toString(),
            ),
        )
    }

    private fun reportEditorSelection() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        telemetry.sourceFileSelected(
            memoryKey = file.path,
            trigger = "ide_editor",
            fileFamily = when (file.extension.orEmpty().lowercase()) {
                "kt", "kts" -> "kotlin"
                "java" -> "java"
                "js", "jsx", "ts", "tsx" -> "js_ts"
                "swift" -> "swift"
                "png" -> "png"
                else -> "other"
            },
            alreadyOpen = true,
        )
    }

    private fun reportOperationFailure(operation: String, error: Throwable, retryable: Boolean) {
        telemetry.captureException(error, "plugin:$operation", project.basePath?.let(::File))
        val properties = mutableMapOf(
            "operation" to operation,
            "error_category" to when (error) {
                is java.io.IOException -> "io"
                is IllegalArgumentException -> "invalid_config"
                else -> "internal"
            },
            "retryable" to retryable.toString(),
        )
        val source = selectedSource()
        if (source.extra == null) {
            properties["scope"] = selectedScope().wireValue()
            properties["source"] = source.wireValue()
        }
        telemetry.event("product.operation_failed", properties)
    }

    private fun emitComparisonViewed(
        head: java.awt.image.BufferedImage?,
        working: java.awt.image.BufferedImage?,
        unchanged: Boolean,
        diffRatio: Double?,
        source: ComparisonSource,
        selectionTrigger: String,
        started: Long,
    ) {
        if (source.extra != null) return
        val result = when {
            head == null && working == null -> "decode_failed"
            unchanged -> "identical"
            head == null -> "new"
            working == null -> "missing_counterpart"
            else -> "modified"
        }
        telemetry.event(
            "product.comparison_viewed",
            mapOf(
                "source" to source.wireValue(),
                "result" to result,
                "load_duration_bucket" to TelemetryBuckets.duration(
                    (System.nanoTime() - started) / 1_000_000,
                ),
                "diff_ratio_bucket" to TelemetryBuckets.diffRatio(diffRatio, head != null && working != null),
                "dimensions" to when {
                    head == null || working == null -> "one_missing"
                    head.width == working.width && head.height == working.height -> "same"
                    else -> "different"
                },
                "cache_hit" to "false",
                "selection_trigger" to selectionTrigger,
            ),
        )
        telemetry.activationCompleted(selectedScope().wireValue(), source.wireValue())
    }

    // Clears the comparison viewer and its cached selection so no stale preview lingers when the
    // current file has no goldens.
    private fun clearComparison() {
        loadedFile = null
        loadedSource = selectedSource()
        compareView.setTitle(null)
        compareView.showSingle(null, "")
    }

    private fun loadComparison(
        item: ExtraComparisonItem,
        selectionTrigger: String = "grid",
        keepCurrentPreview: Boolean = false,
    ) {
        val file = item.file
        val source = selectedSource()
        if (file == loadedFile && source == loadedSource) return
        loadedFile = file
        loadedSource = source
        compareView.setTitle(shortGoldenName(file.name))
        if (!keepCurrentPreview) compareView.showSingle(null, "Loading…")
        val acceptedAtLoad = source == ComparisonSource.GENERATED &&
            file.absolutePath in acceptedGoldens
        val extra = source.extra
        if (extra != null) {
            val screen = currentScreen
            if (screen == null) {
                compareView.showSingle(null, "Open a Kotlin screen or test file.")
                return
            }
            if (!file.isFile) {
                // First load renders the frame on Figma's servers and can take tens of seconds;
                // say so instead of a bare "Loading…" that reads like a freeze.
                compareView.showSingle(null, "Fetching ${item.title} from Figma… (first load renders on Figma's servers)")
            }
            AppExecutorUtil.getAppExecutorService().execute {
                val result = extra.loadComparison(project, file, screen, settings)
                ApplicationManager.getApplication().invokeLater {
                    if (loadedFile != file || loadedSource != source) return@invokeLater
                    listRenderer.clearScaledCache()
                    list.repaint()
                    when (result) {
                        is ExtraComparisonResult.Single ->
                            if (result.image == null) {
                                // Load/download failed: offer an explicit retry instead of
                                // leaving the user to hunt for the refresh action.
                                compareView.showRetry(result.statusText) {
                                    loadedFile = null
                                    loadComparison(item)
                                }
                            } else {
                                compareView.showSingle(result.image, result.statusText)
                            }
                        is ExtraComparisonResult.Comparison -> {
                            compareView.showComparison(
                                old = result.old,
                                new = result.new,
                                statusText = result.statusText,
                                oldLabel = result.oldLabel,
                                newLabel = result.newLabel,
                            )
                            // The reference was just downloaded, so the list item's loading spinner
                            // is now stale — clear it in place so the view settles without a manual refresh.
                            markItemLoaded(file)
                        }
                    }
                }
            }
            return
        }
        val comparisonStarted = System.nanoTime()
        val comparisonSpan = telemetry.startSpan(
            "comparison.load",
            mapOf(
                "scope" to selectedScope().wireValue(),
                "source" to source.wireValue(),
                "cache_hit" to "false",
            ),
        )
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val headBytes = telemetry.measureSpan(
                    "git.head_read",
                    mapOf("scope" to selectedScope().wireValue(), "source" to source.wireValue()),
                ) { headBytesSource.headBytes(file) }
                val workingFile = when (source) {
                    ComparisonSource.WORKING_COPY -> file
                    ComparisonSource.GENERATED if acceptedAtLoad -> file
                    ComparisonSource.GENERATED -> telemetry.measureSpan(
                        "generated.lookup",
                        mapOf("scope" to selectedScope().wireValue(), "source" to "test_output"),
                    ) {
                        GeneratedImageSource.findForGolden(
                            golden = file,
                            goldenRoots = settings.resolvedPaths(project),
                            generatedRoots = settings.resolvedGeneratedPaths(project),
                            generatedFileRegex = settings.generatedFileRegex,
                            excludedSuffixes = settings.excludedSuffixes,
                        )
                    }
                    else -> null
                }
                val workingBytes = workingFile?.let(ImageBytes::workingBytes)
                val trim = settings.trimTransparentPadding
                val (head, working) = telemetry.measureSpan(
                    "image.decode",
                    mapOf("scope" to selectedScope().wireValue(), "source" to source.wireValue()),
                ) {
                    ImagePainting.trimTransparentBorder(ImageBytes.decode(headBytes), trim) to
                        ImagePainting.trimTransparentBorder(ImageBytes.decode(workingBytes), trim)
                }
                val unchanged = !acceptedAtLoad &&
                    headBytes != null &&
                    workingBytes != null &&
                    headBytes.contentEquals(workingBytes)
                val diffRatio = if (!acceptedAtLoad && !unchanged && head != null && working != null) {
                    telemetry.measureSpan(
                        "pixel_diff.compute",
                        mapOf("scope" to selectedScope().wireValue(), "source" to source.wireValue()),
                    ) {
                        PixelDiff.compute(head.toArgbImage(), working.toArgbImage())?.changedRatio
                    }
                } else {
                    if (unchanged) 0.0 else null
                }
                ApplicationManager.getApplication().invokeLater {
                    if (loadedFile != file || loadedSource != source) {
                        comparisonSpan.finish(TelemetrySpanStatus.CANCELLED)
                        return@invokeLater
                    }
                    when {
                        acceptedAtLoad ->
                            compareView.showAccepted(working, "Accepted as golden \u2014 ${file.name}")
                        source == ComparisonSource.GENERATED && !settings.hasGeneratedPaths ->
                            compareView.showSingle(head, "Configure generated test output directories in Settings.")
                        source == ComparisonSource.GENERATED && workingFile == null ->
                            compareView.showSingle(head, "No generated test output found for ${file.name}.")
                        unchanged && working != null ->
                            compareView.showSingle(working, "No changes vs HEAD — ${file.name}")
                        head != null && working != null ->
                            compareView.showComparison(
                                old = head,
                                new = working,
                                statusText = comparisonTitle(file, workingFile, source),
                                oldLabel = "HEAD",
                                newLabel = source.compareLabel,
                            )
                        head == null && working != null ->
                            compareView.showSingle(working, "New file (not in git HEAD) — ${workingFile?.name ?: file.name}")
                        head != null && working == null ->
                            compareView.showSingle(head, "Working copy missing — showing HEAD.")
                        else ->
                            compareView.showSingle(null, "Could not read image.")
                    }
                    if (source == ComparisonSource.GENERATED && workingFile != null && !acceptedAtLoad) {
                        compareView.showAccept()
                    }
                    comparisonSpan.finish()
                    emitComparisonViewed(
                        head = head,
                        working = working,
                        unchanged = unchanged,
                        diffRatio = diffRatio,
                        source = source,
                        selectionTrigger = selectionTrigger,
                        started = comparisonStarted,
                    )
                }
            } catch (error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    if (loadedFile != file || loadedSource != source) return@invokeLater
                    comparisonSpan.finish(TelemetrySpanStatus.ERROR)
                    reportOperationFailure("comparison_load", error, retryable = true)
                    compareView.showRetry("Could not read image.") {
                        loadedFile = null
                        loadComparison(item, selectionTrigger)
                    }
                }
            }
        }
    }

    private fun selectedSource(): ComparisonSource =
        sourceCombo.selectedItem as? ComparisonSource ?: ComparisonSource.WORKING_COPY

    private fun selectedScope(): Scope =
        scopeCombo.selectedItem as? Scope ?: Scope.CURRENT_FILE

    private fun comparisonTitle(golden: File, workingFile: File?, source: ComparisonSource): String =
        when (source) {
            ComparisonSource.WORKING_COPY -> golden.name
            ComparisonSource.GENERATED -> "${golden.name} ↔ ${workingFile?.name ?: "generated output"}"
            else -> golden.name
        }

    override fun dispose() {
        // messageBus connection and caret listener are tied to this Disposable and cleaned up here.
        refreshTimer.stop()
        pngRefreshTimer.stop()
        telemetryService.panelClosed()
    }

    companion object {
        private const val DEBOUNCE_MS = 300
        private const val PNG_DEBOUNCE_MS = 400
        private const val PNG_SUPPRESSION_MS = 2_000L
        private const val GOLDEN_LIST_POPUP_PLACE = "GoldenDiffListPopup"
        private const val BASE_THUMBNAIL_CELL_WIDTH = 300
        private val THUMBNAIL_SCALES = listOf(1.0, 0.85, 0.70, 0.55, 0.40, 0.30, 0.22)
        private const val STATUS_LEGEND_HTML =
            "<html><span style='color:#D36A75'>●</span> Changed vs HEAD&nbsp;&nbsp;&nbsp;" +
                "<span style='color:#57A869'>●</span> New in working copy</html>"
    }

    private data class ComparisonSource(
        val id: String,
        private val title: String,
        val extra: ExtraComparisonSource? = null,
    ) {
        val compareLabel: String get() = extra?.compareLabel ?: title
        fun wireValue(): String =
            if (this == GENERATED) "test_output" else "working_copy"

        override fun toString(): String = title

        companion object {
            val WORKING_COPY = ComparisonSource("working-copy", "Working copy")
            val GENERATED = ComparisonSource("generated", "Test output")
            val builtIns = listOf(WORKING_COPY, GENERATED)

            fun extra(source: ExtraComparisonSource): ComparisonSource =
                ComparisonSource(source.id, source.title, source)
        }
    }

    private data class PngVfsChange(val file: File, val structural: Boolean)

    private fun thumbnailScale(): Double = THUMBNAIL_SCALES[thumbnailScaleIndex]
}

private enum class Scope(private val title: String) {
    CURRENT_FILE("Current file"),
    PROJECT_CHANGES("Project changes");

    override fun toString(): String = title

    fun wireValue(): String =
        if (this == CURRENT_FILE) "current_file" else "project_changes"
}

private class ThumbnailScaleIcon(
    private val isPlus: Boolean,
) : Icon {
    private val size = JBUI.scale(16)

    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size

    override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = if (c?.isEnabled == false) {
                JBUI.CurrentTheme.Label.disabledForeground()
            } else {
                JBUI.CurrentTheme.Label.foreground()
            }
            val centerX = x + iconWidth / 2
            val centerY = y + iconHeight / 2
            val half = JBUI.scale(5)
            g2.drawLine(centerX - half, centerY, centerX + half, centerY)
            if (isPlus) {
                g2.drawLine(centerX, centerY - half, centerX, centerY + half)
            }
        } finally {
            g2.dispose()
        }
    }
}

private class BottomRightOverlayPanel(
    private val content: JComponent,
    private val overlay: JComponent,
) : JPanel(null) {
    init {
        add(content)
        add(overlay)
        setComponentZOrder(overlay, 0)
        isOpaque = false
        content.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = positionOverlay()
                override fun componentMoved(e: ComponentEvent) = positionOverlay()
            },
        )
        (content as? JScrollPane)?.let { scrollPane ->
            scrollPane.horizontalScrollBar.addAdjustmentListener { positionOverlay() }
            scrollPane.verticalScrollBar.addAdjustmentListener { positionOverlay() }
            scrollPane.viewport.addChangeListener { positionOverlay() }
        }
    }

    override fun doLayout() {
        content.setBounds(0, 0, width, height)
        positionOverlay()
    }

    private fun positionOverlay() {
        val overlaySize = overlay.preferredSize
        val margin = JBUI.scale(12)
        overlay.setBounds(
            (width - overlaySize.width - margin).coerceAtLeast(margin),
            (height - overlaySize.height - margin).coerceAtLeast(margin),
            overlaySize.width,
            overlaySize.height,
        )
        overlay.repaint()
    }

    override fun getPreferredSize(): Dimension = content.preferredSize

    override fun getMinimumSize(): Dimension = content.minimumSize
}

private class WrapLayout(
    align: Int,
    hgap: Int,
    vgap: Int,
) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = false).apply { width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
            val maxWidth = if (target.width > 0) {
                target.width - horizontalInsetsAndGap
            } else {
                Int.MAX_VALUE
            }
            val dimension = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (index in 0 until target.componentCount) {
                val component = target.getComponent(index)
                if (!component.isVisible) continue
                val size = if (preferred) component.preferredSize else component.minimumSize
                if (rowWidth > 0 && rowWidth + hgap + size.width > maxWidth) {
                    addRow(dimension, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth > 0) rowWidth += hgap
                rowWidth += size.width
                rowHeight = maxOf(rowHeight, size.height)
            }

            addRow(dimension, rowWidth, rowHeight)
            dimension.width += horizontalInsetsAndGap
            dimension.height += insets.top + insets.bottom + vgap * 2
            return dimension
        }
    }

    private fun addRow(dimension: Dimension, rowWidth: Int, rowHeight: Int) {
        dimension.width = maxOf(dimension.width, rowWidth)
        if (dimension.height > 0) dimension.height += vgap
        dimension.height += rowHeight
    }
}
