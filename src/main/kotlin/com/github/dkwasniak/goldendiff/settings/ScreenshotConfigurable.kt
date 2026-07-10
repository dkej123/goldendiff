package com.github.dkwasniak.goldendiff.settings

import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.match.MatchingDefaults
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSources
import com.github.dkwasniak.goldendiff.variant.ExtraSettingsComponent
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
import java.io.File
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
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
    private val previewCountLabel = JBLabel()
    private val previewModel = DefaultListModel<String>()
    private val previewList = JBList(previewModel)
    private val extraSettings: List<ExtraSettingsComponent> =
        ExtraComparisonSources.all.mapNotNull { it.createSettingsComponent(project) }
    // Debounces live preview updates as the user edits regexes / directories.
    private val previewTimer = Timer(PREVIEW_DEBOUNCE_MS) { runPreview() }.apply { isRepeats = false }

    override fun getDisplayName(): String = "Golden Diff"

    override fun createComponent(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
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
        panel.add(regexSection())
        panel.add(spacer())
        panel.add(matchingSection())
        panel.add(spacer())
        panel.add(excludedSuffixesSection())
        panel.add(spacer())
        extraSettings.forEach { settings ->
            panel.add(settings.component)
            panel.add(spacer())
        }
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
        val roots = currentPaths(goldenModel).map(::File)
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
        val decorator = ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Select Screenshot Directory")
                    .withDescription("Directory that contains screenshot files")
                val chosen = FileChooser.chooseFile(descriptor, project, null)
                if (chosen != null && !contains(model, chosen.path)) {
                    model.addElement(chosen.path)
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

    private fun singleLineField(): JTextField =
        JTextField().apply {
            margin = JBUI.insets(2, 6)
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

    private fun contains(model: DefaultListModel<String>, path: String): Boolean =
        (0 until model.size()).any { model.getElementAt(it) == path }

    private fun currentPaths(model: DefaultListModel<String>): List<String> =
        (0 until model.size()).map { model.getElementAt(it) }

    private fun parseSuffixes(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parsePatterns(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun selectedMatchMode(): MatchMode =
        if (fileClassRadio.isSelected) MatchMode.FILE_CLASS_REGEX else MatchMode.ANNOTATED_METHOD

    override fun isModified(): Boolean {
        val settings = ScreenshotSettings.getInstance(project)
        return currentPaths(goldenModel) != settings.paths ||
            currentPaths(generatedModel) != settings.generatedPaths ||
            generatedRegexField.text != settings.generatedFileRegex ||
            selectedMatchMode() != settings.matchMode ||
            annotationRegexField.text != settings.annotatedFunctionRegex ||
            parsePatterns(goldenPatternsArea.text) != settings.goldenFilePatterns ||
            parseSuffixes(excludedSuffixesField.text) != settings.excludedSuffixes ||
            extraSettings.any { it.isModified() }
    }

    override fun apply() {
        val regex = generatedRegexField.text.ifBlank { ScreenshotSettings.DEFAULT_GENERATED_FILE_REGEX }
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
        settings.paths = currentPaths(goldenModel)
        settings.generatedPaths = currentPaths(generatedModel)
        settings.generatedFileRegex = regex
        settings.matchMode = selectedMatchMode()
        settings.annotatedFunctionRegex = annotationRegex
        settings.goldenFilePatterns = goldenPatterns
        settings.excludedSuffixes = parseSuffixes(excludedSuffixesField.text)
        extraSettings.forEach { it.apply() }
    }

    override fun reset() {
        goldenModel.clear()
        generatedModel.clear()
        val settings = ScreenshotSettings.getInstance(project)
        settings.paths.forEach { goldenModel.addElement(it) }
        settings.generatedPaths.forEach { generatedModel.addElement(it) }
        generatedRegexField.text = settings.generatedFileRegex
        when (settings.matchMode) {
            MatchMode.ANNOTATED_METHOD -> annotatedMethodRadio.isSelected = true
            MatchMode.FILE_CLASS_REGEX -> fileClassRadio.isSelected = true
        }
        annotationRegexField.text = settings.annotatedFunctionRegex
        goldenPatternsArea.text = settings.goldenFilePatterns.joinToString("\n")
        excludedSuffixesField.text = settings.excludedSuffixes.joinToString(", ")
        extraSettings.forEach { it.reset() }
        updateMatchModeEnablement()
    }

    override fun disposeUIResources() {
        previewTimer.stop()
    }
}
