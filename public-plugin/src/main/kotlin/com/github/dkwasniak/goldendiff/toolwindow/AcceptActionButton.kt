package com.github.dkwasniak.goldendiff.toolwindow

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton

/** Compact destructive action matching the approved HTML handoff without native JButton insets. */
internal class AcceptActionButton(
    text: String,
    private val fill: Color,
    private val pill: Boolean,
) : JButton(text) {
    init {
        font = UIUtil.getLabelFont().deriveFont(
            java.awt.Font.BOLD,
            JBUI.scaleFontSize(10.5f).toFloat(),
        )
        foreground = Color.WHITE
        margin = JBUI.emptyInsets()
        // getPreferredSize() reserves exactly the handoff padding, so LaF border insets must not clip the text.
        border = JBUI.Borders.empty()
        horizontalAlignment = CENTER
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
    }

    override fun getPreferredSize(): Dimension {
        val horizontalPadding = JBUI.scale(9)
        return Dimension(
            getFontMetrics(font).stringWidth(text.orEmpty()) + horizontalPadding * 2,
            JBUI.scale(22),
        )
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = when {
                !isEnabled -> DisabledFill
                model.isPressed -> fill.adjustBrightness(0.92)
                model.isRollover -> fill.adjustBrightness(1.08)
                else -> fill
            }
            val arc = if (pill) height else JBUI.scale(5)
            g.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
    }

    private companion object {
        val DisabledFill = GoldenDiffColors.disabledFill
    }
}

private fun Color.adjustBrightness(factor: Double): Color =
    Color(
        (red * factor).toInt().coerceIn(0, 255),
        (green * factor).toInt().coerceIn(0, 255),
        (blue * factor).toInt().coerceIn(0, 255),
        alpha,
    )
