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
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.util.stream.Collectors
import javax.swing.AbstractAction
import javax.swing.BoxLayout
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
    private val scopeCombo = ComboBox(Scope.entries.toTypedArray()).apply {
        selectedItem = Scope.CURRENT_FILE
        toolTipText = "Choose whether to show screenshots for the current file or all project golden changes"
        addActionListener {
            if (selectedScope() == Scope.PROJECT_CHANGES && selectedSource().extra != null) {
                sourceCombo.selectedItem = ComparisonSource.WORKING_COPY
            }
            updateScopeControls()
            loadedFile = null
            lastNames = null
            scheduleRefresh(force = true)
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
            val previousSource = loadedSource
            val newSource = selectedSource()
            loadedFile = null
            loadedSource = newSource
            if (selectedScope() == Scope.PROJECT_CHANGES) {
                if (newSource.extra != null) {
                    selectedItem = ComparisonSource.WORKING_COPY
                    return@addActionListener
                }
                lastNames = null
                scheduleRefresh(force = true)
                return@addActionListener
            }
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
            firstComponent = BottomRightOverlayPanel(listScrollPane, thumbnailScalePanel)
            secondComponent = compareView
        }
        add(buildHeader(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) list.selectedValue?.let(::loadComparison)
        }
        installListKeyboardNavigation()

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
        list.actionMap.put("selectNextColumn", listMoveAction(1))
        list.actionMap.put("selectPreviousColumn", listMoveAction(-1))
        list.actionMap.put("selectNextColumnExtendSelection", listMoveAction(1))
        list.actionMap.put("selectPreviousColumnExtendSelection", listMoveAction(-1))
        list.actionMap.put("selectNextColumnChangeLead", listMoveAction(1))
        list.actionMap.put("selectPreviousColumnChangeLead", listMoveAction(-1))
        list.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    when (event.keyCode) {
                        KeyEvent.VK_RIGHT -> {
                            moveListSelectionBy(1)
                            event.consume()
                        }
                        KeyEvent.VK_LEFT -> {
                            moveListSelectionBy(-1)
                            event.consume()
                        }
                    }
                }
            },
        )
    }

    private fun bindListKey(keyCode: Int, actionName: String, delta: Int) {
        val keyStroke = KeyStroke.getKeyStroke(keyCode, 0)
        list.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionName)
        list.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(keyStroke, actionName)
        list.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, actionName)
        list.actionMap.put(actionName, listMoveAction(delta))
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
            add(JButton("Refresh").apply { addActionListener { scheduleRefresh(force = true) } })
            add(JBLabel("Scope:"))
            add(scopeCombo)
            add(JBLabel("Compare:"))
            add(sourceCombo)
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
            settings.paths = settings.paths + storagePath(chosen.path)
            updateHeader()
            scheduleRefresh(force = true)
        }
    }

    private fun updateHeader() {
        directoriesButton.text = if (settings.isConfigured) "Settings" else "Choose screenshots directory"
    }

    private fun updateScopeControls() {
        sourceCombo.isEnabled = true
    }

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

    private fun scheduleRefresh(force: Boolean = false) {
        if (force) pendingForce = true
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

        if (!settings.isConfigured) {
            statusLabel.text = "Choose a screenshots directory to begin."
            updateStatusLegend(emptyList())
            listModel.clear()
            lastNames = null
            currentScreen = null
            clearComparison()
            return
        }
        val roots = settings.resolvedPaths(project)
        if (force) loadedFile = null

        if (selectedScope() == Scope.PROJECT_CHANGES) {
            refreshProjectChanges(roots, force)
            return
        }

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

    private fun refreshProjectChanges(roots: List<File>, force: Boolean) {
        val source = selectedSource().takeIf { it.extra == null } ?: ComparisonSource.WORKING_COPY
        val refreshKey = listOf(
            "project-changes",
            source.id,
            settings.generatedFileRegex,
        ) + roots.map { it.path } + settings.resolvedGeneratedPaths(project).map { it.path }
        if (!force && refreshKey == lastNames) return

        lastNames = refreshKey
        currentScreen = null
        statusLabel.text = "Searching project changes..."
        AppExecutorUtil.getAppExecutorService().execute {
            val items = when (source) {
                ComparisonSource.GENERATED -> projectGeneratedChangeItems(roots)
                else -> projectWorkingCopyChangeItems(roots)
            }.filter { it.status != ExtraComparisonItemStatus.UNCHANGED }
            val sourceName = when (source) {
                ComparisonSource.GENERATED -> "test output"
                else -> "working copy"
            }
            ApplicationManager.getApplication().invokeLater {
                populate(
                    items,
                    caretName = null,
                    statusOverride = if (items.isEmpty()) {
                        "No golden changes found in $sourceName."
                    } else {
                        "${items.size} changed screenshot(s) found in project $sourceName."
                    },
                )
            }
        }
    }

    private fun buildBuiltInItems(files: List<File>, source: ComparisonSource): List<ExtraComparisonItem> =
        buildBuiltInItems(files) { comparisonStatus(it, source) }

    private fun buildBuiltInItems(
        files: List<File>,
        statusOf: (File) -> ExtraComparisonItemStatus,
    ): List<ExtraComparisonItem> =
        files.mapIndexed { index, file ->
            IndexedValue(
                index,
                ExtraComparisonItem(
                    file = file,
                    title = file.name,
                    isLoading = false,
                    status = statusOf(file),
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
                goldenRoots = settings.resolvedPaths(project),
                generatedRoots = settings.resolvedGeneratedPaths(project),
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

    private fun projectWorkingCopyChangeItems(roots: List<File>): List<ExtraComparisonItem> {
        // git status already tells us which goldens changed and whether they exist in HEAD, so we can
        // derive MODIFIED/NEW straight from the porcelain code — no per-file HEAD revision fetch.
        val statusByPath = gitStatusEntries()
            .filter { isGoldenCandidate(it.file, roots) }
            .associateBy({ it.file.normalize().path }, { it.status })
        val files = statusByPath.keys.map(::File)
            .sortedBy { invariantPathIn(it, roots).lowercase() }
        return buildBuiltInItems(files) { statusByPath[it.normalize().path] ?: ExtraComparisonItemStatus.MODIFIED }
    }

    private data class GitStatusEntry(val file: File, val status: ExtraComparisonItemStatus)

    private fun gitStatusEntries(): List<GitStatusEntry> {
        val basePath = project.basePath ?: return emptyList()
        val base = File(basePath)
        val output = runCatching {
            ProcessBuilder("git", "-C", base.path, "status", "--porcelain=v1", "-z")
                .redirectErrorStream(true)
                .start()
                .let { process ->
                    val bytes = process.inputStream.readBytes()
                    if (process.waitFor() != 0) ByteArray(0) else bytes
                }
        }.getOrDefault(ByteArray(0))
        if (output.isEmpty()) return emptyList()

        val records = output.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        val entries = ArrayList<GitStatusEntry>()
        var index = 0
        while (index < records.size) {
            val record = records[index]
            if (record.length >= 4) {
                val status = record.substring(0, 2)
                val path = record.substring(3)
                if (status.any { it != ' ' && it != 'D' }) {
                    entries.add(GitStatusEntry(File(base, path), status.toChangeStatus()))
                }
                // Rename/copy records carry a second (origin) path token that must be skipped.
                if (status.any { it == 'R' || it == 'C' }) {
                    index++
                }
            }
            index++
        }
        return entries
    }

    // Untracked/added/renamed/copied paths do not exist in HEAD → NEW; anything else that changed
    // exists in HEAD → MODIFIED.
    private fun String.toChangeStatus(): ExtraComparisonItemStatus =
        if (any { it == '?' || it == 'A' || it == 'R' || it == 'C' }) {
            ExtraComparisonItemStatus.NEW
        } else {
            ExtraComparisonItemStatus.MODIFIED
        }

    private fun projectGeneratedChangeItems(roots: List<File>): List<ExtraComparisonItem> {
        val generatedRoots = settings.resolvedGeneratedPaths(project)
        if (generatedRoots.none { it.isDirectory }) return emptyList()
        val generatedPattern = runCatching { Regex(settings.generatedFileRegex) }.getOrNull() ?: return emptyList()
        val suffixes = settings.excludedSuffixes.filter { it.isNotBlank() }
        // Walk the generated tree once and index by base name, so resolving a golden's counterpart is
        // an O(1) lookup instead of re-walking the whole tree per golden.
        val generatedByBaseName = HashMap<String, File>()
        generatedRoots
            .asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .forEach { file ->
                generatedBaseName(file, generatedPattern, suffixes)?.let {
                    generatedByBaseName.putIfAbsent(it.lowercase(), file)
                }
            }
        if (generatedByBaseName.isEmpty()) return emptyList()

        val goldens = GoldenFinder.findAll(roots, settings.excludedSuffixes)
            .asSequence()
            .filter { it.nameWithoutExtension.lowercase() in generatedByBaseName }
            .distinctBy { it.normalize().path }
            .sortedBy { invariantPathIn(it, roots).lowercase() }
            .toList()

        // Each status still needs a per-golden HEAD revision fetch; compute them in parallel so a large
        // change set isn't a serial chain of blocking git reads.
        val statusByGolden: Map<File, ExtraComparisonItemStatus> = goldens.parallelStream().collect(
            Collectors.toConcurrentMap(
                { it },
                { golden -> generatedComparisonStatus(golden, generatedByBaseName[golden.nameWithoutExtension.lowercase()]) },
            ),
        )
        return buildBuiltInItems(goldens) { statusByGolden.getValue(it) }
    }

    private fun generatedComparisonStatus(golden: File, generated: File?): ExtraComparisonItemStatus {
        val sourceBytes = generated?.let(GitImageSource::workingBytes) ?: return ExtraComparisonItemStatus.UNCHANGED
        val headBytes = GitImageSource.headBytes(project, golden) ?: return ExtraComparisonItemStatus.NEW
        return if (headBytes.contentEquals(sourceBytes)) {
            ExtraComparisonItemStatus.UNCHANGED
        } else {
            ExtraComparisonItemStatus.MODIFIED
        }
    }

    private fun generatedBaseName(file: File, pattern: Regex, suffixes: List<String>): String? {
        val match = pattern.matchEntire(file.name)
        return match?.groups?.getOrNull(1)?.value
            ?: suffixes.fold(file.nameWithoutExtension) { acc, suffix -> acc.removeSuffix(suffix) }
    }

    private fun MatchGroupCollection.getOrNull(index: Int): MatchGroup? =
        runCatching { get(index) }.getOrNull()

    private fun isGoldenCandidate(file: File, roots: List<File>): Boolean =
        file.isFile &&
            file.extension.equals("png", ignoreCase = true) &&
            settings.excludedSuffixes.none { it.isNotBlank() && file.nameWithoutExtension.endsWith(it) } &&
            roots.any { root -> file.isDescendantOf(root) }

    private fun File.isDescendantOf(root: File): Boolean {
        val relative = runCatching { normalize().relativeTo(root.normalize()) }.getOrNull() ?: return false
        return !relative.path.startsWith("..")
    }

    private fun invariantPathIn(file: File, roots: List<File>): String {
        val root = roots.firstOrNull { file.isDescendantOf(it) }
        val relative = root?.let { runCatching { file.normalize().relativeTo(it.normalize()) }.getOrNull() }
        return (relative ?: file).path.replace(File.separatorChar, '/')
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
        AppExecutorUtil.getAppExecutorService().execute {
            val headBytes = GitImageSource.headBytes(project, file)
            val workingFile = when (source) {
                ComparisonSource.WORKING_COPY -> file
                ComparisonSource.GENERATED -> GeneratedImageSource.findForGolden(
                    golden = file,
                    goldenRoots = settings.resolvedPaths(project),
                    generatedRoots = settings.resolvedGeneratedPaths(project),
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
                        // Identical to HEAD: no point in a two-up diff — show a single preview.
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

private enum class Scope(private val title: String) {
    CURRENT_FILE("Current file"),
    PROJECT_CHANGES("Project changes");

    override fun toString(): String = title
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
