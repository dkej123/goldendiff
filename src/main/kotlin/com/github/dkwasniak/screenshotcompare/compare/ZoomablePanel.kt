package com.github.dkwasniak.screenshotcompare.compare

import com.intellij.ui.JBColor
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.SwingUtilities

/**
 * Base for the drawing canvases. Handles the zoom model and reports a preferred size so that a
 * surrounding scroll pane shows scrollbars when zoomed in, and none when fitting.
 */
abstract class ZoomablePanel : JPanel() {

    protected var zoom: Double = ImagePainting.FIT
        private set

    init {
        isOpaque = true
        background = JBColor.background()
    }

    fun setZoom(value: Double) {
        zoom = value
        revalidate()
        repaint()
    }

    /** Size of the content at 100% zoom. */
    protected abstract fun contentSize(): Dimension

    override fun getPreferredSize(): Dimension {
        val content = contentSize()
        if (zoom == ImagePainting.FIT) {
            val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport
            return viewport?.extentSize ?: content
        }
        return Dimension(
            (content.width * zoom).toInt().coerceAtLeast(1),
            (content.height * zoom).toInt().coerceAtLeast(1),
        )
    }
}
