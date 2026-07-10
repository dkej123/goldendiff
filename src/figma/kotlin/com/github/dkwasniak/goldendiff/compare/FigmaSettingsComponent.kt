package com.github.dkwasniak.goldendiff.compare

import com.github.dkwasniak.goldendiff.variant.ExtraSettingsComponent
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
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
    private val tokenField = JPasswordField().apply {
        margin = JBUI.insets(2, 6)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        toolTipText = "Figma personal access token with file_content:read access."
    }

    override val component: JComponent =
        JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(94))
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.emptyBottom(6)
                    add(JBLabel("Figma API token:"))
                    add(
                        JBLabel("Stored in the IDE password safe; used only to download missing Figma references into cache.").apply {
                            foreground = UIUtil.getContextHelpForeground()
                        },
                    )
                },
                BorderLayout.NORTH,
            )
            add(tokenField, BorderLayout.CENTER)
        }

    override fun isModified(): Boolean =
        tokenText() != FigmaTokenStore.get(project).orEmpty()

    override fun apply() {
        FigmaTokenStore.set(project, tokenText())
    }

    override fun reset() {
        tokenField.text = FigmaTokenStore.get(project).orEmpty()
    }

    private fun tokenText(): String =
        String(tokenField.password).trim()
}
