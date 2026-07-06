package com.github.dkwasniak.goldendiff.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/** Renders each golden file as a left-aligned thumbnail. The full filename is available as a tooltip. */
class GoldenCellRenderer : ListCellRenderer<File> {

    private data class Key(val path: String, val lastModified: Long)
    private data class IconKey(val key: Key, val targetWidth: Int)

    private val imageCache = HashMap<Key, BufferedImage?>()
    private val iconCache = HashMap<IconKey, Icon?>()

    fun clearScaledCache() {
        iconCache.clear()
    }

    fun cellHeight(files: List<File>, listWidth: Int): Int {
        val targetWidth = targetWidth(listWidth)
        val padding = JBUI.scale(6)
        val maxImageHeight = files.maxOfOrNull { file ->
            imageFor(file)?.let { image -> thumbnailSize(image, targetWidth).height } ?: JBUI.scale(56)
        } ?: JBUI.scale(56)
        return maxImageHeight + padding * 2
    }

    override fun getListCellRendererComponent(
        list: JList<out File>,
        value: File,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val targetWidth = targetWidth(list.width)
        val icon = iconFor(value, targetWidth)
        val padding = JBUI.scale(6)
        val panel = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(padding)
            isOpaque = true
            toolTipText = value.name
            preferredSize = Dimension(
                targetWidth + padding * 2,
                (icon?.iconHeight ?: JBUI.scale(56)) + padding * 2,
            )
        }
        val thumbnail = JBLabel().apply {
            this.icon = icon
            horizontalAlignment = SwingConstants.LEFT
            verticalAlignment = SwingConstants.TOP
            toolTipText = value.name
        }
        if (isSelected) {
            panel.background = list.selectionBackground
        } else {
            panel.background = list.background
        }
        panel.add(thumbnail, BorderLayout.WEST)
        return panel
    }

    private fun targetWidth(listWidth: Int): Int =
        (listWidth - JBUI.scale(24)).coerceAtLeast(JBUI.scale(56))

    private fun iconFor(file: File, targetWidth: Int): Icon? {
        val key = Key(file.path, file.lastModified())
        val image = imageFor(file) ?: return null
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
}
