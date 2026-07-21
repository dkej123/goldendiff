package com.github.dkwasniak.goldendiff.compare

import com.github.dkwasniak.goldendiff.variant.ExtraSettingsComponent
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

class FigmaSettingsComponent(
    private val project: Project,
) : ExtraSettingsComponent {
    private val settings = FigmaSettings.getInstance(project)

    private val goldenPathField = JBTextField().apply {
        margin = JBUI.insets(2, 6)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        toolTipText = "Directory with committed Figma reference PNGs. Relative to the project root; " +
            "default ${FigmaSettings.DEFAULT_GOLDEN_PATH}."
    }
    private val tokenField = JPasswordField().apply {
        margin = JBUI.insets(2, 6)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        toolTipText = "Figma personal access token with file_content:read access."
    }

    override val component: JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(
                fieldSection(
                    "Figma reference directory:",
                    "Committed Figma reference PNGs used as the comparison baseline.",
                    goldenPathField,
                ),
            )
            add(spacer())
            add(
                fieldSection(
                    "Figma API token:",
                    "Stored in the IDE password safe; used only to download missing Figma references into cache.",
                    tokenField,
                ),
            )
        }

    override fun isModified(): Boolean =
        goldenPathText() != settings.goldenPath ||
            tokenText() != FigmaTokenStore.get(project).orEmpty()

    override fun apply() {
        settings.goldenPath = goldenPathText()
        FigmaTokenStore.set(project, tokenText())
    }

    override fun reset() {
        goldenPathField.text = settings.goldenPath
        tokenField.text = FigmaTokenStore.get(project).orEmpty()
    }

    // Same NORTH-label / CENTER-field wrapping the main settings sections use, so the labels and the
    // field share the panel's left edge instead of drifting when stacked in a Y_AXIS BoxLayout.
    private fun fieldSection(title: String, help: String, field: JComponent): JPanel =
        JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(64))
            add(labelBlock(title, help), BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
        }

    private fun labelBlock(title: String, help: String): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(6)
            add(JBLabel(title))
            add(
                JBLabel(help).apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
            )
        }

    private fun spacer(): JPanel =
        JPanel().apply {
            maximumSize = Dimension(1, JBUI.scale(12))
            preferredSize = Dimension(1, JBUI.scale(12))
        }

    private fun goldenPathText(): String =
        goldenPathField.text.trim()

    private fun tokenText(): String =
        String(tokenField.password).trim()
}
