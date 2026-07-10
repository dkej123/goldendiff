package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.compare.CompareView
import com.github.dkwasniak.goldendiff.compare.GeneratedImageSource
import com.github.dkwasniak.goldendiff.compare.GitImageSource
import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.settings.ScreenshotConfigurable
import com.github.dkwasniak.goldendiff.settings.ScreenshotSettings
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonResult
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSource
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSources
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.Timer

class ScreenshotPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : JPanel(BorderLayout()), Disposable {

    private val settings = ScreenshotSettings.getInstance(project)

    private val listModel = DefaultListModel<ExtraComparisonItem>()
    private val listRenderer = GoldenCellRenderer()
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
    private val compareView = CompareView()
    private val statusLabel = JBLabel().apply { border = JBUI.Borders.empty(4, 6) }
    private val statusLegendLabel = JBLabel(STATUS_LEGEND_HTML).apply {
        border = JBUI.Borders.empty(0, 6, 4, 6)
        isVisible = false
    }
    private val directoriesButton = JButton()
    private var thumbnailScaleIndex = 0
    private val thumbnailScaleLabel = JBLabel().apply {
        border = JBUI.Borders.emptyLeft(2)
    }
    private val thumbnailMinusButton = JButton("-").apply {
        toolTipText = "Decrease thumbnail size"
        addActionListener { changeThumbnailScale(1) }
    }
    private val thumbnailPlusButton = JButton("+").apply {
        toolTipText = "Increase thumbnail size"
        addActionListener { changeThumbnailScale(-1) }
    }
    private val comparisonSources = ComparisonSource.builtIns + ExtraComparisonSources.all.map { ComparisonSource.extra(it) }
    private val sourceCombo = ComboBox(comparisonSources.toTypedArray()).apply {
        selectedItem = ComparisonSource.WORKING_COPY
        toolTipText = "Choose whether HEAD is compared with the working-copy golden or test output"
        addActionListener {
            val previousSource = loadedSource
            val newSource = selectedSource()
            loadedFile = null
            loadedSource = newSource
            // Variant-provided sources can change what appears in the list, so force a rebuild.
            if (newSource.extra != null || previousSource.extra != null) {
                lastNames = null
                scheduleRefresh()
            } else {
                list.selectedValue?.let(::loadComparison)
            }
        }
    }

    // Debounces list refreshes from editor/caret changes; fires once on the EDT after DEBOUNCE_MS.
    private val refreshTimer = Timer(DEBOUNCE_MS) { refresh() }.apply { isRepeats = false }

    // Names the list was last built from — used to avoid rebuilding when only the caret moves.
    private var lastNames: List<String>? = null
    // Current screen model used by variant-provided comparison sources.
    private var currentScreen: CurrentScreen.Screen? = null
    // File currently shown in the comparison view, to avoid reloading the same image.
    private var loadedFile: File? = null
    private var loadedSource = ComparisonSource.WORKING_COPY
    private var pendingForce = false
    // Tracks tool-window visibility so we can refresh once it is reopened.
    private var wasVisible = toolWindow.isVisible

    init {
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
            firstComponent = listScrollPane
            secondComponent = compareView
        }
        add(buildHeader(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) list.selectedValue?.let(::loadComparison)
        }

        subscribeToEditor()
        updateThumbnailScaleControls()
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

