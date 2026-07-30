package com.github.dkwasniak.goldendiff.settings

import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.match.GoldenFinder
import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.match.MatchingDefaults
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonSources
import com.github.dkwasniak.goldendiff.variant.ExtraSettingsComponent
import com.github.dkwasniak.goldendiff.telemetry.BuildBadge
import com.github.dkwasniak.goldendiff.telemetry.PluginTelemetryService
import com.github.dkwasniak.goldendiff.telemetry.PluginTelemetrySettings
import com.github.dkwasniak.goldendiff.telemetry.TelemetryBuckets
import com.intellij.ide.BrowserUtil
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.JCheckBox
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

private const val PREVIEW_DEBOUNCE_MS = 300
private const val PREVIEW_DIVIDER_KEY = "golden.diff.settings.preview.divider"
private const val DEFAULT_PREVIEW_DIVIDER = 560

class ScreenshotConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {

    private val goldenModel = DefaultListModel<String>()
    private val goldenList = JBList(goldenModel).apply { name = "goldenDirectoriesList" }
    private val generatedModel = DefaultListModel<String>()
    private val generatedList = JBList(generatedModel).apply { name = "generatedDirectoriesList" }
    private val generatedRegexField = singleLineField()
    private val annotationRegexField = singleLineField()
    private val annotatedMethodRadio = JRadioButton("Match by annotated method")
    private val fileClassRadio = JRadioButton("Match by file/class regex")
    private val goldenPatternsArea = JTextArea(4, 40).apply {
        lineWrap = false
        margin = JBUI.insets(4, 6)
    }
    private val excludedSuffixesField = singleLineField()
    private val generateAllCommandField = singleLineField().apply { name = "generateAllCommandField" }
    private val generateFileCommandField = singleLineField().apply { name = "generateViewCommandField" }
    private val verifyAllCommandField = singleLineField().apply { name = "verifyAllCommandField" }
    private val verifyFileCommandField = singleLineField().apply { name = "verifyViewCommandField" }
    private val verifyCaseCommandField = singleLineField().apply { name = "verifyCaseCommandField" }
    private val trimTransparentPaddingCheckbox =
        JCheckBox("Trim transparent padding around image content")
    private val notifyWhenTasksFinishCheckbox = JCheckBox("Notify when tasks finish")
    private val analyticsCheckbox = JCheckBox("Share anonymous product analytics")
    private val diagnosticsCheckbox = JCheckBox("Share diagnostic error reports")
    private val previewCountLabel = JBLabel()
    private val previewSummaryLabel = JBLabel()
    private val previewModel = DefaultListModel<String>()
    private val previewList = JBList(previewModel)
    private val directoryRegexField = singleLineField()
    private val directoryRegexAddButton = JButton("Add regex")
    private lateinit var matchingOptions: JPanel
    private val extraSettingsSources: List<Pair<String, ExtraSettingsComponent>> =
        ExtraComparisonSources.all.mapNotNull { source ->
            source.createSettingsComponent(project)?.let { source.title to it }
        }
    private val extraSettings: List<ExtraSettingsComponent> = extraSettingsSources.map { it.second }
    // Debounces live preview updates as the user edits regexes / directories.
    private val previewTimer = Timer(PREVIEW_DEBOUNCE_MS) { runPreview() }.apply { isRepeats = false }

    override fun getDisplayName(): String = "Golden Diff"

