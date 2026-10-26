package com.github.dkwasniak.goldendiff.toolwindow

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants

/** Rounded status/count pill ("3", "Failed", "2 failed"); hidden when [setPill] receives null. */
internal class PillLabel(fontSize: Float = 10f) : JLabel("", SwingConstants.CENTER) {
    private var fill: Color = GoldenDiffColors.disabledFill

    init {
        isOpaque = false
        font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(fontSize).toFloat())
        border = JBUI.Borders.empty(1, 6)
        isVisible = false
    }

    fun setPill(text: String?, foreground: Color = GoldenDiffColors.failed, background: Color = foreground.withAlpha(36)) {
        isVisible = text != null
        this.text = text.orEmpty()
        this.foreground = foreground
        fill = background
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = fill
            g.fillRoundRect(0, 0, width, height, height, height)
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
    }
}

/** Status badge for a Files row; unchanged rows show no badge. */
internal fun PillLabel.showStatus(status: GoldenDisplayStatus) {
    val label = status.badgeLabel
    if (label == null) {
        setPill(null)
    } else {
        setPill(label, GoldenDiffColors.status(status), GoldenDiffColors.statusBackground(status))
    }
}

/**
 * Compact segmented selector (Side / Swipe / Onion / Diff). Selecting a segment programmatically
 * through [select] never fires [onSelect].
 */
internal class SegmentedControl<T>(
    options: List<Pair<T, String>>,
    private val onSelect: (T) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)) {
    private val buttons = linkedMapOf<T, SegmentButton>()
    private var notifying = true

    init {
        isOpaque = false
        val group = ButtonGroup()
        options.forEach { (value, label) ->
            val button = SegmentButton(label).apply {
                addActionListener { if (notifying && isSelected) onSelect(value) }
            }
            group.add(button)
            buttons[value] = button
            add(button)
        }
    }

    fun select(value: T) {
        notifying = false
        try {
            buttons[value]?.isSelected = true
        } finally {
            notifying = true
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        buttons.values.forEach { it.isEnabled = enabled }
    }

    private class SegmentButton(text: String) : JToggleButton(text) {
        init {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isRolloverEnabled = true
            margin = JBUI.emptyInsets()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
            getAccessibleContext()?.accessibleName = "$text comparison mode"
        }

        override fun getPreferredSize(): Dimension {
            val bold = font.deriveFont(Font.BOLD)
            return Dimension(getFontMetrics(bold).stringWidth(text) + JBUI.scale(16), JBUI.scale(22))
        }

        override fun getMinimumSize(): Dimension = preferredSize

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (isSelected || (isEnabled && model.isRollover)) {
                    g.color = if (isSelected) GoldenDiffColors.accentBackground else GoldenDiffColors.disabledFill
                    g.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
                }
                if (hasFocus()) {
                    g.color = GoldenDiffColors.accent
                    g.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
                }
                g.font = if (isSelected) font.deriveFont(Font.BOLD) else font
                g.color = when {
                    !isEnabled -> GoldenDiffColors.faint
                    isSelected -> GoldenDiffColors.accent
                    else -> GoldenDiffColors.dim
                }
                val metrics = g.fontMetrics
                g.drawString(
                    text,
                    (width - metrics.stringWidth(text)) / 2,
                    (height - metrics.height) / 2 + metrics.ascent,
                )
            } finally {
                g.dispose()
            }
        }
    }
}

