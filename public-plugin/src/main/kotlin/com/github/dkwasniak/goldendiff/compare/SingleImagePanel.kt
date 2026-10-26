package com.github.dkwasniak.goldendiff.compare

import com.github.dkwasniak.goldendiff.toolwindow.GoldenDiffColors
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JPanel

/** Shows a single image (used when there is nothing to compare, e.g. no change vs HEAD). */
class SingleImagePanel : JPanel(BorderLayout()) {

    private val canvas = Canvas()

    init {
        background = GoldenDiffColors.background
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun setImage(image: BufferedImage?) = canvas.setImage(image)

    fun setZoom(zoom: Double) = canvas.setZoom(zoom)

    fun effectiveZoom(): Double = canvas.effectiveZoom()

    private class Canvas : ZoomablePanel() {
        private var image: BufferedImage? = null

        fun setImage(image: BufferedImage?) {
            this.image = image
            revalidate()
            repaint()
        }

        override fun contentSize(): Dimension =
            image?.let { Dimension(it.width, it.height) } ?: Dimension(0, 0)

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = image ?: return
            val g2 = g as Graphics2D
            val inset = JBUI.scale(14)
            val area = Rectangle(inset, inset, width - inset * 2, height - inset * 2)
            val scale = ImagePainting.scaleForBounding(
                zoom,
                img.width,
                img.height,
                area.width,
                area.height,
            )
            val rect = ImagePainting.centeredRect(img.width, img.height, scale, area)
            ImagePainting.paintCheckerboard(g2, rect)
            ImagePainting.drawImage(g2, img, rect)
        }
    }
}