    override fun createComponent(): JComponent {
        val pages = buildList {
            add(
                SettingsPage(
                    "Directories",
                    formPage(
                        "Directories",
                        "Choose the committed baselines and, optionally, the fresh screenshots produced by verification tests.",
                        directorySection(
                            "Golden directories",
                            "Directories holding the committed screenshots Golden Diff compares against.",
                            goldenList,
                            goldenModel,
                            allowRegex = true,
                        ),
                        spacer(20),
                        directorySection(
                            "Generated output directories",
                            "Where verification tests write their screenshots. Only needed for Test output.",
                            generatedList,
                            generatedModel,
                        ),
                    ),
                    showsPreview = true,
                ),
            )
            add(
                SettingsPage(
                    "Test output matching",
                    formPage(
                        "Test output matching",
                        "Map a generated screenshot back to its committed golden.",
                        regexSection(),
                    ),
                    showsPreview = true,
                ),
            )
            add(
                SettingsPage(
                    "Golden matching",
                    formPage(
                        "Golden matching",
                        "Match goldens either by the name of annotated screenshot functions, or by a file/class regex.",
                        matchingSection(),
                    ),
                    showsPreview = true,
                ),
            )
            add(
                SettingsPage(
                    "Filtering",
                    formPage(
                        "Filtering",
                        "Hide generated artifacts that should not appear as committed goldens.",
                        excludedSuffixesSection(),
                    ),
                    showsPreview = true,
                ),
            )
            add(
                SettingsPage(
                    "Display",
                    formPage(
                        "Display",
                        "Control how screenshot images are presented in the plugin.",
                        displaySection(),
                    ),
                ),
            )
            add(
                SettingsPage(
                    "Commands",
                    formPage(
                        "Commands",
                        "Configure the framework-agnostic shell commands exposed by the tool window.",
                        commandsSection(),
                    ),
                ),
            )
            add(
                SettingsPage(
                    "Notifications",
                    formPage(
                        "Notifications",
                        "Notify after successful or failed screenshot commands while the IDE is in the background.",
                        JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            add(notifyWhenTasksFinishCheckbox)
                            add(
                                labelBlock(
                                    "Completion notifications:",
                                    "Manual cancellation is silent. Open the notification action to return to the command Run output.",
                                ),
                            )
                        },
                    ),
                ),
            )
            add(
                SettingsPage(
                    "Privacy",
                    formPage(
                        "Privacy",
                        "Control anonymous product analytics and sanitized diagnostic reports independently.",
                        privacySection(),
                    ),
                ),
            )
            extraSettingsSources.forEach { (title, settings) ->
                add(
                    SettingsPage(
                        title,
                        formPage(
                            title,
                            "Settings provided by the $title comparison source.",
                            settings.component,
                        ),
                    ),
                )
            }
        }

