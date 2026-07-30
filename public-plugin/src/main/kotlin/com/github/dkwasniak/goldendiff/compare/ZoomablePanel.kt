package com.github.dkwasniak.goldendiff.compare

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    private var panStartOnScreen: Point? = null
    private var panStartPosition: Point? = null
    private var cursorBeforePan: Cursor? = null

    init {
        isOpaque = true
        background = JBColor.background()
        val panHandler = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (
                    !SwingUtilities.isLeftMouseButton(event) ||
                    !hasScrollableContent() ||
                    reservesPrimaryDrag(event.point)
                ) {
                    return
                }
                val viewport = viewport() ?: return
                panStartOnScreen = event.locationOnScreen
                panStartPosition = viewport.viewPosition
                cursorBeforePan = cursor
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }

            override fun mouseDragged(event: MouseEvent) {
                val startOnScreen = panStartOnScreen ?: return
                val startPosition = panStartPosition ?: return
                val viewport = viewport() ?: return
                val current = event.locationOnScreen
                val maxX = (width - viewport.extentSize.width).coerceAtLeast(0)
                val maxY = (height - viewport.extentSize.height).coerceAtLeast(0)
                viewport.viewPosition = Point(
                    (startPosition.x - (current.x - startOnScreen.x)).coerceIn(0, maxX),
                    (startPosition.y - (current.y - startOnScreen.y)).coerceIn(0, maxY),
                )
            }

            override fun mouseReleased(event: MouseEvent) {
                if (panStartOnScreen == null) return
                panStartOnScreen = null
                panStartPosition = null
                cursor = cursorBeforePan ?: Cursor.getDefaultCursor()
                cursorBeforePan = null
            }
        }
        addMouseListener(panHandler)
        addMouseMotionListener(panHandler)
    }

    fun setZoom(value: Double) {
        zoom = value
        revalidate()
        repaint()
    }

    fun effectiveZoom(): Double {
        if (zoom != ImagePainting.FIT) return zoom
        val content = contentSize()
        if (content.width <= 0 || content.height <= 0) return 1.0
        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport
            ?: return 1.0
        val extent = viewport.extentSize
        if (extent.width <= 0 || extent.height <= 0) return 1.0
        return minOf(
            extent.width.toDouble() / content.width,
            extent.height.toDouble() / content.height,
        ).coerceAtMost(1.0)
    }

    /** Size of the content at 100% zoom. */
    protected abstract fun contentSize(): Dimension

    /** Allows an interactive canvas (Swipe's divider) to claim a specific primary-button drag. */
    protected open fun reservesPrimaryDrag(point: Point): Boolean = false

    protected fun hasScrollableContent(): Boolean {
        val viewport = viewport() ?: return false
        return width > viewport.extentSize.width || height > viewport.extentSize.height
    }

    private fun viewport(): JViewport? =
        SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport

    override fun getPreferredSize(): Dimension {
        val content = contentSize()
        val viewport = viewport()
        if (zoom == ImagePainting.FIT) {
            return viewport?.extentSize ?: content
        }
        // Leave non-scaling breathing room around explicit zoom content. Besides centering images
        // that fit the viewport, this keeps the outer pixels reachable by the scrollbars at 200%+.
        val surround = JBUI.scale(56)
        return Dimension(
            ((content.width * zoom).toInt() + surround)
                .coerceAtLeast(viewport?.extentSize?.width ?: 1),
            ((content.height * zoom).toInt() + surround)
                .coerceAtLeast(viewport?.extentSize?.height ?: 1),
        )
    }
}
