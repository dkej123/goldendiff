package com.github.dkwasniak.goldendiff.compare

import com.github.dkwasniak.goldendiff.toolwindow.GoldenDiffColors
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JPanel

/**
 * Pixel-diff heatmap: unchanged pixels are dimmed to grayscale context and changed pixels are
 * highlighted, so what moved between HEAD and the selected source is visible at a glance.
 */
class DiffPanel : JPanel(BorderLayout()) {

    private val canvas = Canvas()

    init {
        background = GoldenDiffColors.background
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun setImages(old: BufferedImage?, new: BufferedImage?) = canvas.setImages(old, new)

    fun setZoom(zoom: Double) = canvas.setZoom(zoom)

    fun effectiveZoom(): Double = canvas.effectiveZoom()

    private class Canvas : ZoomablePanel() {
        private var diff: PixelDiff.Result? = null
        private var rendered: BufferedImage? = null
        private val titleHeight get() = JBUI.scale(28)

        fun setImages(old: BufferedImage?, new: BufferedImage?) {
            val result = PixelDiff.compute(old?.toArgbImage(), new?.toArgbImage())
            diff = result
            // PixelDiff works on toolkit-neutral pixel arrays, so convert once here rather than on
            // every paint — this canvas repaints on scroll, zoom and resize.
            rendered = result?.image?.toBufferedImage()
            revalidate()
            repaint()
        }

        override fun contentSize(): Dimension {
            val image = rendered ?: return Dimension(0, 0)
            return Dimension(image.width, image.height + titleHeight)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val result = diff ?: return
            val image = rendered ?: return
            val g2 = g.create() as Graphics2D
            try {
                val area = Rectangle(0, titleHeight, width, height - titleHeight)
                val scale = ImagePainting.scaleForBounding(zoom, image.width, image.height, area.width, area.height)
                val rect = ImagePainting.centeredRect(image.width, image.height, scale, area)
                if (rect.width <= 0) return
                drawLegend(g2, rect, result)
                ImagePainting.paintCheckerboard(g2, rect)
                ImagePainting.drawImage(g2, image, rect)
            } finally {
                g2.dispose()
            }
        }

        private fun drawLegend(g2: Graphics2D, rect: Rectangle, result: PixelDiff.Result) {
            val changed = result.changedRatio > 0.0
            val text = if (changed) "%.2f%% pixels differ".format(result.changedRatio * 100) else "no differences"
            g2.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(9.5f))
            val metrics = g2.fontMetrics
            val padX = JBUI.scale(5)
            val chipWidth = metrics.stringWidth(text) + padX * 2
            val chipHeight = metrics.height + JBUI.scale(4)
            val x = rect.x + (rect.width - chipWidth) / 2
            val y = (titleHeight - chipHeight) / 2
            g2.color = GoldenDiffColors.header
            g2.fillRoundRect(x, y, chipWidth, chipHeight, JBUI.scale(6), JBUI.scale(6))
            g2.color = if (changed) GoldenDiffColors.failed else GoldenDiffColors.faint
            g2.drawString(text, x + padX, y + JBUI.scale(2) + metrics.ascent)
        }
    }
}