        val cardsLayout = CardLayout()
        val cards = JPanel(cardsLayout)
        pages.forEachIndexed { index, page ->
            cards.add(page.component, index.toString())
        }
        val preview = previewSection()
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cards, preview).apply {
            name = "settingsPreviewSplit"
            border = null
            dividerSize = JBUI.scale(5)
            resizeWeight = 1.0
            isContinuousLayout = true
            val saved = PropertiesComponent.getInstance(project)
                .getInt(PREVIEW_DIVIDER_KEY, JBUI.scale(DEFAULT_PREVIEW_DIVIDER))
            dividerLocation = saved
            addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
                if (rightComponent != null) {
                    PropertiesComponent.getInstance(project)
                        .setValue(PREVIEW_DIVIDER_KEY, dividerLocation, JBUI.scale(DEFAULT_PREVIEW_DIVIDER))
                }
            }
        }
        val navigation = settingsNavigation(pages) { index ->
            cardsLayout.show(cards, index.toString())
            if (pages[index].showsPreview) {
                if (split.rightComponent == null) split.rightComponent = preview
                split.dividerSize = JBUI.scale(5)
                val saved = PropertiesComponent.getInstance(project)
                    .getInt(PREVIEW_DIVIDER_KEY, JBUI.scale(DEFAULT_PREVIEW_DIVIDER))
                split.dividerLocation = saved
                schedulePreview()
            } else {
                split.rightComponent = null
                split.dividerSize = 0
            }
        }

        wirePreviewTriggers()
        reset()
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(920), JBUI.scale(560))
            add(navigation, BorderLayout.WEST)
            add(split, BorderLayout.CENTER)
            add(versionFooter(), BorderLayout.SOUTH)
        }
    }

    private data class SettingsPage(
        val title: String,
        val component: JComponent,
        val showsPreview: Boolean = false,
    )

    private fun settingsNavigation(pages: List<SettingsPage>, onSelected: (Int) -> Unit): JComponent {
        val model = DefaultListModel<String>().apply { pages.forEach { addElement(it.title) } }
        val list = JBList(model).apply {
            name = "settingsNavigation"
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectedIndex = 0
            fixedCellHeight = JBUI.scale(34)
            border = JBUI.Borders.empty(10, 8)
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    val component = super.getListCellRendererComponent(
                        list,
                        value,
                        index,
                        isSelected,
                        cellHasFocus,
                    ) as JLabel
                    component.border = JBUI.Borders.empty(6, 12)
                    component.font = component.font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN)
                    if (isSelected) {
                        component.background = UIUtil.getListSelectionBackground(true)
                        component.foreground = UIUtil.getListSelectionForeground(true)
                    }
                    return component
                }
            }
            addListSelectionListener {
                if (!it.valueIsAdjusting && selectedIndex >= 0) onSelected(selectedIndex)
            }
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(210), 1)
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, UIUtil.getBoundsColor())
            add(list, BorderLayout.CENTER)
        }
    }

    private fun formPage(title: String, description: String, vararg content: JComponent): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(22, 28, 28, 28)
            add(pageHeader(title, description))
            content.forEach(::add)
            add(Box.createVerticalGlue())
        }
        return JBScrollPane(panel).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun pageHeader(title: String, description: String): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(18)
            add(
                JBLabel(title).apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(20).toFloat())
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            add(
                JBLabel("<html><div style='width:420px'>$description</div></html>").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyTop(6)
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    // Pinned to the bottom-left of the settings panel, next to the dialog's buttons. A developer or
    // beta build shows its badges directly after the version (DEV BUILD before BETA); stable shows none.
    private fun versionFooter(): JComponent {
        val service = PluginTelemetryService.getInstance()
        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(
                JBLabel("Golden Diff ${service.appVersion}").apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
            )
            service.metadata.badges.forEach { badge ->
                add(Box.createRigidArea(Dimension(JBUI.scale(6), 0)))
                add(badgeLabel(badge))
            }
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 2, 2, 2)
            add(left, BorderLayout.WEST)
        }
    }

    private fun badgeLabel(badge: BuildBadge): JComponent {
        val color = when (badge) {
            BuildBadge.DEV -> JBColor(Color(0xA9761A), Color(0xD9A441))
            BuildBadge.BETA -> JBColor(Color(0x3574F0), Color(0x548AF7))
        }
        return JBLabel(badge.label).apply {
            foreground = color
            font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            border = BorderFactory.createCompoundBorder(
                RoundedLineBorder(color, JBUI.scale(6), 1),
                JBUI.Borders.empty(0, 4),
            )
        }
    }

    private fun previewSection(): JPanel {
        previewList.visibleRowCount = 12
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(280), 1)
            minimumSize = Dimension(JBUI.scale(220), 1)
            border = BorderFactory.createMatteBorder(0, 1, 0, 0, UIUtil.getBoundsColor())
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(12, 14, 10, 14)
                    add(
                        JBLabel("Preview (live)").apply {
                            font = font.deriveFont(Font.BOLD)
                        },
                        BorderLayout.WEST,
                    )
                    add(previewCountLabel, BorderLayout.EAST)
                },
                BorderLayout.NORTH,
            )
            add(
                JPanel(BorderLayout()).apply {
                    previewSummaryLabel.border = JBUI.Borders.empty(10, 14)
                    previewSummaryLabel.foreground = UIUtil.getContextHelpForeground()
                    add(previewSummaryLabel, BorderLayout.NORTH)
                    add(JBScrollPane(previewList).apply { border = null }, BorderLayout.CENTER)
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
                    border = JBUI.Borders.emptyTop(4)
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                            BrowserUtil.browse("https://github.com/dkej123/goldendiff/blob/main/docs/privacy.md")
                        }
                    })
                },
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
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
        val goldenPaths = currentPaths(goldenModel)
        val mode = selectedMatchMode()
        val annotationRegex = annotationRegexField.text.ifBlank { MatchingDefaults.ANNOTATION_NAME_REGEX }
        val patterns = parsePatterns(goldenPatternsArea.text).ifEmpty { MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS }
        val excluded = parseSuffixes(excludedSuffixesField.text)

        if (goldenPaths.isEmpty()) {
            showPreview("Add a golden directory to preview matches.", emptyList())
            return
        }
        previewCountLabel.text = "…"
        previewSummaryLabel.text = "Searching…"
        previewModel.clear()
        // Capture the dialog's modality so the result is delivered while Settings is still open.
        val modality = ModalityState.stateForComponent(previewList)
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching {
                val roots = GoldenDiffConfig(goldenPaths = goldenPaths)
                    .resolvedGoldenPaths(project.basePath?.let(::File))
                val screen = CurrentScreen.compute(project, annotationRegex)
                val files = if (screen == null) emptyList() else GoldenFinder.find(roots, screen, mode, excluded, patterns)
                Triple(screen, files, roots)
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    result.onFailure { showPreview("Preview failed: ${it.message}", emptyList()) }
                    result.onSuccess { (screen, files, roots) ->
                        if (roots.isEmpty()) {
                            showPreview("No directories match the configured paths or regexes.", emptyList())
                        } else if (screen == null) {
                            showPreview("Open a source or test file in the editor to preview matches.", emptyList())
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
        previewCountLabel.text = paths.size.toString()
        previewSummaryLabel.text = "<html><div style='width:230px'>${htmlEscape(summary)}</div></html>"
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
        allowRegex: Boolean = false,
    ): JPanel {
        list.visibleRowCount = 4
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val raw = value?.toString().orEmpty()
                val pattern = GoldenDiffConfig.goldenDirectoryRegex(raw)
                val display = if (pattern == null) {
                    raw
                } else {
                    "<html><b>Regex</b>&nbsp;&nbsp;<code>${htmlEscape(pattern)}</code></html>"
                }
                return super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus).apply {
                    border = JBUI.Borders.empty(7, 10)
                }
            }
        }
        val addButton = JButton("+ Add directory").apply {
            addActionListener {
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Select $title")
                    .withDescription(description)
                val chosen = FileChooser.chooseFile(descriptor, project, null)
                val path = chosen?.path?.let(::storagePath)
                if (path != null && !modelContains(model, path)) {
                    model.addElement(path)
                    list.selectedIndex = model.size() - 1
                }
            }
        }
        val removeButton = JButton("Remove").apply {
            isEnabled = false
            addActionListener {
                list.selectedValuesList.forEach { model.removeElement(it) }
            }
        }
        list.addListSelectionListener { removeButton.isEnabled = !list.isSelectionEmpty }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(labelBlock(title, description))
            add(
                JBScrollPane(list).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    preferredSize = Dimension(JBUI.scale(520), JBUI.scale(124))
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(124))
                },
            )
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyTop(8)
                    add(addButton)
                    add(Box.createRigidArea(Dimension(JBUI.scale(8), 1)))
                    add(removeButton)
                    add(Box.createHorizontalGlue())
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                },
            )
            if (allowRegex) {
                add(spacer())
                add(
                    labelBlock(
                        "Directory regex",
                        "Matched against the complete project-relative directory path using / separators.",
                    ),
                )
                directoryRegexField.toolTipText = "Example: .*/src/test/snapshots"
                directoryRegexAddButton.isEnabled = false
                directoryRegexAddButton.addActionListener {
                    val pattern = directoryRegexField.text.trim()
                    val entry = GoldenDiffConfig.goldenDirectoryRegexEntry(pattern)
                    if (pattern.isNotEmpty() && runCatching { Regex(pattern) }.isSuccess && !modelContains(model, entry)) {
                        model.addElement(entry)
                        list.selectedIndex = model.size() - 1
                        directoryRegexField.text = ""
                    }
                }
                directoryRegexField.document.addDocumentListener(
                    object : DocumentListener {
                        private fun update() {
                            val pattern = directoryRegexField.text.trim()
                            val entry = GoldenDiffConfig.goldenDirectoryRegexEntry(pattern)
                            directoryRegexAddButton.isEnabled =
                                pattern.isNotEmpty() &&
                                runCatching { Regex(pattern) }.isSuccess &&
                                !modelContains(model, entry)
                        }

                        override fun insertUpdate(e: DocumentEvent) = update()
                        override fun removeUpdate(e: DocumentEvent) = update()
                        override fun changedUpdate(e: DocumentEvent) = update()
                    },
                )
                add(
                    JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        add(directoryRegexField, BorderLayout.CENTER)
                        add(directoryRegexAddButton, BorderLayout.EAST)
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    },
                )
            }
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun regexSection(): JPanel =
        JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(94))
            add(
                labelBlock(
                    "Generated output filename regex:",
                    "Maps a golden to its Test output. The first capture group must be the matching golden basename.",
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

        val annotatedFields = JPanel(BorderLayout()).apply {
            add(
                labelBlock(
                    "Screenshot annotation name regex",
                    "Annotated functions whose annotation name matches become golden candidates; the golden must contain the function name.",
                ),
                BorderLayout.NORTH,
            )
            annotationRegexField.toolTipText =
                "Annotation names matched here make their functions golden candidates, e.g. .*Preview.*|Test|ScreenshotTest."
            add(annotationRegexField, BorderLayout.CENTER)
        }
        val fileClassFields = JPanel(BorderLayout()).apply {
            add(
                labelBlock(
                    "Golden filename regex (one per line)",
                    "Matched against each golden's path. {file_name} and {class_name} are substituted first.",
                ),
                BorderLayout.NORTH,
            )
            goldenPatternsArea.toolTipText =
                "{file_name} = current file name, {class_name} = a class declared in the file."
            add(
                JBScrollPane(goldenPatternsArea).apply {
                    preferredSize = Dimension(1, JBUI.scale(116))
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(116))
                },
                BorderLayout.CENTER,
            )
        }
        matchingOptions = JPanel(CardLayout()).apply {
            add(annotatedFields, MatchMode.ANNOTATED_METHOD.name)
            add(fileClassFields, MatchMode.FILE_CLASS_REGEX.name)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(
                labelBlock(
                    "Golden matching mode",
                    "Match goldens either by the name of annotated screenshot functions, or by a file/class regex.",
                ),
            )
            add(annotatedMethodRadio)
            add(fileClassRadio)
            add(spacer())
            add(matchingOptions)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun updateMatchModeEnablement() {
        annotationRegexField.isEnabled = annotatedMethodRadio.isSelected
        goldenPatternsArea.isEnabled = fileClassRadio.isSelected
        if (::matchingOptions.isInitialized) {
            (matchingOptions.layout as CardLayout).show(matchingOptions, selectedMatchMode().name)
        }
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

    private fun commandsSection(): JPanel {
        val viewPlaceholders = com.github.dkwasniak.goldendiff.command.CommandTemplate.PLACEHOLDERS
            .take(3)
            .joinToString("  ·  ") { (token, meaning) -> "$token = $meaning" }
        val casePlaceholders = com.github.dkwasniak.goldendiff.command.CommandTemplate.PLACEHOLDERS
            .drop(3)
            .joinToString("  ·  ") { (token, meaning) -> "$token = $meaning" }
        val viewPlaceholderTokens = com.github.dkwasniak.goldendiff.command.CommandTemplate.PLACEHOLDERS
            .take(3)
            .joinToString(", ") { it.first }
        val casePlaceholderTokens = com.github.dkwasniak.goldendiff.command.CommandTemplate.PLACEHOLDERS
            .drop(3)
            .joinToString(", ") { it.first }
        fun commandBlock(title: String, description: String, field: JTextField): JPanel {
            val fieldHeight = field.preferredSize.height.coerceAtLeast(JBUI.scale(28))
            field.alignmentX = Component.LEFT_ALIGNMENT
            field.minimumSize = Dimension(JBUI.scale(120), fieldHeight)
            field.preferredSize = Dimension(JBUI.scale(520), fieldHeight)
            field.maximumSize = Dimension(Int.MAX_VALUE, fieldHeight)
            return JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(labelBlock(title, description))
                add(field)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(
                labelBlock(
                    "Screenshot test commands (optional):",
                    "Shell commands run from the tool window. Leave blank to hide the corresponding action.",
                ),
            )
            generateAllCommandField.toolTipText = "Whole-project command; placeholders are not expanded."
            verifyAllCommandField.toolTipText = "Whole-project command; placeholders are not expanded."
            generateFileCommandField.toolTipText =
                "Current file placeholders: $viewPlaceholders"
            verifyFileCommandField.toolTipText =
                "Current file placeholders: $viewPlaceholders"
            verifyCaseCommandField.toolTipText =
                "Current case placeholders: $casePlaceholders"
            add(
                commandBlock(
                    "Generate all goldens:",
                    "Run for the whole project. Placeholders are not expanded.",
                    generateAllCommandField,
                ),
            )
            add(spacer())
            add(
                commandBlock(
                    "Generate for current file:",
                    "Regenerate only the current file's goldens. File placeholders: $viewPlaceholderTokens.",
                    generateFileCommandField,
                ),
            )
            add(spacer())
            add(
                commandBlock(
                    "Verify all goldens:",
                    "Run for the whole project. Placeholders are not expanded.",
                    verifyAllCommandField,
                ),
            )
            add(spacer())
            add(
                commandBlock(
                    "Verify current file:",
                    "Verify only the current file's goldens. File placeholders: $viewPlaceholderTokens.",
                    verifyFileCommandField,
                ),
            )
            add(spacer())
            add(
                commandBlock(
                    "Verify current case:",
                    "Optional method/preview command. Leave blank to use Verify current file for its generator. " +
                        "Case placeholders: $casePlaceholderTokens.",
                    verifyCaseCommandField,
                ),
            )
        }
    }

    private fun singleLineField(): JTextField =
        JTextField().apply {
            margin = JBUI.insets(2, 6)
            preferredSize = Dimension(JBUI.scale(420), preferredSize.height)
            maximumSize = Dimension(JBUI.scale(520), preferredSize.height)
        }

    private fun labelBlock(title: String, description: String): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 0, 8, 0)
            add(
                JBLabel(title).apply {
                    font = font.deriveFont(Font.BOLD)
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            add(
                JBLabel("<html><div style='width:420px'>$description</div></html>").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyTop(4)
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun spacer(height: Int = 12): JPanel =
        JPanel().apply {
            maximumSize = Dimension(1, JBUI.scale(height))
            preferredSize = Dimension(1, JBUI.scale(height))
        }

    private fun currentPaths(model: DefaultListModel<String>): List<String> =
        (0 until model.size()).map { model.getElementAt(it) }

    private fun currentStoragePaths(model: DefaultListModel<String>): List<String> =
        currentPaths(model).map { path ->
            if (GoldenDiffConfig.goldenDirectoryRegex(path) != null) path else storagePath(path)
        }

    private fun modelContains(model: DefaultListModel<String>, value: String): Boolean =
        (0 until model.size()).any { model.getElementAt(it) == value }

    private fun htmlEscape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
            generateAllCommandField.text != settings.generateAllCommand ||
            generateFileCommandField.text != settings.generateFileCommand ||
            verifyAllCommandField.text != settings.verifyAllCommand ||
            verifyFileCommandField.text != settings.verifyFileCommand ||
            verifyCaseCommandField.text != settings.verifyCaseCommand ||
            notifyWhenTasksFinishCheckbox.isSelected != settings.notifyWhenTasksFinish ||
            trimTransparentPaddingCheckbox.isSelected != settings.trimTransparentPadding ||
            analyticsCheckbox.isSelected != PluginTelemetrySettings.getInstance().analyticsEnabled ||
            diagnosticsCheckbox.isSelected != PluginTelemetrySettings.getInstance().diagnosticsEnabled ||
            extraSettings.any { it.isModified() }
    }

    override fun apply() {
        currentPaths(goldenModel).forEach { path ->
            GoldenDiffConfig.goldenDirectoryRegex(path)?.let { pattern ->
                try {
                    Regex(pattern)
                } catch (e: IllegalArgumentException) {
                    throw ConfigurationException("Golden directory regex is invalid: ${e.message}")
                }
            }
        }
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
        settings.generateAllCommand = generateAllCommandField.text.trim()
        settings.generateFileCommand = generateFileCommandField.text.trim()
        settings.verifyAllCommand = verifyAllCommandField.text.trim()
        settings.verifyFileCommand = verifyFileCommandField.text.trim()
        settings.verifyCaseCommand = verifyCaseCommandField.text.trim()
        settings.notifyWhenTasksFinish = notifyWhenTasksFinishCheckbox.isSelected
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
        generateAllCommandField.text = settings.generateAllCommand
        generateFileCommandField.text = settings.generateFileCommand
        verifyAllCommandField.text = settings.verifyAllCommand
        verifyFileCommandField.text = settings.verifyFileCommand
        verifyCaseCommandField.text = settings.verifyCaseCommand
        notifyWhenTasksFinishCheckbox.isSelected = settings.notifyWhenTasksFinish
        directoryRegexField.text = ""
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
        if (GoldenDiffConfig.goldenDirectoryRegex(path) != null) return path
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
