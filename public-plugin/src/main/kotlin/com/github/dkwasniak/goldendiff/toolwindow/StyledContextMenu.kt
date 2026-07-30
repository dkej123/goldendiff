package com.github.dkwasniak.goldendiff.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.plaf.basic.BasicMenuItemUI

/** Compact popup styled to match the Golden Diff tool-window surfaces. */
internal class StyledContextMenu : JPopupMenu() {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(2)
    }

    fun addHeader(text: String) {
        add(ContextMenuHeader(text))
    }

    fun addAction(
        text: String,
        icon: Icon? = null,
        enabled: Boolean = true,
        action: () -> Unit,
    ) {
        add(ContextMenuItem(text, icon).apply {
            isEnabled = enabled
            addActionListener { action() }
        })
    }

    fun addSectionSeparator() {
        add(ContextMenuSeparator())
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = PopupSurface
            g.fillRoundRect(0, 0, width, height, JBUI.scale(10), JBUI.scale(10))
            g.color = PopupBorder
            g.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(10), JBUI.scale(10))
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
    }

    private companion object {
        val PopupSurface = JBColor(Color(0xFFFFFF), Color(0x2B2D30))
        val PopupBorder = JBColor(Color(0xD8DADE), Color(0x4A4D51))
    }
}

private class ContextMenuHeader(text: String) : JLabel(text) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, 6, 2, 6)
        horizontalAlignment = SwingConstants.LEFT
        alignmentX = Component.LEFT_ALIGNMENT
        font = UIUtil.getLabelFont().deriveFont(
            Font.BOLD,
            JBUI.scaleFontSize(10.5f).toFloat(),
        )
        foreground = UIUtil.getContextHelpForeground()
        toolTipText = text
    }

    override fun getPreferredSize(): Dimension =
        super.getPreferredSize().apply { height = JBUI.scale(20) }
}

private class ContextMenuItem(text: String, icon: Icon?) : JMenuItem(text, icon) {
    init {
        setUI(ContextMenuItemUI())
        isOpaque = false
        border = JBUI.Borders.empty(0, 5)
        margin = JBUI.emptyInsets()
        iconTextGap = JBUI.scale(6)
        horizontalAlignment = SwingConstants.LEFT
        horizontalTextPosition = SwingConstants.RIGHT
        alignmentX = Component.LEFT_ALIGNMENT
        font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
        foreground = UIUtil.getLabelForeground()
        disabledIcon = icon
        accessibleContext?.accessibleName = text
    }

    override fun getPreferredSize(): Dimension =
        super.getPreferredSize().apply {
            width += JBUI.scale(8)
            height = JBUI.scale(24)
        }
}

private class ContextMenuItemUI : BasicMenuItemUI() {
    override fun installDefaults() {
        super.installDefaults()
        selectionForeground = UIUtil.getLabelForeground()
        disabledForeground = UIUtil.getContextHelpForeground()
    }

    override fun paintBackground(graphics: Graphics, menuItem: JMenuItem, backgroundColor: Color) {
        if (!menuItem.model.isArmed) return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = HoverColor
            val inset = JBUI.scale(2)
            g.fillRoundRect(
                inset,
                JBUI.scale(1),
                menuItem.width - inset * 2,
                menuItem.height - JBUI.scale(2),
                JBUI.scale(5),
                JBUI.scale(5),
            )
        } finally {
            g.dispose()
        }
    }

    private companion object {
        val HoverColor = JBColor(Color(0xEAF2FF), Color(0x354A67))
    }
}

private class ContextMenuSeparator : JSeparator() {
    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    override fun getPreferredSize(): Dimension = Dimension(1, JBUI.scale(5))

    override fun paintComponent(graphics: Graphics) {
        graphics.color = SeparatorColor
        val inset = JBUI.scale(6)
        graphics.drawLine(inset, height / 2, width - inset, height / 2)
    }

    private companion object {
        val SeparatorColor = JBColor(Color(0xE6E7EA), Color(0x414448))
    }
}
