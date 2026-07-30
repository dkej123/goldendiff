package com.github.dkwasniak.goldendiff.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JButton

/**
 * Compact header action without permanent button chrome.
 *
 * The hover and pressed washes provide pointer feedback while keeping the idle toolbar visually
 * flat in the IntelliJ New UI.
 */
internal class FlatHeaderButton(
    text: String,
    icon: Icon? = null,
    height: Int = 24,
    horizontalPadding: Int = 8,
) : JButton(text, icon) {
    init {
        font = font.deriveFont(
            JBUI.scaleFontSize(if (height <= 18) 9.5f else 11f).toFloat(),
        )
        margin = JBUI.insets(0, horizontalPadding)
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        preferredSize = Dimension(preferredSize.width, JBUI.scale(height))
        minimumSize = preferredSize
        getAccessibleContext()?.accessibleName = text
    }

    override fun paintComponent(graphics: Graphics) {
        if (isEnabled && (model.isRollover || model.isPressed)) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = if (model.isPressed) PressedColor else HoverColor
                g.fillRoundRect(
                    0,
                    JBUI.scale(1),
                    width,
                    (height - JBUI.scale(2)).coerceAtLeast(0),
                    JBUI.scale(5),
                    JBUI.scale(5),
                )
            } finally {
                g.dispose()
            }
        }
        super.paintComponent(graphics)
    }

    private companion object {
        val HoverColor = JBColor(Color(0, 0, 0, 12), Color(255, 255, 255, 14))
        val PressedColor = JBColor(Color(53, 116, 240, 38), Color(84, 138, 247, 48))
    }
}
