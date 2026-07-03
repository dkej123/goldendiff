package com.github.dkwasniak.screenshotcompare.toolwindow

import com.github.dkwasniak.screenshotcompare.compare.CompareView
import com.github.dkwasniak.screenshotcompare.compare.GeneratedImageSource
import com.github.dkwasniak.screenshotcompare.compare.GitImageSource
import com.github.dkwasniak.screenshotcompare.match.CurrentScreen
import com.github.dkwasniak.screenshotcompare.match.GoldenFinder
import com.github.dkwasniak.screenshotcompare.settings.ScreenshotConfigurable
import com.github.dkwasniak.screenshotcompare.settings.ScreenshotSettings
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
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.Timer

class ScreenshotPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings = ScreenshotSettings.getInstance(project)

    private val listModel = DefaultListModel<File>()
    private val listRenderer = GoldenCellRenderer()
    private val list = object : JBList<File>(listModel) {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }.apply {
        cellRenderer = listRenderer
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
    private val directoriesButton = JButton()
    private val sourceCombo = ComboBox(ComparisonSource.entries.toTypedArray()).apply {
        selectedItem = ComparisonSource.WORKING_COPY
        toolTipText = "Choose whether HEAD is compared with the working-copy golden or test output"
        addActionListener {
            loadedFile = null
            loadedSource = selectedSource()
            list.selectedValue?.let(::loadComparison)
        }
    }

    // Debounces list refreshes from editor/caret changes; fires once on the EDT after DEBOUNCE_MS.
    private val refreshTimer = Timer(DEBOUNCE_MS) { refresh() }.apply { isRepeats = false }

    // Names the list was last built from — used to avoid rebuilding when only the caret moves.
    private var lastNames: List<String>? = null
    // File currently shown in the comparison view, to avoid reloading the same image.
    private var loadedFile: File? = null
    private var loadedSource = ComparisonSource.WORKING_COPY
    private var pendingForce = false

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
        updateHeader()
        scheduleRefresh()
    }

    private fun refreshListThumbnailLayout() {
        listRenderer.clearScaledCache()
        list.fixedCellHeight = listRenderer.cellHeight(listModel.elements().toList(), list.width)
        list.invalidate()
        list.revalidate()
        list.repaint()
    }

    private fun buildHeader(): JPanel {
        directoriesButton.addActionListener { onDirectoriesClicked() }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(directoriesButton)
            add(JButton("Refresh").apply { addActionListener { scheduleRefresh(force = true) } })
            add(JBLabel("Compare:"))
            add(sourceCombo)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 6, 4, 6)
            add(toolbar, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)
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
        val force = pendingForce
        pendingForce = false

        if (!settings.isConfigured) {
            statusLabel.text = "Choose a screenshots directory to begin."
            listModel.clear()
            lastNames = null
            loadedFile = null
            return
        }
        val roots = settings.paths.map(::File)
        if (force) loadedFile = null

        val screen = CurrentScreen.compute(project)
        if (screen == null || screen.names.isEmpty()) {
            statusLabel.text = "Open a Kotlin screen or test file."
            listModel.clear()
            lastNames = null
            return
        }

        // Same file/name-set as before: keep the current list and the user's selection untouched.
        if (!force && screen.names == lastNames) {
            return
        }

        lastNames = screen.names
        statusLabel.text = "Searching…"
        AppExecutorUtil.getAppExecutorService().execute {
            val files = GoldenFinder.find(roots, screen)
            ApplicationManager.getApplication().invokeLater {
                populate(files, screen.caretName)
            }
        }
    }

    private fun populate(files: List<File>, caretName: String?) {
        val previouslyLoaded = loadedFile
        listModel.clear()
        files.forEach(listModel::addElement)
        refreshListThumbnailLayout()
        statusLabel.text = if (files.isEmpty()) {
            "No screenshots found for this file. Check the directory or record screenshots."
        } else {
            "${files.size} screenshot(s) found."
        }
        if (files.isEmpty()) {
            loadedFile = null
            return
        }
        // Prefer keeping whatever was already open; otherwise pick the caret match, else the first.
        val index = when {
            previouslyLoaded != null && files.indexOf(previouslyLoaded) >= 0 -> files.indexOf(previouslyLoaded)
            caretName != null -> files.indexOfFirst { it.name.contains(caretName, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
            else -> 0
        }
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
    }

    private fun loadComparison(file: File) {
        val source = selectedSource()
        if (file == loadedFile && source == loadedSource) return
        loadedFile = file
        loadedSource = source
        compareView.showSingle(null, "Loading…")
        AppExecutorUtil.getAppExecutorService().execute {
            val headBytes = GitImageSource.headBytes(project, file)
            val workingFile = when (source) {
                ComparisonSource.WORKING_COPY -> file
                ComparisonSource.GENERATED -> GeneratedImageSource.findForGolden(
                    golden = file,
                    goldenRoots = settings.paths.map(::File),
                    generatedRoots = settings.generatedPaths.map(::File),
                    generatedFileRegex = settings.generatedFileRegex,
                )
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
        }

    override fun dispose() {
        // messageBus connection and caret listener are tied to this Disposable and cleaned up here.
        refreshTimer.stop()
    }

    companion object {
        private const val DEBOUNCE_MS = 300
    }

    private enum class ComparisonSource(private val title: String) {
        WORKING_COPY("Working copy"),
        GENERATED("Test output");

        val compareLabel: String get() = title

        override fun toString(): String = title
    }
}
