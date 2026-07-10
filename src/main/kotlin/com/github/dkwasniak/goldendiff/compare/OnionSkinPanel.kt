package com.github.dkwasniak.goldendiff.compare

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JSlider

/**
 * "Onion skin": the new selected source image is overlaid on the old (HEAD) one; a slider controls
 * the opacity so you can blend between them.
 */
class OnionSkinPanel : JPanel(BorderLayout()) {

    private val canvas = Canvas()
    private val slider = JSlider(0, 100, 50).apply {
        border = JBUI.Borders.empty(4)
        addChangeListener { canvas.alpha = value / 100f }
    }

    init {
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(slider, BorderLayout.SOUTH)
    }

    fun setImages(old: BufferedImage?, new: BufferedImage?) = canvas.setImages(old, new)

    fun setLabels(oldLabel: String, newLabel: String) = canvas.setLabels(oldLabel, newLabel)

    fun setZoom(zoom: Double) = canvas.setZoom(zoom)

    private class Canvas : ZoomablePanel() {
        private var oldImage: BufferedImage? = null
        private var newImage: BufferedImage? = null
        private var oldLabel = "HEAD"
        private var newLabel = "Working copy"
        private val titleHeight get() = JBUI.scale(28)
        var alpha: Float = 0.5f
            set(value) {
                field = value
                repaint()
            }

        fun setImages(old: BufferedImage?, new: BufferedImage?) {
            oldImage = old
            newImage = new
            revalidate()
            repaint()
        }

        fun setLabels(oldLabel: String, newLabel: String) {
            this.oldLabel = oldLabel
            this.newLabel = newLabel
            repaint()
        }

        override fun contentSize(): Dimension {
            val (w, h) = ImagePainting.commonSize(oldImage, newImage)
            return if (w <= 0 || h <= 0) Dimension(0, 0) else Dimension(w, h + titleHeight)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                val (w, h) = ImagePainting.commonSize(oldImage, newImage)
                val rect = ImagePainting.renderRect(zoom, w, h, width, height - titleHeight).apply {
                    translate(0, titleHeight)
                }
                if (rect.width <= 0) return
                drawLegend(g2, rect)
                ImagePainting.paintCheckerboard(g2, rect)
                oldImage?.let { ImagePainting.drawImage(g2, it, rect) }
                newImage?.let {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                    ImagePainting.drawImage(g2, it, rect)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun drawLegend(g2: Graphics2D, rect: Rectangle) {
            val text = "$oldLabel base / $newLabel overlay ${(alpha * 100).toInt()}%"
            val metrics = g2.fontMetrics
            val x = rect.x + JBUI.scale(8)
            val titleText = ImagePainting.ellipsize(text, metrics, rect.width - JBUI.scale(16))
            g2.color = JBColor.foreground()
            if (titleText.isNotEmpty()) {
                g2.drawString(titleText, x, metrics.ascent + JBUI.scale(6))
            }
        }
    }
}