    private fun thumbnailCellWidth(): Int {
        val viewportWidth = (list.parent?.width ?: list.width).coerceAtLeast(JBUI.scale(120))
        val targetWidth = JBUI.scale((BASE_THUMBNAIL_CELL_WIDTH * thumbnailScale()).toInt())
        val columns = (viewportWidth / targetWidth.coerceAtLeast(JBUI.scale(80))).coerceAtLeast(1)
        return (viewportWidth / columns).coerceAtLeast(JBUI.scale(80))
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
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(directoriesButton)
            add(JButton("Refresh").apply { addActionListener { scheduleRefresh(force = true) } })
            add(JBLabel("Compare:"))
            add(sourceCombo)
            add(JBLabel("Thumbnails:"))
            add(thumbnailMinusButton)
            add(thumbnailScaleLabel)
            add(thumbnailPlusButton)
        }

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
    }

    private fun onDirectoriesClicked() {
        if (settings.isConfigured) {
            ShowSettingsUtil.getInstance().editConfigurable(project, ScreenshotConfigurable(project))
            updateHeader()
            scheduleRefresh(force = true)
        } else {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Screenshot Directory")
                .withDescription("Directory that contains screenshot golden files")
            val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
            settings.paths = settings.paths + chosen.path
            updateHeader()
            scheduleRefresh(force = true)
        }
    }

    private fun updateHeader() {
        directoriesButton.text = if (settings.isConfigured) "Directories…" else "Choose screenshots directory"
    }

    private fun scheduleRefresh(force: Boolean = false) {
        if (force) pendingForce = true
        refreshTimer.restart()
    }

    private fun refresh() {
        // Don't scan for goldens while the tool window is collapsed/hidden; a pending force is kept
        // so it is honored once the window is reopened.
        if (!toolWindow.isVisible) return

        val force = pendingForce
        pendingForce = false

        if (!settings.isConfigured) {
            statusLabel.text = "Choose a screenshots directory to begin."
            updateStatusLegend(emptyList())
            listModel.clear()
            lastNames = null
            currentScreen = null
            clearComparison()
            return
        }
        val roots = settings.paths.map(::File)
        if (force) loadedFile = null

        val screen = CurrentScreen.compute(project, settings.annotatedFunctionRegex)
        if (screen == null || screen.names.isEmpty()) {
            statusLabel.text = "Open a Kotlin screen or test file."
            updateStatusLegend(emptyList())
            listModel.clear()
            lastNames = null
            currentScreen = null
            clearComparison()
            return
        }

        val source = selectedSource()
        val refreshKey = screen.names + source.id + (source.extra?.refreshKey(screen) ?: emptyList())

        // Same file/name-set/source as before: keep the current list and the user's selection untouched.
        if (!force && refreshKey == lastNames) {
            return
        }

        lastNames = refreshKey
        currentScreen = screen
        statusLabel.text = "Searching…"
        AppExecutorUtil.getAppExecutorService().execute {
            val items = source.extra?.findItems(project, screen, settings) ?: buildBuiltInItems(
                GoldenFinder.find(
                    roots,
                    screen,
                    settings.matchMode,
                    settings.excludedSuffixes,
                    settings.goldenFilePatterns,
                ),
                source,
            )
            val statusOverride = source.extra?.listStatusForItems(items)
            ApplicationManager.getApplication().invokeLater {
                populate(items, if (source.extra == null) screen.caretName else null, statusOverride)
            }
        }
    }

    private fun buildBuiltInItems(files: List<File>, source: ComparisonSource): List<ExtraComparisonItem> =
        files.mapIndexed { index, file ->
            IndexedValue(
                index,
                ExtraComparisonItem(
                    file = file,
                    title = file.name,
                    isLoading = false,
                    status = comparisonStatus(file, source),
                ),
            )
        }
            .sortedWith(compareBy<IndexedValue<ExtraComparisonItem>> { statusSortRank(it.value.status) }.thenBy { it.index })
            .map { it.value }

    private fun statusSortRank(status: ExtraComparisonItemStatus): Int =
        when (status) {
            ExtraComparisonItemStatus.MODIFIED -> 0
            ExtraComparisonItemStatus.NEW -> 1
            ExtraComparisonItemStatus.UNCHANGED -> 2
        }

    private fun comparisonStatus(file: File, source: ComparisonSource): ExtraComparisonItemStatus {
        val headBytes = GitImageSource.headBytes(project, file)
        val sourceFile = when (source) {
            ComparisonSource.WORKING_COPY -> file
            ComparisonSource.GENERATED -> GeneratedImageSource.findForGolden(
                golden = file,
                goldenRoots = settings.paths.map(::File),
                generatedRoots = settings.generatedPaths.map(::File),
                generatedFileRegex = settings.generatedFileRegex,
                excludedSuffixes = settings.excludedSuffixes,
            )
            else -> null
        }
        val sourceBytes = sourceFile?.let(GitImageSource::workingBytes)
        return when {
            sourceBytes == null -> ExtraComparisonItemStatus.UNCHANGED
            headBytes == null -> ExtraComparisonItemStatus.NEW
            !headBytes.contentEquals(sourceBytes) -> ExtraComparisonItemStatus.MODIFIED
            else -> ExtraComparisonItemStatus.UNCHANGED
        }
    }

    private fun populate(items: List<ExtraComparisonItem>, caretName: String?, statusOverride: String? = null) {
        val previouslyLoaded = loadedFile
        listModel.clear()
        items.forEach(listModel::addElement)
        refreshListThumbnailLayout()
        updateStatusLegend(items)
        statusLabel.text = statusOverride ?: if (items.isEmpty()) {
            "No screenshots found for this file. Check the directory or record screenshots."
        } else {
            "${items.size} screenshot(s) found."
        }
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
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
    }

    private fun updateStatusLegend(items: List<ExtraComparisonItem>) {
        statusLegendLabel.isVisible = items.any {
            it.status == ExtraComparisonItemStatus.MODIFIED || it.status == ExtraComparisonItemStatus.NEW
        }
    }

    // Clears the comparison viewer and its cached selection so no stale preview lingers when the
    // current file has no goldens.
    private fun clearComparison() {
        loadedFile = null
        loadedSource = selectedSource()
        compareView.showSingle(null, "")
    }

    private fun loadComparison(item: ExtraComparisonItem) {
        val file = item.file
        val source = selectedSource()
        if (file == loadedFile && source == loadedSource) return
        loadedFile = file
        loadedSource = source
        compareView.showSingle(null, "Loading…")
        val extra = source.extra
        if (extra != null) {
            val screen = currentScreen
            if (screen == null) {
                compareView.showSingle(null, "Open a Kotlin screen or test file.")
                return
            }
            AppExecutorUtil.getAppExecutorService().execute {
                val result = extra.loadComparison(project, file, screen, settings)
                ApplicationManager.getApplication().invokeLater {
                    if (loadedFile != file || loadedSource != source) return@invokeLater
                    listRenderer.clearScaledCache()
                    list.repaint()
                    when (result) {
                        is ExtraComparisonResult.Single ->
                            compareView.showSingle(result.image, result.statusText)
                        is ExtraComparisonResult.Comparison ->
                            compareView.showComparison(
                                old = result.old,
                                new = result.new,
                                statusText = result.statusText,
                                oldLabel = result.oldLabel,
                                newLabel = result.newLabel,
                            )
                    }
                }
            }
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            val headBytes = GitImageSource.headBytes(project, file)
            val workingFile = when (source) {
                ComparisonSource.WORKING_COPY -> file
                ComparisonSource.GENERATED -> GeneratedImageSource.findForGolden(
                    golden = file,
                    goldenRoots = settings.paths.map(::File),
                    generatedRoots = settings.generatedPaths.map(::File),
                    generatedFileRegex = settings.generatedFileRegex,
                    excludedSuffixes = settings.excludedSuffixes,
                )
                else -> null
            }
            val workingBytes = workingFile?.let(GitImageSource::workingBytes)
            val head = GitImageSource.decode(headBytes)
            val working = GitImageSource.decode(workingBytes)
            val unchanged = headBytes != null && workingBytes != null && headBytes.contentEquals(workingBytes)
            ApplicationManager.getApplication().invokeLater {
                if (loadedFile != file || loadedSource != source) return@invokeLater
                when {
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
            }
        }
    }

    private fun selectedSource(): ComparisonSource =
        sourceCombo.selectedItem as? ComparisonSource ?: ComparisonSource.WORKING_COPY

    private fun comparisonTitle(golden: File, workingFile: File?, source: ComparisonSource): String =
        when (source) {
            ComparisonSource.WORKING_COPY -> golden.name
            ComparisonSource.GENERATED -> "${golden.name} ↔ ${workingFile?.name ?: "generated output"}"
            else -> golden.name
        }

    override fun dispose() {
        // messageBus connection and caret listener are tied to this Disposable and cleaned up here.
        refreshTimer.stop()
    }

    companion object {
        private const val DEBOUNCE_MS = 300
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

        override fun toString(): String = title

        companion object {
            val WORKING_COPY = ComparisonSource("working-copy", "Working copy")
            val GENERATED = ComparisonSource("generated", "Test output")
            val builtIns = listOf(WORKING_COPY, GENERATED)

            fun extra(source: ExtraComparisonSource): ComparisonSource =
                ComparisonSource(source.id, source.title, source)
        }
    }

    private fun thumbnailScale(): Double = THUMBNAIL_SCALES[thumbnailScaleIndex]
}
