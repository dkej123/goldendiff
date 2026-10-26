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

/** "2-up": old (HEAD) and new selected source side by side. */
class TwoUpPanel : JPanel(BorderLayout()) {

    private val canvas = Canvas()

    init {
        background = GoldenDiffColors.background
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun setImages(old: BufferedImage?, new: BufferedImage?) = canvas.setImages(old, new)

    fun setLabels(oldLabel: String, newLabel: String) = canvas.setLabels(oldLabel, newLabel)

    fun setZoom(zoom: Double) = canvas.setZoom(zoom)

    fun effectiveZoom(): Double = canvas.effectiveZoom()

    private class Canvas : ZoomablePanel() {
        private var oldImage: BufferedImage? = null
        private var newImage: BufferedImage? = null
        private var oldLabel = "HEAD"
        private var newLabel = "Working copy"

        private val titleHeight get() = JBUI.scale(22)

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
            val (w, h) = ImagePainting.boundingSize(oldImage, newImage)
            if (w <= 0 || h <= 0) return Dimension(0, 0)
            return Dimension(w * 2, h + titleHeight)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            val half = width / 2
            val (commonWidth, commonHeight) = ImagePainting.boundingSize(oldImage, newImage)
            drawSide(g2, oldImage, Rectangle(0, 0, half, height), oldLabel, commonWidth, commonHeight)
            drawSide(g2, newImage, Rectangle(half, 0, width - half, height), newLabel, commonWidth, commonHeight)
        }

        private fun drawSide(
            g2: Graphics2D,
            image: BufferedImage?,
            area: Rectangle,
            title: String,
            commonWidth: Int,
            commonHeight: Int,
        ) {
            val captionFont = g2.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(9.5f).toFloat())
            g2.font = captionFont
            val metrics = g2.fontMetrics
            val titleText = ImagePainting.ellipsize(title.uppercase(), metrics, area.width - JBUI.scale(12))
            g2.color = GoldenDiffColors.faint
            if (titleText.isNotEmpty()) {
                g2.drawString(
                    titleText,
                    area.x + (area.width - metrics.stringWidth(titleText)) / 2,
                    area.y + metrics.ascent + JBUI.scale(4),
                )
            }
            val inset = JBUI.scale(10)
            val imageArea = Rectangle(
                area.x + inset,
                area.y + titleHeight + JBUI.scale(2),
                area.width - inset * 2,
                area.height - titleHeight - inset - JBUI.scale(2),
            )
            if (image == null) {
                g2.color = GoldenDiffColors.faint
                val missing = "missing in $title"
                g2.drawString(missing, area.x + (area.width - metrics.stringWidth(missing)) / 2, imageArea.y + JBUI.scale(16))
                return
            }
            // Scale from the bounding box so both sides share one scale, then center each image.
            val scale = ImagePainting.scaleForBounding(zoom, commonWidth, commonHeight, imageArea.width, imageArea.height)
            val rect = ImagePainting.centeredRect(image.width, image.height, scale, imageArea)
            ImagePainting.paintCheckerboard(g2, rect)
            ImagePainting.drawImage(g2, image, rect)
            g2.color = GoldenDiffColors.border
            g2.drawRoundRect(rect.x - 1, rect.y - 1, rect.width + 1, rect.height + 1, JBUI.scale(6), JBUI.scale(6))
        }
    }
}