/** Files / Review tab strip: bold active label with a 2px accent underline and a failed badge. */
internal class ReviewTabsHeader(
    private val onSelect: (ReviewTab) -> Unit,
) : JPanel(GridLayout(1, 2)) {
    private val filesTab = TabButton("Files")
    private val reviewTab = TabButton("Review")

    init {
        isOpaque = true
        background = GoldenDiffColors.background
        border = JBUI.Borders.customLine(GoldenDiffColors.border, 0, 0, 1, 0)
        val group = ButtonGroup()
        listOf(filesTab to ReviewTab.FILES, reviewTab to ReviewTab.REVIEW).forEach { (button, tab) ->
            group.add(button)
            button.addActionListener { onSelect(tab) }
            add(button)
        }
    }

    fun render(tab: ReviewTab, failedViews: Int) {
        filesTab.isSelected = tab == ReviewTab.FILES
        reviewTab.isSelected = tab == ReviewTab.REVIEW
        filesTab.badge = failedViews.takeIf { it > 0 }?.toString()
        filesTab.getAccessibleContext()?.accessibleName =
            if (failedViews > 0) "Files, $failedViews failed" else "Files"
        repaint()
    }

    private class TabButton(text: String) : JToggleButton(text) {
        var badge: String? = null

        init {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isRolloverEnabled = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(12f).toFloat())
            getAccessibleContext()?.accessibleName = text
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(80), JBUI.scale(36))

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.font = if (isSelected) font.deriveFont(Font.BOLD) else font
                val metrics = g.fontMetrics
                val badgeFont = font.deriveFont(Font.BOLD, JBUI.scaleFontSize(10f).toFloat())
                val badgeMetrics = g.getFontMetrics(badgeFont)
                val badgeText = badge
                val gap = JBUI.scale(6)
                val badgeWidth = badgeText?.let { badgeMetrics.stringWidth(it) + JBUI.scale(12) } ?: 0
                val contentWidth = metrics.stringWidth(text) + if (badgeText != null) gap + badgeWidth else 0
                var x = (width - contentWidth) / 2
                val baseline = (height - metrics.height) / 2 + metrics.ascent
                g.color = when {
                    isSelected -> GoldenDiffColors.text
                    model.isRollover -> GoldenDiffColors.text
                    else -> GoldenDiffColors.dim
                }
                g.drawString(text, x, baseline)
                x += metrics.stringWidth(text) + gap
                if (badgeText != null) {
                    val badgeHeight = badgeMetrics.height + JBUI.scale(2)
                    val badgeY = (height - badgeHeight) / 2
                    g.color = GoldenDiffColors.failed.withAlpha(36)
                    g.fillRoundRect(x, badgeY, badgeWidth, badgeHeight, badgeHeight, badgeHeight)
                    g.font = badgeFont
                    g.color = GoldenDiffColors.failed
                    g.drawString(
                        badgeText,
                        x + (badgeWidth - badgeMetrics.stringWidth(badgeText)) / 2,
                        badgeY + (badgeHeight - badgeMetrics.height) / 2 + badgeMetrics.ascent,
                    )
                }
                if (isSelected) {
                    g.color = GoldenDiffColors.accent
                    g.fillRect(0, height - JBUI.scale(2), width, JBUI.scale(2))
                }
                if (hasFocus()) {
                    g.color = GoldenDiffColors.accent
                    g.stroke = BasicStroke(1f)
                    g.drawRect(1, 1, width - 3, height - 4)
                }
            } finally {
                g.dispose()
            }
        }
    }
}

/** Failed / Modified / New filter chip: outlined when off, filled with the status colour when on. */
internal class StatusChipToggle(
    text: String,
    private val statusColor: Color,
) : JToggleButton(text) {
    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(10.5f).toFloat())
        toolTipText = "Show $text files"
        getAccessibleContext()?.accessibleName = "$text status filter"
    }

    override fun getPreferredSize(): Dimension =
        Dimension(getFontMetrics(font).stringWidth(text) + JBUI.scale(18), JBUI.scale(20))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (isSelected) {
                g.color = statusColor
                g.fillRoundRect(0, 0, width, height, height, height)
            } else {
                g.color = statusColor.withAlpha(102)
                g.drawRoundRect(0, 0, width - 1, height - 1, height - 1, height - 1)
            }
            if (hasFocus()) {
                g.color = GoldenDiffColors.accent
                g.drawRoundRect(0, 0, width - 1, height - 1, height - 1, height - 1)
            }
            g.font = font
            g.color = if (isSelected) GoldenDiffColors.background else statusColor
            val metrics = g.fontMetrics
            g.drawString(text, (width - metrics.stringWidth(text)) / 2, (height - metrics.height) / 2 + metrics.ascent)
        } finally {
            g.dispose()
        }
    }
}
