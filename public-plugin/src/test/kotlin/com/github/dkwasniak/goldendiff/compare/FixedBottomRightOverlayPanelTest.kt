package com.github.dkwasniak.goldendiff.compare

import java.awt.Dimension
import java.awt.Point
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FixedBottomRightOverlayPanelTest {
    @Test
    fun `scrolling content does not move the overlay`() {
        val scroll = JScrollPane(
            JPanel().apply { preferredSize = Dimension(400, 400) },
        )
        val overlay = JPanel().apply { preferredSize = Dimension(60, 24) }
        val panel = FixedBottomRightOverlayPanel(scroll, overlay, margin = 10)
        panel.setSize(240, 180)
        panel.doLayout()
        scroll.doLayout()
        val initialBounds = overlay.bounds

        scroll.viewport.viewPosition = Point(80, 120)

        assertEquals(initialBounds, overlay.bounds)
        assertEquals(JViewport.SIMPLE_SCROLL_MODE, scroll.viewport.scrollMode)
        assertFalse(panel.isOptimizedDrawingEnabled)
    }
}
