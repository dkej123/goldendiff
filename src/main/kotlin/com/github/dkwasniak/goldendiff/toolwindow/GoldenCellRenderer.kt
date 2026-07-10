package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.BorderFactory
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/** Renders each golden file as a left-aligned thumbnail. The full filename is available as a tooltip. */
class GoldenCellRenderer : ListCellRenderer<ExtraComparisonItem> {

    private data class Key(val path: String, val lastModified: Long)
    private data class IconKey(val key: Key, val targetWidth: Int)

    private val modifiedBackground = JBColor(Color(0xFFF1F1), Color(0x3A2428))
    private val modifiedAccent = JBColor(Color(0xD36A75), Color(0xA84F5C))
    private val newBackground = JBColor(Color(0xF0F8F2), Color(0x1F3326))
    private val newAccent = JBColor(Color(0x57A869), Color(0x5BA66A))

    private val imageCache = HashMap<Key, BufferedImage?>()
    private val iconCache = HashMap<IconKey, Icon?>()

    fun clearScaledCache() {
        iconCache.clear()
    }

    fun cellHeight(items: List<ExtraComparisonItem>, cellWidth: Int): Int {
        val targetWidth = targetWidth(cellWidth)
        val padding = JBUI.scale(6)
        val maxImageHeight = items.maxOfOrNull { item ->
            imageFor(item.file)?.let { image -> thumbnailSize(image, targetWidth).height }
                ?: placeholderSize(targetWidth).height
        } ?: placeholderSize(targetWidth).height
        return maxImageHeight + padding * 2
    }

    override fun getListCellRendererComponent(
        list: JList<out ExtraComparisonItem>,
        value: ExtraComparisonItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val targetWidth = targetWidth((list.fixedCellWidth.takeIf { it > 0 } ?: list.width))
        val icon = iconFor(value, targetWidth)
        val padding = JBUI.scale(6)
        val panel = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(2))).apply {
            val accent = statusAccent(value.status)
            border = if (accent != null) {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, accent),
                    JBUI.Borders.empty(padding, padding, padding, padding),
                )
            } else {
                JBUI.Borders.empty(padding)
            }
            isOpaque = true
            toolTipText = value.title
            preferredSize = Dimension(
                targetWidth + padding * 2,
                (icon?.iconHeight ?: JBUI.scale(56)) + padding * 2,
            )
        }
        val thumbnail = JBLabel().apply {
            this.icon = icon
            horizontalAlignment = SwingConstants.LEFT
            verticalAlignment = SwingConstants.TOP
            toolTipText = value.title
        }
        if (isSelected) {
            panel.background = list.selectionBackground
        } else {
            panel.background = statusBackground(value.status) ?: list.background
        }
        panel.add(thumbnail, BorderLayout.WEST)
        return panel
    }

    private fun statusAccent(status: ExtraComparisonItemStatus): JBColor? =
        when (status) {
            ExtraComparisonItemStatus.MODIFIED -> modifiedAccent
            ExtraComparisonItemStatus.NEW -> newAccent
            ExtraComparisonItemStatus.UNCHANGED -> null
        }

    private fun statusBackground(status: ExtraComparisonItemStatus): JBColor? =
        when (status) {
            ExtraComparisonItemStatus.MODIFIED -> modifiedBackground
            ExtraComparisonItemStatus.NEW -> newBackground
            ExtraComparisonItemStatus.UNCHANGED -> null
        }

    private fun targetWidth(cellWidth: Int): Int =
        (cellWidth - JBUI.scale(16)).coerceAtLeast(JBUI.scale(56))

    private fun iconFor(item: ExtraComparisonItem, targetWidth: Int): Icon? {
        val file = item.file
        val image = imageFor(file) ?: return PlaceholderIcon(item.title, targetWidth, item.isLoading)
        val key = Key(file.path, file.lastModified())
        val iconKey = IconKey(key, targetWidth)
        return iconCache.getOrPut(iconKey) { FittedImageIcon(image, targetWidth) }
    }

    private fun imageFor(file: File): BufferedImage? {
        val key = Key(file.path, file.lastModified())
        return imageCache.getOrPut(key) { runCatching { ImageIO.read(file) }.getOrNull() }
    }

    private fun thumbnailSize(image: BufferedImage, targetWidth: Int): Dimension {
        val height = (image.height * (targetWidth.toDouble() / image.width)).toInt().coerceAtLeast(1)
        return Dimension(targetWidth, height)
    }

    private fun placeholderSize(targetWidth: Int): Dimension =
        Dimension(targetWidth, (targetWidth * 1.8).toInt().coerceAtLeast(JBUI.scale(96)))

    private inner class FittedImageIcon(
        private val image: BufferedImage,
        targetWidth: Int,
    ) : Icon {
        private val size = thumbnailSize(image, targetWidth)

        override fun getIconWidth(): Int = size.width

        override fun getIconHeight(): Int = size.height

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.drawImage(image, x, y, iconWidth, iconHeight, null)
            } finally {
                g2.dispose()
            }
        }
    }

    private class PlaceholderIcon(
        private val title: String,
        targetWidth: Int,
        private val loading: Boolean,
    ) : Icon {
        private val size = Dimension(targetWidth, (targetWidth * 1.8).toInt().coerceAtLeast(JBUI.scale(96)))
        private val background = JBColor(Color(0xF3F4F6), Color(0x2B2D31))
        private val border = JBColor(Color(0xD1D5DB), Color(0x4B5563))
        private val text = JBColor(Color(0x4B5563), Color(0xD1D5DB))

        override fun getIconWidth(): Int = size.width

        override fun getIconHeight(): Int = size.height

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                g2.fillRoundRect(x, y, iconWidth, iconHeight, JBUI.scale(8), JBUI.scale(8))
                g2.color = border
                g2.drawRoundRect(x, y, iconWidth - 1, iconHeight - 1, JBUI.scale(8), JBUI.scale(8))
                g2.color = text
                val metrics = g2.fontMetrics
                val status = if (loading) "Loading Figma..." else "Missing image"
                val maxTextWidth = iconWidth - JBUI.scale(24)
                val titleText = ellipsize(title, metrics, maxTextWidth)
                val statusText = ellipsize(status, metrics, maxTextWidth)
                val centerY = y + iconHeight / 2
                g2.drawString(titleText, x + (iconWidth - metrics.stringWidth(titleText)) / 2, centerY - JBUI.scale(6))
                g2.drawString(statusText, x + (iconWidth - metrics.stringWidth(statusText)) / 2, centerY + metrics.height)
            } finally {
                g2.dispose()
            }
        }

        private fun ellipsize(value: String, metrics: java.awt.FontMetrics, maxWidth: Int): String {
            if (metrics.stringWidth(value) <= maxWidth) return value
            val ellipsis = "..."
            var end = value.length
            while (end > 0 && metrics.stringWidth(value.substring(0, end) + ellipsis) > maxWidth) {
                end--
            }
            return value.substring(0, end) + ellipsis
        }
    }
}
