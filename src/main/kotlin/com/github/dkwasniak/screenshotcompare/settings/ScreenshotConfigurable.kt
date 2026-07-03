package com.github.dkwasniak.screenshotcompare.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

class ScreenshotConfigurable(private val project: Project) : Configurable {

    private val goldenModel = DefaultListModel<String>()
    private val goldenList = JBList(goldenModel)
    private val generatedModel = DefaultListModel<String>()
    private val generatedList = JBList(generatedModel)
    private val generatedRegexField = JTextField()

    override fun getDisplayName(): String = "Screenshot Compare"

    override fun createComponent(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        panel.add(directorySection("Golden directories:", goldenList, goldenModel))
        panel.add(spacer())
        panel.add(directorySection("Generated test output directories:", generatedList, generatedModel))
        panel.add(spacer())
        panel.add(regexSection())
        reset()
        return panel
    }

    private fun directorySection(title: String, list: JBList<String>, model: DefaultListModel<String>): JPanel {
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

        return JPanel(BorderLayout()).apply {
            add(
                JBLabel(title).apply {
                    border = JBUI.Borders.emptyBottom(6)
                },
                BorderLayout.NORTH,
            )
            add(decorator.createPanel(), BorderLayout.CENTER)
        }
    }

    private fun regexSection(): JPanel =
        JPanel(BorderLayout()).apply {
            add(
                JBLabel("Generated file regex:").apply {
                    border = JBUI.Borders.emptyBottom(6)
                },
                BorderLayout.NORTH,
            )
            generatedRegexField.toolTipText =
                "Regex applied to generated file names. The first capture group should be the golden base name."
            add(generatedRegexField, BorderLayout.CENTER)
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

    override fun isModified(): Boolean {
        val settings = ScreenshotSettings.getInstance(project)
        return currentPaths(goldenModel) != settings.paths ||
            currentPaths(generatedModel) != settings.generatedPaths ||
            generatedRegexField.text != settings.generatedFileRegex
    }

    override fun apply() {
        val regex = generatedRegexField.text.ifBlank { ScreenshotSettings.DEFAULT_GENERATED_FILE_REGEX }
        try {
            Regex(regex)
        } catch (e: IllegalArgumentException) {
            throw ConfigurationException("Generated file regex is invalid: ${e.message}")
        }
        val settings = ScreenshotSettings.getInstance(project)
        settings.paths = currentPaths(goldenModel)
        settings.generatedPaths = currentPaths(generatedModel)
        settings.generatedFileRegex = regex
    }

    override fun reset() {
        goldenModel.clear()
        generatedModel.clear()
        val settings = ScreenshotSettings.getInstance(project)
        settings.paths.forEach { goldenModel.addElement(it) }
        settings.generatedPaths.forEach { generatedModel.addElement(it) }
        generatedRegexField.text = settings.generatedFileRegex
    }
}
