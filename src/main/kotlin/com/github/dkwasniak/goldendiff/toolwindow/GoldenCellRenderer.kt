package com.github.dkwasniak.goldendiff.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/** Renders each golden file as a left-aligned thumbnail. The full filename is available as a tooltip. */
class GoldenCellRenderer : ListCellRenderer<File> {

    private data class Key(val path: String, val lastModified: Long)
    private data class ScaledKey(val key: Key, val targetWidth: Int)

    private val imageCache = HashMap<Key, BufferedImage?>()
    private val iconCache = HashMap<ScaledKey, Icon?>()

    fun clearScaledCache() {
        iconCache.clear()
    }

    fun cellHeight(files: List<File>, listWidth: Int): Int {
        val targetWidth = targetWidth(listWidth)
        val padding = JBUI.scale(6)
        val maxImageHeight = files.maxOfOrNull { file ->
            imageFor(file)?.let { image ->
                (image.height * (targetWidth.toDouble() / image.width)).toInt().coerceAtLeast(1)
            } ?: JBUI.scale(56)
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
        val scaledKey = ScaledKey(key, targetWidth)
        return iconCache.getOrPut(scaledKey) {
            val scale = targetWidth.toDouble() / image.width
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            ImageIcon(scaleImage(image, w, h))
        }
    }

    private fun imageFor(file: File): BufferedImage? {
        val key = Key(file.path, file.lastModified())
        return imageCache.getOrPut(key) { runCatching { ImageIO.read(file) }.getOrNull() }
    }

    private fun scaleImage(image: BufferedImage, width: Int, height: Int): Image {
        val scaled = if (width < image.width || height < image.height) {
            downscaleInSteps(image, width, height)
        } else {
            scaleOnce(image, width, height)
        }
        return if (width < image.width || height < image.height) sharpen(scaled) else scaled
    }

    private fun downscaleInSteps(source: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        var current = source
        var width = source.width
        var height = source.height

        while (width / 2 >= targetWidth && height / 2 >= targetHeight) {
            width /= 2
            height /= 2
            current = scaleOnce(current, width, height)
        }
        return if (width == targetWidth && height == targetHeight) current else scaleOnce(current, targetWidth, targetHeight)
    }

    private fun scaleOnce(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return scaled
    }

    private fun sharpen(image: BufferedImage): BufferedImage {
        val kernel = Kernel(
            3,
            3,
            floatArrayOf(
                0f, -0.12f, 0f,
                -0.12f, 1.48f, -0.12f,
                0f, -0.12f, 0f,
            ),
        )
        return ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null).filter(image, null)
    }
}
