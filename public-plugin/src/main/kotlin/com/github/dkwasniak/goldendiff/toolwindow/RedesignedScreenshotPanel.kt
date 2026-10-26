package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.command.CommandTemplate
import com.github.dkwasniak.goldendiff.command.CompletionCommandKind
import com.github.dkwasniak.goldendiff.command.CompletionCommandScope
import com.github.dkwasniak.goldendiff.command.CompletionNotificationPolicy
import com.github.dkwasniak.goldendiff.command.CompletionResult
import com.github.dkwasniak.goldendiff.command.GoldenAccept
import com.github.dkwasniak.goldendiff.compare.CompareView
import com.github.dkwasniak.goldendiff.compare.GeneratedCounterpartResolution
import com.github.dkwasniak.goldendiff.compare.ImageBytes
import com.github.dkwasniak.goldendiff.compare.ImagePainting
import com.github.dkwasniak.goldendiff.compare.PixelDiff
import com.github.dkwasniak.goldendiff.compare.toArgbImage
import com.github.dkwasniak.goldendiff.git.GitCli
import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.match.GenericScreenExtractor
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.project.ProjectFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.scan.ChangeScanner
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.scan.MergedGoldenStatus
import com.github.dkwasniak.goldendiff.scan.automaticBuiltInSource
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
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
import com.github.dkwasniak.goldendiff.watch.PNG_REFRESH_DEBOUNCE_MS
import com.github.dkwasniak.goldendiff.watch.PngChangeEvent
import com.github.dkwasniak.goldendiff.watch.PngChangeKind
import com.github.dkwasniak.goldendiff.watch.PngRefreshMode
import com.github.dkwasniak.goldendiff.watch.SnapshotLoadState
import com.github.dkwasniak.goldendiff.watch.pngRefreshMode
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * Snapshot-aware public-plugin Tool Window: a Generate/Verify toolbar, Files / Review tabs and a
 * status bar. It deliberately shares only models with the standalone app; all rendering remains
 * Swing/JBUI for the plugin's open-ended 241+ compatibility range. Durable UI state lives in
 * [PluginSnapshotController]; this class owns I/O, platform listeners and rendering.
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

    private val filesList = FilesListPanel(FilesActions())
    private val strip = GoldenStripPanel(
        trimTransparentPadding = { settings.trimTransparentPadding },
        statusOf = ::displayStatusOf,
        onSelect = { gridId, trigger ->
            nextSelectionTrigger = trigger
            controller.selectGridItem(gridId)
        },
        onContextMenu = ::showGoldenContextMenu,
    )
    private var comparisonGeneration = 0L
    private var lastComparisonKey: ComparisonKey? = null
    private val resolvedAcceptCandidates = HashMap<String, GoldenAccept.FrozenCandidate>()
    private var acceptingGoldenId: String? = null
    private val compare = createCompareView(detachable = true)
    private var detachedWindow: JDialog? = null

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
    private val runningIcon = AsyncProcessIcon("Golden Diff command").apply {
        isVisible = false
        suspend()
    }
    private val stopButton = iconButton(AllIcons.Actions.Suspend, "Stop") { activeBatch?.cancel() }.apply {
        isVisible = false
    }
    private val refreshButton = iconButton(AllIcons.Actions.Refresh, "Refresh snapshots") {
        refresh(force = true, trigger = "manual_refresh")
    }

    private val tabsHeader = ReviewTabsHeader(controller::showTab)
    private val prevFile = navButton("‹", "Previous file") { controller.stepView(-1) }
    private val nextFile = navButton("›", "Next file") { controller.stepView(1) }
    private val fileName = JBLabel().apply {
        font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scaleFontSize(12f))
        foreground = GoldenDiffColors.text
    }
    private val filePosition = JBLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
        foreground = GoldenDiffColors.faint
    }
    private val viewStatus = PillLabel()
    private val filesToggle = FlatHeaderButton("Files ▾", height = 24, horizontalPadding = 8).apply {
        toolTipText = "Show files"
        addActionListener { controller.toggleFilesOverlay() }
    }
    private val navRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = true
        background = GoldenDiffColors.background
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(GoldenDiffColors.border, 0, 0, 1, 0),
            JBUI.Borders.empty(4, 6, 4, 8),
        )
        add(prevFile, BorderLayout.WEST)
        add(fileName, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(viewStatus)
                add(filePosition)
                add(nextFile)
                add(filesToggle)
            },
            BorderLayout.EAST,
        )
    }
    private val reviewContent = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = GoldenDiffColors.background
        add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(navRow)
                add(strip)
            },
            BorderLayout.NORTH,
        )
        add(compare, BorderLayout.CENTER)
    }
    private val reviewLayer = FilesOverlayLayer(reviewContent, navRow) { controller.closeFilesOverlay() }
    private val filesTab = JPanel(BorderLayout()).apply { isOpaque = true; background = GoldenDiffColors.panel }
    private val tabCards = JPanel(CardLayout()).apply {
        add(filesTab, ReviewTab.FILES.name)
        add(reviewLayer, ReviewTab.REVIEW.name)
    }
    private val statusText = JBLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(10f).toFloat())
        border = JBUI.Borders.empty(0, 8)
    }
    private var commandMessage: String? = null
    private val statusBar = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = GoldenDiffColors.background
        border = JBUI.Borders.customLine(GoldenDiffColors.border, 1, 0, 0, 0)
        preferredSize = Dimension(0, JBUI.scale(24))
        add(buildVersionFooter(), BorderLayout.WEST)
        add(statusText, BorderLayout.EAST)
    }

    private var commandRunning = false
    private var runningKind: CommandKind? = null
    private var generateTarget = CommandTarget.PROJECT
    private var verifyTarget = CommandTarget.PROJECT
    private var activeBatch: BatchCommandExecution.Handle? = null
    private var nextSelectionTrigger = "automatic"
    private var lastReportedComparisonKey: String? = null
    private val pendingVfsEvents = mutableListOf<PngChangeEvent>()
    private var pendingVfsForceRescan = false
    private val suppressedVfsPaths = mutableMapOf<String, Long>()
    private val vfsTimer = Timer(PNG_REFRESH_DEBOUNCE_MS.toInt()) { onVfsTimerFired() }
        .apply { isRepeats = false }
    private val commandMessageTimer = Timer(4000) { setCommandMessage(null) }.apply { isRepeats = false }

    init {
        telemetry.panelOpened(project)
        border = JBUI.Borders.empty()
        background = GoldenDiffColors.background
        add(buildToolbar(), BorderLayout.NORTH)
        add(
            JPanel(BorderLayout()).apply {
                add(tabsHeader, BorderLayout.NORTH)
                add(tabCards, BorderLayout.CENTER)
            },
            BorderLayout.CENTER,
        )
        add(statusBar, BorderLayout.SOUTH)
        reviewLayer.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "files.overlay.close")
        reviewLayer.actionMap.put("files.overlay.close", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) = controller.closeFilesOverlay()
        })
        com.intellij.openapi.util.Disposer.register(this, runningIcon)
        installGearActions()
        subscribe()
        reportEditorSelection()
        renderState(controller.state)
        refresh(force = true, trigger = "automatic")
    }

    private fun buildToolbar(): JPanel =
        JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = true
            background = GoldenDiffColors.background
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(GoldenDiffColors.border, 0, 0, 1, 0),
                JBUI.Borders.empty(0, 4),
            )
            preferredSize = Dimension(0, JBUI.scale(38))
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(7))).apply {
                    isOpaque = false
                    add(commandScopeControl(generateProject, generateScope))
                    add(commandScopeControl(verifyProject, verifyScope))
                },
                BorderLayout.WEST,
            )
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(7))).apply {
                    isOpaque = false
                    add(runningIcon)
                    add(stopButton)
                    add(refreshButton)
                },
                BorderLayout.EAST,
            )
        }

    /** Settings and the actions with no place in the compact layout live in the tool window's ⋮ menu. */
    private fun installGearActions() {
        val group = DefaultActionGroup().apply {
            add(panelAction("Settings…", AllIcons.General.Settings) { openSettings() })
            add(panelAction("Refresh", AllIcons.Actions.Refresh) { refresh(force = true, trigger = "manual_refresh") })
            add(Separator.getInstance())
            add(object : DumbAwareAction("Accept All in File") {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun update(event: AnActionEvent) {
                    val count = acceptAllCandidates().size
                    event.presentation.isEnabled = count > 0 && !commandRunning
                    event.presentation.text = if (count > 0) "Accept All in File ($count)" else "Accept All in File"
                }

                override fun actionPerformed(event: AnActionEvent) = acceptAllForSelectedView()
            })
            add(object : DumbAwareToggleAction("Show Only Reference Goldens") {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun update(event: AnActionEvent) {
                    super.update(event)
                    val source = controller.state.extraFilterSource
                    event.presentation.isVisible = source != null
                    event.presentation.text = "Show Only ${source?.title ?: "Reference"} Goldens"
                    event.presentation.icon = source?.filterIcon
                }

                override fun isSelected(event: AnActionEvent): Boolean = controller.state.extraOnly

                override fun setSelected(event: AnActionEvent, state: Boolean) = controller.setExtraOnly(state)
            })
            add(panelAction("Open Comparison in Separate Window") { openDetachedComparison() })
        }
        (toolWindow as? ToolWindowEx)?.setAdditionalGearActions(group)
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().editConfigurable(project, ScreenshotConfigurable(project))
        refresh(force = true, trigger = "config_change")
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
        renderAcceptState()
    }

    private fun detachedWindowTitle(): String =
        controller.state.selectedGridItem?.let {
            when (it) {
                is GoldenGridItem.BuiltIn -> it.item.golden.name
                is GoldenGridItem.Extension -> it.item.title
            }
        }?.let { "Golden Diff — $it" }
            ?: "Golden Diff — Compare"

    private fun buildVersionFooter(): JComponent {
        val service = PluginTelemetryService.getInstance()
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 10, 0, 0)
            add(JBLabel(service.appVersion).apply {
                foreground = GoldenDiffColors.faint
                font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(10f).toFloat())
            })
            service.metadata.badges.forEach { badge ->
                val color = when (badge) {
                    BuildBadge.DEV -> JBColor(Color(0xA9761A), Color(0xD9A441))
                    BuildBadge.BETA -> JBColor(Color(0x3574F0), Color(0x548AF7))
                }
                add(JBLabel(badge.label).apply {
                    foreground = color
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(8f).toFloat())
                    border = BorderFactory.createCompoundBorder(
                        RoundedLineBorder(color, JBUI.scale(5), 1),
                        JBUI.Borders.empty(0, 4),
                    )
                    accessibleContext.accessibleName = "${badge.label} build"
                })
            }
        }
    }

    private fun renderState(state: PluginSnapshotState) {
        detachedWindow?.title = detachedWindowTitle()
        tabsHeader.render(state.tab, state.failedViewCount)
        placeFilesList(state)
        val presentation = state.filesList()
        filesList.render(
            FilesListRenderState(
                presentation = presentation,
                query = state.fileQuery,
                filters = state.statusFilters,
                indexing = state.loadState == SnapshotLoadState.INITIAL_INDEXING ||
                    state.loadState == SnapshotLoadState.REFRESHING,
                hasIndex = state.index != null,
                hiddenActiveViewName = state.activeEditorPath
                    ?.takeIf { path -> state.index?.files?.any { it.relativePath == path } == true }
                    ?.takeIf { it !in presentation.viewOrder }
                    ?.let { File(it).nameWithoutExtension },
            ),
        )
        strip.render(state.visibleGridItems, state.selectedGridId)
        renderNavRow(state)
        updateCommandButtons()
        renderAcceptState()
        renderStatusText()
        val key = ComparisonKey(state.selectedGridItem, state.generation, state.loadState, state.activeFileHasSnapshots)
        if (key == lastComparisonKey) return
        lastComparisonKey = key
        if (state.selectedGridItem != null) loadSelectedComparison() else clearComparison(state)
    }

    private fun placeFilesList(state: PluginSnapshotState) {
        val overlay = state.tab == ReviewTab.REVIEW && state.filesOverlayOpen
        val target = when {
            state.tab == ReviewTab.FILES -> filesTab
            overlay -> reviewLayer.host
            else -> null
        }
        if (filesList.parent !== target) {
            detach(filesList)
            target?.add(filesList, BorderLayout.CENTER)
            target?.revalidate()
            if (overlay) ApplicationManager.getApplication().invokeLater { filesList.focusSearch() }
        }
        reviewLayer.setOverlay(overlay)
        filesToggle.setForeground(if (overlay) GoldenDiffColors.accent else GoldenDiffColors.dim)
        (tabCards.layout as CardLayout).show(tabCards, state.tab.name)
    }

    private fun renderNavRow(state: PluginSnapshotState) {
        val view = state.selectedView
        fileName.text = when {
            view == null -> when (state.loadState) {
                SnapshotLoadState.UNCONFIGURED -> "No configuration"
                SnapshotLoadState.INITIAL_INDEXING -> "Indexing snapshots…"
                SnapshotLoadState.EMPTY -> "No snapshot-related files"
                SnapshotLoadState.ERROR -> "Snapshot index unavailable"
                else -> "Select a file"
            }
            state.selectedCase != null ->
                "${File(view.relativePath).nameWithoutExtension} › ${state.selectedCase!!.methodName}"
            else -> File(view.relativePath).nameWithoutExtension
        }
        fileName.toolTipText = view?.relativePath
        // ‹ › only walk changed Views; without any there is nothing to navigate.
        val hasQueue = state.reviewQueue.isNotEmpty()
        prevFile.isVisible = hasQueue
        nextFile.isVisible = hasQueue
        filePosition.text = state.reviewPosition.orEmpty()
        filePosition.isVisible = hasQueue && state.reviewPosition != null
        when (val status = state.selectedViewStatus) {
            null -> viewStatus.setPill(null)
            GoldenDisplayStatus.UNCHANGED -> viewStatus.setPill("Unchanged", GoldenDiffColors.faint, GoldenDiffColors.disabledFill)
            else -> viewStatus.showStatus(status)
        }
    }

    private fun displayStatusOf(item: GoldenGridItem): GoldenDisplayStatus = when (item) {
        is GoldenGridItem.BuiltIn -> controller.state.goldenDisplayStatus(item.item.golden.path)
            ?: item.item.displayStatus.toDisplayStatus()
        is GoldenGridItem.Extension -> item.item.status.toDisplayStatus()
    }

    private fun renderAcceptState() {
        val state = controller.state
        val item = state.selectedGridItem
        val goldenId = (item as? GoldenGridItem.BuiltIn)?.item?.golden?.normalize()?.path
        val accepted = goldenId != null && goldenId in state.acceptedGoldens
        val base = acceptButtonState(item, accepted, accepting = goldenId != null && goldenId == acceptingGoldenId)
        // Accept writes the exact Test output shown, so it waits until that output is frozen.
        compare.setAcceptState(
            if (base == AcceptButtonState.ENABLED && goldenId !in resolvedAcceptCandidates) {
                AcceptButtonState.DISABLED
            } else {
                base
            },
        )
    }

    private fun renderStatusText() {
        val message = commandMessage
        if (message != null) {
            statusText.text = message
            statusText.foreground = GoldenDiffColors.brand
            return
        }
        when (val item = controller.state.selectedGridItem) {
            null -> statusText.text = ""
            is GoldenGridItem.Extension -> {
                statusText.text = "${item.source.title} reference"
                statusText.foreground = GoldenDiffColors.dim
            }
            is GoldenGridItem.BuiltIn -> {
                val status = displayStatusOf(item)
                statusText.text = status.statusText
                statusText.foreground = GoldenDiffColors.status(status)
            }
        }
    }

    private fun setCommandMessage(message: String?, transient: Boolean = false) {
        commandMessage = message
        if (transient) commandMessageTimer.restart() else commandMessageTimer.stop()
        renderStatusText()
    }

    private fun pickFilesSelection(selection: SnapshotFilesSelection) {
        nextSelectionTrigger = "automatic"
        when (selection) {
            is SnapshotFilesSelection.View -> controller.openView(selection.sourcePath)
            is SnapshotFilesSelection.Case -> controller.openCase(selection.sourcePath, selection.caseId)
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
    }

    private inner class FilesActions : FilesListPanel.Actions {
        override fun queryChanged(query: String) = controller.setFileQuery(query)
        override fun filterChanged(status: SnapshotFileStatus, enabled: Boolean) =
            controller.setStatusFilter(status, enabled)
        override fun folderToggled(path: String, collapsed: Boolean) = controller.setFolderCollapsed(path, collapsed)
        override fun viewExpansionToggled(viewId: String, expanded: Boolean) =
            controller.setViewExpanded(viewId, expanded)
        override fun picked(selection: SnapshotFilesSelection) = pickFilesSelection(selection)
        override fun expandAll() = controller.expandAllFolders()
        override fun collapseAll() = controller.collapseAllFolders()
        override fun clearSearchAndFilters() = controller.clearFileFilters()

        override fun revealActive() {
            controller.clearFileFilters()
            activeEditorPath()?.let(controller::selectView)
        }

        override fun contextMenu(selection: SnapshotFilesSelection, component: Component, x: Int, y: Int) {
            // Commands in the menu act on the selected View/Case, so select without leaving the list.
            when (selection) {
                is SnapshotFilesSelection.View -> controller.selectView(selection.sourcePath)
                is SnapshotFilesSelection.Case -> controller.selectCase(selection.sourcePath, selection.caseId)
            }
            showFilesContextMenu(selection, component, x, y)
        }
    }

    private fun showGoldenContextMenu(item: GoldenGridItem, component: Component, x: Int, y: Int) {
        controller.selectGridItem(item.id)
        val popup = StyledContextMenu()
        popup.addHeader(
            when (item) {
                is GoldenGridItem.BuiltIn -> item.item.golden.name
                is GoldenGridItem.Extension -> item.item.title
            },
        )
        popup.addSectionSeparator()
        if (item is GoldenGridItem.BuiltIn) {
            popup.addAction(
                text = "Accept",
                enabled = item.item.hasAcceptableTestOutput && !commandRunning,
            ) { acceptGolden(item.item) }
            val candidates = acceptAllCandidates()
            popup.addAction(
                text = "Accept All in File (${candidates.size})",
                enabled = candidates.isNotEmpty() && !commandRunning,
            ) { acceptAllForSelectedView() }
            popup.addSectionSeparator()
        }
        popup.addAction("Show in File Manager", AllIcons.Actions.MenuOpen) {
            telemetry.event("product.feature_used", mapOf("feature" to "reveal_in_file_manager"))
            RevealFileAction.openFile(item.file)
        }
        popup.addAction("Copy Absolute Path", AllIcons.Actions.Copy) {
            telemetry.event("product.feature_used", mapOf("feature" to "copy_path"))
            CopyPasteManager.getInstance().setContents(StringSelection(item.file.absolutePath))
        }
        popup.show(component, x, y)
    }

    private fun acceptAllCandidates(): List<MergedGoldenItem> {
        val view = controller.state.selectedView ?: return emptyList()
        val byPath = controller.state.mergedItems.associateBy { it.golden.normalize().path }
        return view.goldenPaths.mapNotNull { path -> byPath[path]?.takeIf(MergedGoldenItem::hasAcceptableTestOutput) }
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

    private fun clearComparison(state: PluginSnapshotState) {
        if (state.loadState == SnapshotLoadState.UNCONFIGURED) {
            compare.showBlocked(
                "No configuration",
                "Choose at least one golden directory to start indexing snapshots.",
                "Open Settings",
            ) { openSettings() }
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
                renderAcceptState()
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
                        strip.invalidate(item.item.file)
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
                "Refreshing the comparison before accepting this Test output…",
            )
            refresh(force = true, trigger = "stale_source_recovery")
            return
        }
        val goldenId = item.golden.normalize().path
        acceptingGoldenId = goldenId
        renderAcceptState()
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
                if (result !is GoldenAccept.ResolvedOutcome.Success) acceptingGoldenId = null
                when (result) {
                    is GoldenAccept.ResolvedOutcome.Success -> {
                        resolvedAcceptCandidates.remove(goldenId)
                        suppressVfsEvent(item.golden)
                        suppressVfsEvent(generated)
                        LocalFileSystem.getInstance().refreshIoFiles(listOf(item.golden, generated))
                        controller.acceptSucceeded(listOf(goldenId))
                        val root = projectRoot
                        if (root != null) {
                            applyMergedItemUpdatesInPlace(root, settings.toConfig(), setOf(item.golden)) {
                                if (acceptingGoldenId == goldenId) acceptingGoldenId = null
                                renderAcceptState()
                            }
                        } else {
                            acceptingGoldenId = null
                        }
                        renderAcceptState()
                    }
                    is GoldenAccept.ResolvedOutcome.NothingToAccept -> {
                        renderAcceptState()
                        compare.showSingle(null, "Nothing to accept")
                    }
                    is GoldenAccept.ResolvedOutcome.Ambiguous ->
                        compare.showBlocked(
                            "Ambiguous Test output",
                            "More than one generated file maps to this golden. Resolve the mapping in Settings.",
                        )
                    is GoldenAccept.ResolvedOutcome.StaleSource -> {
                        compare.showBlocked(
                            "Test output changed",
                            "The displayed Test output changed or moved. Refreshing…",
                        )
                        refresh(force = true, trigger = "stale_source_recovery")
                    }
                    is GoldenAccept.ResolvedOutcome.Io -> {
                        renderAcceptState()
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
        val candidates = acceptAllCandidates()
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
        setCommandMessage("Accepting 0 of ${candidates.size}…")
        val displayedCandidates = resolvedAcceptCandidates.toMap()
        AppExecutorUtil.getAppExecutorService().execute {
            val frozen = freezeAcceptAllCandidates(candidates, displayedCandidates)
            val result = GoldenAccept.acceptAllResolved(frozen) { done, total ->
                ApplicationManager.getApplication().invokeLater {
                    setCommandMessage("Accepting $done of $total…")
                }
            }
            ApplicationManager.getApplication().invokeLater {
                setCommandMessage(null)
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
                controller.acceptSucceeded(result.accepted.map { it.golden.normalize().path })
                result.accepted.forEach { accepted ->
                    suppressVfsEvent(accepted.golden)
                    (accepted.source as? GoldenAccept.FrozenSource.Exact)?.generated?.let(::suppressVfsEvent)
                }
                LocalFileSystem.getInstance().refreshIoFiles(
                    candidates.flatMap { listOfNotNull(it.golden, it.testOutput?.file) },
                )
                val root = projectRoot
                if (root != null && result.accepted.isNotEmpty()) {
                    applyMergedItemUpdatesInPlace(
                        root,
                        settings.toConfig(),
                        result.accepted.mapTo(linkedSetOf()) { it.golden },
                    )
                }
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
        runningKind = kind
        setCommandMessage("${kind.label} · $scope · ${commands.size} commands")
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
                    runningKind = null
                    activeBatch = null
                    setCommandMessage(
                        when (outcome) {
                            is BatchCommandOutcome.Success -> "${kind.label} completed"
                            is BatchCommandOutcome.Failure -> "${kind.label} failed"
                            is BatchCommandOutcome.PartialFailure -> "${kind.label} partially failed"
                            is BatchCommandOutcome.Cancelled -> "${kind.label} cancelled"
                        },
                        transient = true,
                    )
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
                }
            },
        )
        if (activeBatch == null) {
            commandRunning = false
            runningKind = null
            setCommandMessage(null)
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
        runningKind = kind
        setCommandMessage("${kind.label} · $scope")
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
                    runningKind = null
                    setCommandMessage(
                        when (it) {
                            0 -> "${kind.label} completed"
                            null -> "${kind.label} could not start"
                            else -> "${kind.label} failed (exit $it)"
                        },
                        transient = true,
                    )
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
                }
            },
        )
        if (!started) {
            commandRunning = false
            runningKind = null
            setCommandMessage(null)
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
        generateProject.text = if (runningKind == CommandKind.GENERATE) "Generating…" else CommandKind.GENERATE.label
        verifyProject.text = if (runningKind == CommandKind.VERIFY) "Verifying…" else CommandKind.VERIFY.label
        runningIcon.isVisible = commandRunning
        if (commandRunning) runningIcon.resume() else runningIcon.suspend()
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
                    val now = System.currentTimeMillis()
                    suppressedVfsPaths.entries.removeIf { it.value < now }
                    var matched = false
                    val externalGoldens = mutableListOf<String>()
                    events.forEach { event ->
                        val file = File(event.path)
                        if (!file.extension.equals("png", ignoreCase = true)) return@forEach
                        if (!config.containsGoldenFile(root, file) && !config.containsGeneratedFile(root, file)) {
                            return@forEach
                        }
                        if (suppressedVfsPaths.remove(file.normalize().path) != null) return@forEach
                        if (config.containsGoldenFile(root, file)) externalGoldens += file.normalize().path
                        matched = true
                        when (val kind = classifyVfsEvent(event)) {
                            null -> pendingVfsForceRescan = true
                            else -> pendingVfsEvents += PngChangeEvent(file, kind)
                        }
                    }
                    if (externalGoldens.isNotEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            controller.goldensChangedExternally(externalGoldens)
                        }
                    }
                    if (matched) vfsTimer.restart()
                }
            },
        )
        ExtraComparisonSources.addChangeListener(this) {
            ApplicationManager.getApplication().invokeLater { refreshExtraItems() }
        }
    }

    /** Null means the platform event doesn't map to a single classified change (e.g. a rename/move)
     * and the caller should force a rescan instead of guessing at a granular kind. */
    private fun classifyVfsEvent(event: VFileEvent): PngChangeKind? = when (event) {
        is VFileContentChangeEvent -> PngChangeKind.MODIFIED
        is VFileCreateEvent, is VFileCopyEvent -> PngChangeKind.CREATED
        is VFileDeleteEvent -> PngChangeKind.DELETED
        is VFileMoveEvent, is VFilePropertyChangeEvent -> null
        else -> null
    }

    private fun onVfsTimerFired() {
        val events = pendingVfsEvents.toList()
        val forceRescan = pendingVfsForceRescan
        pendingVfsEvents.clear()
        pendingVfsForceRescan = false
        // Generate/Verify may write hundreds of files over several seconds. Their completion path
        // already performs one refresh, so intermediate watcher batches would only waste work and
        // risk racing a rescan against partially written files.
        if (commandRunning) return
        applyPngEvents(events, forceRescan)
    }

    private fun applyPngEvents(events: List<PngChangeEvent>, overflow: Boolean) {
        val root = projectRoot ?: return
        when (pngRefreshMode(events, overflow)) {
            PngRefreshMode.NONE -> return
            PngRefreshMode.RESCAN -> refresh(force = true, trigger = "filesystem_change")
            PngRefreshMode.IN_PLACE -> {
                val config = settings.toConfig()
                val affected = affectedGoldens(events, root, config)
                if (affected.isEmpty()) return
                applyMergedItemUpdatesInPlace(root, config, affected)
            }
        }
    }

    /** Maps watcher events to the golden files whose merged status needs re-scanning: a direct
     * golden-file change, or — for a changed generated file — the golden it is the counterpart of. */
    private fun affectedGoldens(events: List<PngChangeEvent>, root: File, config: GoldenDiffConfig): Set<File> {
        val mergedItems = controller.state.mergedItems
        val goldensByPath = mergedItems.associateBy { it.golden.normalize().path }
        val affected = linkedSetOf<File>()
        events.forEach { event ->
            goldensByPath[event.file.normalize().path]?.let { affected += it.golden }
            if (config.containsGeneratedFile(root, event.file)) {
                generatedGoldenBase(event.file, config)?.let { base ->
                    mergedItems.asSequence()
                        .filter { it.golden.nameWithoutExtension.equals(base, ignoreCase = true) }
                        .mapTo(affected) { it.golden }
                }
            }
        }
        return affected
    }

    private fun generatedGoldenBase(file: File, config: GoldenDiffConfig): String? {
        val regex = runCatching { Regex(config.normalizedForMatching().generatedFileRegex) }.getOrNull() ?: return null
        val match = regex.matchEntire(file.name) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun applyMergedItemUpdatesInPlace(
        root: File,
        config: GoldenDiffConfig,
        affected: Set<File>,
        onApplied: () -> Unit = {},
    ) {
        val request = controller.beginMergedItemUpdate()
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { ChangeScanner.forGitProject(root, config).mergedItemsFor(affected.toList()) }
            ApplicationManager.getApplication().invokeLater {
                if (projectRoot != root || project.isDisposed) return@invokeLater
                result.onSuccess { updated -> controller.applyMergedItemUpdates(request, updated) }
                onApplied()
            }
        }
    }

    /** Ignores the next VFS event for [file] (an own write already reflected in state), so it does
     * not queue a redundant watcher-triggered rescan on top of an already-applied in-place update. */
    private fun suppressVfsEvent(file: File) {
        suppressedVfsPaths[file.normalize().path] = System.currentTimeMillis() + VFS_SUPPRESSION_MS
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

    override fun dispose() {
        vfsTimer.stop()
        commandMessageTimer.stop()
        comparisonGeneration++
        detachedWindow?.dispose()
        detachedWindow = null
        telemetry.panelClosed()
    }

    private enum class CommandKind(val label: String) {
        GENERATE("Generate"),
        VERIFY("Verify");

        fun telemetryKind(): TelemetryCommandKind =
            if (this == GENERATE) TelemetryCommandKind.GENERATE else TelemetryCommandKind.VERIFY
    }
    private enum class CommandTarget { PROJECT, CURRENT }

    /** Identity of what the compare area shows; unrelated state changes must not reload it. */
    private data class ComparisonKey(
        val item: GoldenGridItem?,
        val generation: Long,
        val loadState: SnapshotLoadState,
        val activeFileHasSnapshots: Boolean,
    )

    private data class LoadedBuiltInComparison(
        val frozen: GoldenAccept.FrozenCandidate?,
        val headRaw: ByteArray?,
        val sourceRaw: ByteArray?,
        val old: java.awt.image.BufferedImage?,
        val new: java.awt.image.BufferedImage?,
        val diffRatio: Double?,
    )

    private companion object {
        const val VFS_SUPPRESSION_MS = 2_000L

        fun panelAction(text: String, icon: javax.swing.Icon? = null, action: () -> Unit): DumbAwareAction =
            object : DumbAwareAction(text, null, icon) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun actionPerformed(event: AnActionEvent) = action()
            }

        fun navButton(text: String, accessibleName: String, action: () -> Unit): JButton =
            FlatHeaderButton(text, height = 24, horizontalPadding = 6).apply {
                font = font.deriveFont(JBUI.scaleFontSize(15f).toFloat())
                foreground = GoldenDiffColors.dim
                toolTipText = accessibleName
                getAccessibleContext()?.accessibleName = accessibleName
                addActionListener { action() }
            }

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
