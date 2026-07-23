package com.github.dkwasniak.goldendiff.settings

import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.match.MatchingDefaults
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSources
import com.github.dkwasniak.goldendiff.variant.ExtraSettingsComponent
import com.github.dkwasniak.goldendiff.telemetry.PluginTelemetryService
import com.github.dkwasniak.goldendiff.telemetry.PluginTelemetrySettings
import com.github.dkwasniak.goldendiff.telemetry.TelemetryBuckets
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

private const val PREVIEW_DEBOUNCE_MS = 300

class ScreenshotConfigurable(private val project: Project) : Configurable {

    private val goldenModel = DefaultListModel<String>()
    private val goldenList = JBList(goldenModel)
    private val generatedModel = DefaultListModel<String>()
    private val generatedList = JBList(generatedModel)
    private val generatedRegexField = singleLineField()
    private val annotationRegexField = singleLineField()
    private val annotatedMethodRadio = JRadioButton("Match by annotated method")
    private val fileClassRadio = JRadioButton("Match by file/class regex")
    private val goldenPatternsArea = JTextArea(4, 40).apply {
        lineWrap = false
        margin = JBUI.insets(4, 6)
    }
    private val excludedSuffixesField = singleLineField()
    private val trimTransparentPaddingCheckbox =
        JCheckBox("Trim transparent padding around image content")
    private val analyticsCheckbox = JCheckBox("Share anonymous product analytics")
    private val diagnosticsCheckbox = JCheckBox("Share diagnostic error reports")
    private val previewCountLabel = JBLabel()
    private val previewModel = DefaultListModel<String>()
    private val previewList = JBList(previewModel)
    private val extraSettingsSources: List<Pair<String, ExtraSettingsComponent>> =
        ExtraComparisonSources.all.mapNotNull { source ->
            source.createSettingsComponent(project)?.let { source.title to it }
        }
    private val extraSettings: List<ExtraSettingsComponent> = extraSettingsSources.map { it.second }
    // Debounces live preview updates as the user edits regexes / directories.
    private val previewTimer = Timer(PREVIEW_DEBOUNCE_MS) { runPreview() }.apply { isRepeats = false }

    override fun getDisplayName(): String = "Golden Diff"

    override fun createComponent(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        panel.add(sectionHeader("Directories"))
        panel.add(directorySection("Golden directories:", "Committed screenshot baselines listed in the tool window.", goldenList, goldenModel))
        panel.add(spacer())
        panel.add(
            directorySection(
                "Generated test output directories:",
                "Fresh screenshots produced by verification tests; used only when Compare is set to Test output.",
                generatedList,
                generatedModel,
            ),
        )
        panel.add(spacer())
        panel.add(sectionHeader("Test output matching"))
        panel.add(regexSection())
        panel.add(spacer())
        panel.add(sectionHeader("Golden matching"))
        panel.add(matchingSection())
        panel.add(spacer())
        panel.add(sectionHeader("Filtering"))
        panel.add(excludedSuffixesSection())
        panel.add(spacer())
        panel.add(sectionHeader("Display"))
        panel.add(displaySection())
        panel.add(spacer())
        panel.add(sectionHeader("Privacy"))
        panel.add(privacySection())
        panel.add(spacer())
        extraSettingsSources.forEach { (title, settings) ->
            panel.add(sectionHeader(title))
            panel.add(settings.component)
            panel.add(spacer())
        }
        panel.add(sectionHeader("Preview"))
        panel.add(previewSection())
        wirePreviewTriggers()
        reset()
        return panel
    }

