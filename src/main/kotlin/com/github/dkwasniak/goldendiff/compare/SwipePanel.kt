package com.github.dkwasniak.goldendiff.compare

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel

/**
 * "Swipe": both images are drawn into the same rectangle; a draggable vertical divider reveals the
 * old (HEAD) version on the left and the new selected source version on the right.
 */
class SwipePanel : JPanel(BorderLayout()) {

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

        private val titleHeight get() = JBUI.scale(28)

        /** Divider position as a fraction (0..1) of the rendered image rectangle. */
        private var fraction = 0.5

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = updateFraction(e)
                override fun mouseDragged(e: MouseEvent) = updateFraction(e)
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
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

        private fun currentRect(): Rectangle {
            val (w, h) = ImagePainting.commonSize(oldImage, newImage)
            return ImagePainting.renderRect(zoom, w, h, width, height - titleHeight).apply {
                translate(0, titleHeight)
            }
        }

        private fun updateFraction(e: MouseEvent) {
            val rect = currentRect()
            if (rect.width <= 0) return
            fraction = ((e.x - rect.x).toDouble() / rect.width).coerceIn(0.0, 1.0)
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                val rect = currentRect()
                if (rect.width <= 0) return
                ImagePainting.paintCheckerboard(g2, rect)

                newImage?.let { ImagePainting.drawImage(g2, it, rect) }

                val dividerX = rect.x + (rect.width * fraction).toInt()
                oldImage?.let {
                    val clip = g2.create() as Graphics2D
                    try {
                        clip.clipRect(rect.x, rect.y, dividerX - rect.x, rect.height)
                        ImagePainting.drawImage(clip, it, rect)
                    } finally {
                        clip.dispose()
                    }
                }

                g2.color = JBColor.namedColor("Component.focusColor", JBColor.BLUE)
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                g2.drawLine(dividerX, rect.y, dividerX, rect.y + rect.height)
                drawLabels(g2, rect)
            } finally {
                g2.dispose()
            }
        }

        private fun drawLabels(g2: Graphics2D, rect: Rectangle) {
            val metrics = g2.fontMetrics
            val inset = JBUI.scale(6)
            val gap = JBUI.scale(12)
            val maxLabelWidth = ((rect.width - inset * 2 - gap) / 2).coerceAtLeast(0)
            val oldText = ImagePainting.ellipsize(oldLabel, metrics, maxLabelWidth)
            val newText = ImagePainting.ellipsize(newLabel, metrics, maxLabelWidth)
            g2.color = JBColor.foreground()
            if (oldText.isNotEmpty()) {
                g2.drawString(oldText, rect.x + inset, metrics.ascent + inset)
            }
            if (newText.isNotEmpty()) {
                g2.drawString(newText, rect.x + rect.width - metrics.stringWidth(newText) - inset, metrics.ascent + inset)
            }
        }
    }
}
