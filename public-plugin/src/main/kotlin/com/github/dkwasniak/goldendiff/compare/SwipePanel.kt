package com.github.dkwasniak.goldendiff.compare

import com.github.dkwasniak.goldendiff.toolwindow.GoldenDiffColors
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JSlider

/**
 * "Swipe": both images are drawn into the same rectangle; a draggable vertical divider reveals the
 * old (HEAD) version on the left and the new selected source version on the right.
 */
class SwipePanel : JPanel(BorderLayout()) {

    private var syncing = false
    private val slider: JSlider = JSlider(0, 100, 50).apply {
        isOpaque = false
        getAccessibleContext()?.accessibleName = "Swipe position"
        border = JBUI.Borders.empty(2, 12, 6, 12)
        addChangeListener {
            if (!syncing) canvas.fraction = value / 100.0
        }
    }
    private val controls get() = slider

    private val canvas: Canvas = Canvas { fraction ->
        syncing = true
        try {
            slider.value = (fraction * 100).toInt()
        } finally {
            syncing = false
        }
    }

    init {
        background = GoldenDiffColors.background
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(controls, BorderLayout.SOUTH)
    }

    fun setImages(old: BufferedImage?, new: BufferedImage?) = canvas.setImages(old, new)

    fun setLabels(oldLabel: String, newLabel: String) = canvas.setLabels(oldLabel, newLabel)

    fun setZoom(zoom: Double) = canvas.setZoom(zoom)

    /** Height of the slider row below the canvas, kept clear of floating controls. */
    fun controlsHeight(): Int = controls.height.takeIf { it > 0 } ?: controls.preferredSize.height

    fun effectiveZoom(): Double = canvas.effectiveZoom()

    private class Canvas(private val onFractionDragged: (Double) -> Unit) : ZoomablePanel() {
        private var oldImage: BufferedImage? = null
        private var newImage: BufferedImage? = null
        private var oldLabel = "HEAD"
        private var newLabel = "Working copy"

        private val titleHeight get() = JBUI.scale(8)

        /** Divider position as a fraction (0..1) of the rendered image rectangle. */
        var fraction = 0.5
            set(value) {
                field = value.coerceIn(0.0, 1.0)
                repaint()
            }
        private var draggingDivider = false

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    draggingDivider = reservesPrimaryDrag(e.point)
                    if (draggingDivider) updateFraction(e)
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (draggingDivider) updateFraction(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    draggingDivider = false
                }
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
            val (w, h) = ImagePainting.boundingSize(oldImage, newImage)
            return if (w <= 0 || h <= 0) Dimension(0, 0) else Dimension(w, h + titleHeight)
        }

        private fun imageArea(): Rectangle = Rectangle(0, titleHeight, width, height - titleHeight)

        private fun scale(): Double {
            val (w, h) = ImagePainting.boundingSize(oldImage, newImage)
            val area = imageArea()
            return ImagePainting.scaleForBounding(zoom, w, h, area.width, area.height)
        }

        /** Centered bounding reference rect used for the divider, checkerboard and labels. */
        private fun currentRect(): Rectangle {
            val (w, h) = ImagePainting.boundingSize(oldImage, newImage)
            return ImagePainting.centeredRect(w, h, scale(), imageArea())
        }

        override fun reservesPrimaryDrag(point: Point): Boolean {
            if (!hasScrollableContent()) return true
            val rect = currentRect()
            val dividerX = rect.x + (rect.width * fraction).toInt()
            return kotlin.math.abs(point.x - dividerX) <= JBUI.scale(8)
        }

        private fun updateFraction(e: MouseEvent) {
            val rect = currentRect()
            if (rect.width <= 0) return
            fraction = (e.x - rect.x).toDouble() / rect.width
            onFractionDragged(fraction)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                val area = imageArea()
                val scale = scale()
                val rect = currentRect()
                if (rect.width <= 0) return
                ImagePainting.paintCheckerboard(g2, rect)

                newImage?.let {
                    ImagePainting.drawImage(g2, it, ImagePainting.centeredRect(it.width, it.height, scale, area))
                }

                val dividerX = rect.x + (rect.width * fraction).toInt()
                oldImage?.let {
                    val clip = g2.create() as Graphics2D
                    try {
                        clip.clipRect(rect.x, rect.y, dividerX - rect.x, rect.height)
                        ImagePainting.drawImage(clip, it, ImagePainting.centeredRect(it.width, it.height, scale, area))
                    } finally {
                        clip.dispose()
                    }
                }

                g2.color = GoldenDiffColors.accent
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                g2.drawLine(dividerX, rect.y, dividerX, rect.y + rect.height)
                val knob = JBUI.scale(13)
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.fillOval(dividerX - knob / 2, rect.y + rect.height / 2 - knob / 2, knob, knob)
                drawLabels(g2, rect)
            } finally {
                g2.dispose()
            }
        }

        private fun drawLabels(g2: Graphics2D, rect: Rectangle) {
            g2.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(8.5f))
            val metrics = g2.fontMetrics
            val inset = JBUI.scale(6)
            val gap = JBUI.scale(12)
            val maxLabelWidth = ((rect.width - inset * 2 - gap) / 2).coerceAtLeast(0)
            val oldText = ImagePainting.ellipsize(oldLabel.uppercase(), metrics, maxLabelWidth)
            val newText = ImagePainting.ellipsize(newLabel.uppercase(), metrics, maxLabelWidth)
            val baseline = rect.y + metrics.ascent + JBUI.scale(5)
            g2.color = GoldenDiffColors.dim
            if (oldText.isNotEmpty()) {
                g2.drawString(oldText, rect.x + inset, baseline)
            }
            if (newText.isNotEmpty()) {
                g2.drawString(newText, rect.x + rect.width - metrics.stringWidth(newText) - inset, baseline)
            }
        }
    }
}
