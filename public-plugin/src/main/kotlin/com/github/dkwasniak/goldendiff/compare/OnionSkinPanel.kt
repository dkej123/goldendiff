package com.github.dkwasniak.goldendiff.compare

import com.github.dkwasniak.goldendiff.toolwindow.GoldenDiffColors
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
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
        isOpaque = false
        getAccessibleContext()?.accessibleName = "Overlay opacity"
        addChangeListener { canvas.alpha = value / 100f }
    }
    private val oldEnd = sliderEnd()
    private val newEnd = sliderEnd()
    private val controls = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 12, 6, 12)
        add(oldEnd, BorderLayout.WEST)
        add(slider, BorderLayout.CENTER)
        add(newEnd, BorderLayout.EAST)
    }

    init {
        background = GoldenDiffColors.background
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(controls, BorderLayout.SOUTH)
    }

    fun setImages(old: BufferedImage?, new: BufferedImage?) = canvas.setImages(old, new)

    fun setLabels(oldLabel: String, newLabel: String) {
        oldEnd.text = oldLabel.uppercase()
        newEnd.text = newLabel.uppercase()
        canvas.setLabels(oldLabel, newLabel)
    }

    fun setZoom(zoom: Double) = canvas.setZoom(zoom)

    /** Height of the slider row below the canvas, kept clear of floating controls. */
    fun controlsHeight(): Int = controls.height.takeIf { it > 0 } ?: controls.preferredSize.height

    fun effectiveZoom(): Double = canvas.effectiveZoom()

    private class Canvas : ZoomablePanel() {
        private var oldImage: BufferedImage? = null
        private var newImage: BufferedImage? = null
        private var oldLabel = "HEAD"
        private var newLabel = "Working copy"
        private val titleHeight get() = JBUI.scale(8)
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
            val (w, h) = ImagePainting.boundingSize(oldImage, newImage)
            return if (w <= 0 || h <= 0) Dimension(0, 0) else Dimension(w, h + titleHeight)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                val area = Rectangle(0, titleHeight, width, height - titleHeight)
                val (bw, bh) = ImagePainting.boundingSize(oldImage, newImage)
                val scale = ImagePainting.scaleForBounding(zoom, bw, bh, area.width, area.height)
                val rect = ImagePainting.centeredRect(bw, bh, scale, area)
                if (rect.width <= 0) return
                ImagePainting.paintCheckerboard(g2, rect)
                oldImage?.let { ImagePainting.drawImage(g2, it, ImagePainting.centeredRect(it.width, it.height, scale, area)) }
                newImage?.let {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                    ImagePainting.drawImage(g2, it, ImagePainting.centeredRect(it.width, it.height, scale, area))
                }
            } finally {
                g2.dispose()
            }
        }
    }
}

private fun sliderEnd(): JBLabel = JBLabel().apply {
    font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(9.5f))
    foreground = GoldenDiffColors.faint
}
