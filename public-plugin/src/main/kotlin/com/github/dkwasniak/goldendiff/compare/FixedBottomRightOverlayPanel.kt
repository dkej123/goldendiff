package com.github.dkwasniak.goldendiff.compare

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport

/**
 * Keeps floating controls fixed above scrollable content.
 *
 * Swing normally assumes sibling components do not overlap and uses viewport blitting while
 * scrolling. Both assumptions are invalid for an overlay and can move or duplicate its pixels.
 */
internal class FixedBottomRightOverlayPanel(
    private val content: JComponent,
    private val overlay: JComponent,
    private val margin: Int = 12,
) : JPanel(null) {
    init {
        add(content)
        add(overlay)
        setComponentZOrder(overlay, 0)
        isOpaque = false
        content.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) = positionOverlay()
                override fun componentMoved(event: ComponentEvent) = positionOverlay()
            },
        )
        useSimpleScrollMode(content)
    }

    override fun isOptimizedDrawingEnabled(): Boolean = false

    override fun doLayout() {
        content.setBounds(0, 0, width, height)
        positionOverlay()
    }

    private fun positionOverlay() {
        val overlaySize = overlay.preferredSize
        val scaledMargin = JBUI.scale(margin)
        overlay.setBounds(
            (width - overlaySize.width - scaledMargin).coerceAtLeast(scaledMargin),
            (height - overlaySize.height - scaledMargin).coerceAtLeast(scaledMargin),
            overlaySize.width,
            overlaySize.height,
        )
        overlay.repaint()
    }

    private fun useSimpleScrollMode(component: Component) {
        if (component is JScrollPane) {
            component.viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
        }
        if (component is Container) {
            for (index in 0 until component.componentCount) {
                useSimpleScrollMode(component.getComponent(index))
            }
        }
    }

    override fun getPreferredSize(): Dimension = content.preferredSize

    override fun getMinimumSize(): Dimension = content.minimumSize
}
