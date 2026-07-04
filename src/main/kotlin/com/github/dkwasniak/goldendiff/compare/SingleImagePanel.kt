package com.github.dkwasniak.goldendiff.compare

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.swing.JPanel

/** Shows a single image (used when there is nothing to compare, e.g. no change vs HEAD). */
class SingleImagePanel : JPanel(BorderLayout()) {

    private val canvas = Canvas()

    init {
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun setImage(image: BufferedImage?) = canvas.setImage(image)

    fun setZoom(zoom: Double) = canvas.setZoom(zoom)

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
            val rect = ImagePainting.renderRect(zoom, img.width, img.height, width, height)
            ImagePainting.paintCheckerboard(g2, rect)
            ImagePainting.drawImage(g2, img, rect)
        }
    }
}