    private fun previewSection(): JPanel {
        previewList.visibleRowCount = 6
        return JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(220))
            add(
                labelBlock(
                    "Preview (live):",
                    "Runs the current (unsaved) settings against the golden directories and the Kotlin file open in the editor.",
                ),
                BorderLayout.NORTH,
            )
            add(
                JPanel(BorderLayout()).apply {
                    add(previewCountLabel, BorderLayout.NORTH)
                    add(
                        JScrollPane(previewList).apply {
                            preferredSize = Dimension(1, JBUI.scale(120))
                            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(120))
                        },
                        BorderLayout.CENTER,
                    )
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun privacySection(): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(
                labelBlock(
                    "Optional telemetry:",
                    "Both choices are off by default and independent. Golden Diff never sends project " +
                        "names, file names, paths, source code or images.",
                ),
            )
            add(analyticsCheckbox)
            add(diagnosticsCheckbox)
            add(
                JBLabel("<html><a href=''>Read the privacy policy</a></html>").apply {
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                            BrowserUtil.browse("https://github.com/dkej123/goldendiff/blob/main/docs/privacy.md")
                        }
                    })
                },
            )
        }

    /** Refresh the preview (debounced) whenever a matching-relevant field changes. */
    private fun wirePreviewTriggers() {
        val onChange = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = schedulePreview()
            override fun removeUpdate(e: DocumentEvent) = schedulePreview()
            override fun changedUpdate(e: DocumentEvent) = schedulePreview()
        }
        goldenPatternsArea.document.addDocumentListener(onChange)
        annotationRegexField.document.addDocumentListener(onChange)
        excludedSuffixesField.document.addDocumentListener(onChange)
        annotatedMethodRadio.addActionListener { schedulePreview() }
        fileClassRadio.addActionListener { schedulePreview() }
        goldenModel.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent) = schedulePreview()
            override fun intervalRemoved(e: ListDataEvent) = schedulePreview()
            override fun contentsChanged(e: ListDataEvent) = schedulePreview()
        })
    }

    private fun schedulePreview() {
        previewTimer.restart()
    }

    private fun runPreview() {
        val roots = currentPaths(goldenModel).map(::resolvePath)
        val mode = selectedMatchMode()
        val annotationRegex = annotationRegexField.text.ifBlank { MatchingDefaults.ANNOTATION_NAME_REGEX }
        val patterns = parsePatterns(goldenPatternsArea.text).ifEmpty { MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS }
        val excluded = parseSuffixes(excludedSuffixesField.text)

        if (roots.isEmpty()) {
            showPreview("Add a golden directory to preview matches.", emptyList())
            return
        }
        previewCountLabel.text = "Searching…"
        previewModel.clear()
        // Capture the dialog's modality so the result is delivered while Settings is still open.
        val modality = ModalityState.stateForComponent(previewList)
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching {
                val screen = CurrentScreen.compute(project, annotationRegex)
                val files = if (screen == null) emptyList() else GoldenFinder.find(roots, screen, mode, excluded, patterns)
                screen to files
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    result.onFailure { showPreview("Preview failed: ${it.message}", emptyList()) }
                    result.onSuccess { (screen, files) ->
                        if (screen == null) {
                            showPreview("Open a Kotlin file in the editor to preview matches.", emptyList())
                        } else {
                            val context = buildList {
                                if (screen.fileName.isNotBlank()) add("file ${screen.fileName}")
                                if (screen.classNames.isNotEmpty()) add("classes ${screen.classNames.joinToString(", ")}")
                                if (mode == MatchMode.ANNOTATED_METHOD && screen.functionNames.isNotEmpty()) {
                                    add("methods ${screen.functionNames.joinToString(", ")}")
                                }
                            }.joinToString("; ")
                            showPreview("${files.size} match(es) for $context", files.map { displayPath(it, roots) })
                        }
                    }
                },
                modality,
            )
        }
    }

    private fun showPreview(summary: String, paths: List<String>) {
        previewCountLabel.text = summary
        previewModel.clear()
        paths.forEach(previewModel::addElement)
    }

    private fun displayPath(file: File, roots: List<File>): String {
        val root = roots.firstOrNull { file.path.startsWith(it.path + File.separatorChar) }
        return if (root != null) file.relativeTo(root).path.replace(File.separatorChar, '/') else file.name
    }

    private fun directorySection(
        title: String,
        description: String,
        list: JBList<String>,
        model: DefaultListModel<String>,
    ): JPanel {
        list.visibleRowCount = 1
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val decorator = ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Select Screenshot Directory")
                    .withDescription("Directory that contains screenshot files")
                val chosen = FileChooser.chooseFile(descriptor, project, null)
                val path = chosen?.path?.let(::storagePath)
                if (path != null) {
                    // Single directory per section: a new pick replaces the previous entry.
                    model.clear()
                    model.addElement(path)
                }
            }
            .setRemoveAction {
                list.selectedValuesList.forEach { model.removeElement(it) }
            }
            .disableUpDownActions()
        val decorated = decorator.createPanel().apply {
            preferredSize = Dimension(1, JBUI.scale(58))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(58))
        }

        return JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(100))
            add(
                labelBlock(title, description),
                BorderLayout.NORTH,
            )
            add(decorated, BorderLayout.CENTER)
        }
    }

    private fun regexSection(): JPanel =
        JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(94))
            add(
                labelBlock(
                    "Generated output filename regex:",
                    "Used only after you select a golden and choose Compare: Test output. The first capture group must be the matching golden basename.",
                ),
                BorderLayout.NORTH,
            )
            generatedRegexField.toolTipText =
                "Example: ^(.+)_actual\\.png$ maps LoginScreen_actual.png to LoginScreen.png."
            add(generatedRegexField, BorderLayout.CENTER)
        }

    private fun matchingSection(): JPanel {
        ButtonGroup().apply {
            add(annotatedMethodRadio)
            add(fileClassRadio)
        }
        annotatedMethodRadio.addActionListener { updateMatchModeEnablement() }
        fileClassRadio.addActionListener { updateMatchModeEnablement() }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(
                labelBlock(
                    "Golden matching mode:",
                    "Match goldens either by the name of annotated screenshot functions, or by a file/class regex.",
                ),
            )
            add(annotatedMethodRadio)
            add(fileClassRadio)
            add(spacer())
            add(
                JPanel(BorderLayout()).apply {
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(94))
                    add(
                        labelBlock(
                            "Screenshot annotation name regex:",
                            "Annotated functions whose annotation name matches become golden candidates; the golden must contain the function name.",
                        ),
                        BorderLayout.NORTH,
                    )
                    annotationRegexField.toolTipText =
                        "Annotation names matched here make their functions golden candidates, e.g. .*Preview.*|Test|ScreenshotTest."
                    add(annotationRegexField, BorderLayout.CENTER)
                },
            )
            add(spacer())
            add(
                JPanel(BorderLayout()).apply {
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(196))
                    add(
                        labelBlock(
                            "Golden filename regex (one per line):",
                            "Matched against each golden's path. Examples: {file_name}, {class_name}, {class_name}\\..*, golden_{file_name}.",
                        ),
                        BorderLayout.NORTH,
                    )
                    goldenPatternsArea.toolTipText =
                        "{file_name} = current Kotlin file name, {class_name} = a class declared in the file."
                    add(
                        JScrollPane(goldenPatternsArea).apply {
                            preferredSize = Dimension(1, JBUI.scale(92))
                            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(92))
                        },
                        BorderLayout.CENTER,
                    )
                },
            )
        }
    }

    private fun updateMatchModeEnablement() {
        annotationRegexField.isEnabled = annotatedMethodRadio.isSelected
        goldenPatternsArea.isEnabled = fileClassRadio.isSelected
    }

    private fun excludedSuffixesSection(): JPanel =
        JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(94))
            add(
                labelBlock(
                    "Excluded golden suffixes:",
                    "Files ending with these suffixes are hidden from the golden list, for example Roborazzi _actual and _compare outputs.",
                ),
                BorderLayout.NORTH,
            )
            excludedSuffixesField.toolTipText =
                "File-name suffixes (before the extension) excluded from the golden list, " +
                "e.g. Roborazzi's _compare, _actual artifacts. Leave empty to exclude nothing."
            add(excludedSuffixesField, BorderLayout.CENTER)
        }

    private fun displaySection(): JPanel =
        JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(64))
            add(
                labelBlock(
                    "Image display:",
                    "Crops fully transparent borders so images are shown tight to their content. Off by default; images are shown exactly as stored.",
                ),
                BorderLayout.NORTH,
            )
            add(trimTransparentPaddingCheckbox, BorderLayout.CENTER)
        }

    private fun singleLineField(): JTextField =
        JTextField().apply {
            margin = JBUI.insets(2, 6)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    // A left-aligned section title followed by a divider line. Built by hand because TitledSeparator
    // overrides getMaximumSize(), so inside a Y_AXIS BoxLayout it refuses to fill the width and its
    // label drifts to the center.
    private fun sectionHeader(title: String): JComponent =
        JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(12, 0, 4, 0)
            add(
                JBLabel(title).apply { font = font.deriveFont(Font.BOLD) },
                BorderLayout.WEST,
            )
            add(JSeparator(SwingConstants.HORIZONTAL), BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun labelBlock(title: String, description: String): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(6)
            add(JBLabel(title))
            add(
                JBLabel(description).apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
            )
        }

    private fun spacer(): JPanel =
        JPanel().apply {
            maximumSize = Dimension(1, JBUI.scale(12))
            preferredSize = Dimension(1, JBUI.scale(12))
        }

    private fun currentPaths(model: DefaultListModel<String>): List<String> =
        (0 until model.size()).map { model.getElementAt(it) }

    private fun currentStoragePaths(model: DefaultListModel<String>): List<String> =
        currentPaths(model).map(::storagePath)

    private fun parseSuffixes(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parsePatterns(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun selectedMatchMode(): MatchMode =
        if (fileClassRadio.isSelected) MatchMode.FILE_CLASS_REGEX else MatchMode.ANNOTATED_METHOD

    override fun isModified(): Boolean {
        val settings = ScreenshotSettings.getInstance(project)
        return currentStoragePaths(goldenModel) != settings.paths.map(::storagePath) ||
            currentStoragePaths(generatedModel) != settings.generatedPaths.map(::storagePath) ||
            generatedRegexField.text != settings.generatedFileRegex ||
            selectedMatchMode() != settings.matchMode ||
            annotationRegexField.text != settings.annotatedFunctionRegex ||
            parsePatterns(goldenPatternsArea.text) != settings.goldenFilePatterns ||
            parseSuffixes(excludedSuffixesField.text) != settings.excludedSuffixes ||
            trimTransparentPaddingCheckbox.isSelected != settings.trimTransparentPadding ||
            analyticsCheckbox.isSelected != PluginTelemetrySettings.getInstance().analyticsEnabled ||
            diagnosticsCheckbox.isSelected != PluginTelemetrySettings.getInstance().diagnosticsEnabled ||
            extraSettings.any { it.isModified() }
    }

    override fun apply() {
        val regex = generatedRegexField.text.ifBlank { GoldenDiffDefaults.GENERATED_FILE_REGEX }
        try {
            Regex(regex)
        } catch (e: IllegalArgumentException) {
            throw ConfigurationException("Generated file regex is invalid: ${e.message}")
        }
        val annotationRegex = annotationRegexField.text.ifBlank {
            com.github.dkwasniak.goldendiff.match.MatchingDefaults.ANNOTATION_NAME_REGEX
        }
        try {
            Regex(annotationRegex)
        } catch (e: IllegalArgumentException) {
            throw ConfigurationException("Annotated function regex is invalid: ${e.message}")
        }
        val goldenPatterns = parsePatterns(goldenPatternsArea.text)
            .ifEmpty { MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS }
        goldenPatterns.forEach { pattern ->
            val expanded = pattern
                .replace("{file_name}", "FileName")
                .replace("{class_name}", "ClassName")
            try {
                Regex(expanded)
            } catch (e: IllegalArgumentException) {
                throw ConfigurationException("Golden filename regex is invalid: ${e.message}")
            }
        }
        val settings = ScreenshotSettings.getInstance(project)
        val old = settings.toConfig()
        settings.paths = currentStoragePaths(goldenModel)
        settings.generatedPaths = currentStoragePaths(generatedModel)
        settings.generatedFileRegex = regex
        settings.matchMode = selectedMatchMode()
        settings.annotatedFunctionRegex = annotationRegex
        settings.goldenFilePatterns = goldenPatterns
        settings.excludedSuffixes = parseSuffixes(excludedSuffixesField.text)
        settings.trimTransparentPadding = trimTransparentPaddingCheckbox.isSelected
        val telemetrySettings = PluginTelemetrySettings.getInstance()
        telemetrySettings.analyticsEnabled = analyticsCheckbox.isSelected
        telemetrySettings.diagnosticsEnabled = diagnosticsCheckbox.isSelected
        PluginTelemetryService.getInstance().updateConsent()
        val new = settings.toConfig()
        if (old != new) {
            PluginTelemetryService.getInstance().client.event(
                "product.configuration_saved",
                mapOf(
                    "match_mode" to if (new.matchMode == MatchMode.ANNOTATED_METHOD) {
                        "annotated_method"
                    } else {
                        "file_class_regex"
                    },
                    "golden_dir_count_bucket" to TelemetryBuckets.count(new.goldenPaths.size),
                    "generated_dir_count_bucket" to TelemetryBuckets.count(new.generatedPaths.size),
                    "generated_configured" to new.generatedPaths.isNotEmpty().toString(),
                    "trim_enabled" to new.trimTransparentPadding.toString(),
                    "changed_golden_dirs" to (old.goldenPaths != new.goldenPaths).toString(),
                    "changed_generated_dirs" to (old.generatedPaths != new.generatedPaths).toString(),
                    "changed_matching" to (
                        old.matchMode != new.matchMode ||
                            old.annotatedFunctionRegex != new.annotatedFunctionRegex ||
                            old.goldenFilePatterns != new.goldenFilePatterns
                        ).toString(),
                    "changed_filtering" to (old.excludedSuffixes != new.excludedSuffixes).toString(),
                    "changed_display" to (old.trimTransparentPadding != new.trimTransparentPadding).toString(),
                ),
            )
        }
        extraSettings.forEach { it.apply() }
    }

    override fun reset() {
        goldenModel.clear()
        generatedModel.clear()
        val settings = ScreenshotSettings.getInstance(project)
        settings.paths.forEach { goldenModel.addElement(storagePath(it)) }
        settings.generatedPaths.forEach { generatedModel.addElement(storagePath(it)) }
        generatedRegexField.text = settings.generatedFileRegex
        when (settings.matchMode) {
            MatchMode.ANNOTATED_METHOD -> annotatedMethodRadio.isSelected = true
            MatchMode.FILE_CLASS_REGEX -> fileClassRadio.isSelected = true
        }
        annotationRegexField.text = settings.annotatedFunctionRegex
        goldenPatternsArea.text = settings.goldenFilePatterns.joinToString("\n")
        excludedSuffixesField.text = settings.excludedSuffixes.joinToString(", ")
        trimTransparentPaddingCheckbox.isSelected = settings.trimTransparentPadding
        val telemetrySettings = PluginTelemetrySettings.getInstance()
        analyticsCheckbox.isSelected = telemetrySettings.analyticsEnabled
        diagnosticsCheckbox.isSelected = telemetrySettings.diagnosticsEnabled
        extraSettings.forEach { it.reset() }
        updateMatchModeEnablement()
    }

    override fun disposeUIResources() {
        previewTimer.stop()
    }

    private fun resolvePath(path: String): File {
        val file = File(path)
        if (file.isAbsolute) return file.normalize()
        val basePath = project.basePath ?: return file.normalize()
        return File(basePath, path).normalize()
    }

    private fun storagePath(path: String): String {
        val basePath = project.basePath ?: return path
        val base = File(basePath).normalize()
        val file = resolvePath(path)
        return runCatching { file.relativeTo(base).path }
            .getOrNull()
            ?.takeIf { !it.startsWith("..") && it != "." }
            ?.replace(File.separatorChar, '/')
            ?: path
    }
}
