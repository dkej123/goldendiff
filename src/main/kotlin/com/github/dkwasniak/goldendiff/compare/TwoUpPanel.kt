package com.github.dkwasniak.goldendiff.compare

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JPanel

/** "2-up": old (HEAD) and new selected source side by side. */
class TwoUpPanel : JPanel(BorderLayout()) {

    private val canvas = Canvas()

    init {
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun setImages(old: BufferedImage?, new: BufferedImage?) = canvas.setImages(old, new)

    fun setLabels(oldLabel: String, newLabel: String) = canvas.setLabels(oldLabel, newLabel)

    fun setZoom(zoom: Double) = canvas.setZoom(zoom)

    private class Canvas : ZoomablePanel() {
        private var oldImage: BufferedImage? = null
        private var newImage: BufferedImage? = null
        private var oldLabel = "HEAD"
        private var newLabel = "Working copy"

        private val titleHeight get() = JBUI.scale(20)

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
            if (w <= 0 || h <= 0) return Dimension(0, 0)
            return Dimension(w * 2, h + titleHeight)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            val half = width / 2
            drawSide(g2, oldImage, Rectangle(0, 0, half, height), oldLabel)
            drawSide(g2, newImage, Rectangle(half, 0, width - half, height), newLabel)
            g2.color = JBColor.border()
            g2.drawLine(half, 0, half, height)
        }

        private fun drawSide(g2: Graphics2D, image: BufferedImage?, area: Rectangle, title: String) {
            val inset = JBUI.scale(6)
            val metrics = g2.fontMetrics
            val titleText = ImagePainting.ellipsize(title, metrics, area.width - inset * 2)
            g2.color = foreground
            if (titleText.isNotEmpty()) {
                g2.drawString(titleText, area.x + inset, area.y + metrics.ascent + JBUI.scale(3))
            }
            val imageArea = Rectangle(area.x, area.y + titleHeight, area.width, area.height - titleHeight)
            if (image == null) {
                g2.color = JBColor.GRAY
                g2.drawString("(none)", area.x + inset, area.y + titleHeight + JBUI.scale(14))
                return
            }
            val rect = ImagePainting.renderRect(zoom, image.width, image.height, imageArea.width, imageArea.height)
            rect.translate(imageArea.x, imageArea.y)
            ImagePainting.paintCheckerboard(g2, rect)
            ImagePainting.drawImage(g2, image, rect)
        }
    }
}
