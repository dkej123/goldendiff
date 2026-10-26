package com.github.dkwasniak.goldendiff.toolwindow

import com.intellij.ui.RoundedLineBorder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Review tab content with the Files list floating over it: a scrim covers the content and the list
 * is anchored under [anchor] (the nav row) with 8px side insets, down to the bottom of the layer.
 * A click on the scrim calls [onDismiss]; the shell decides what is shown through [setOverlay].
 */
internal class FilesOverlayLayer(
    private val content: JComponent,
    private val anchor: JComponent,
    onDismiss: () -> Unit,
) : JPanel(null) {
    private val scrim = object : JPanel() {
        override fun paintComponent(graphics: Graphics) {
            graphics.color = GoldenDiffColors.scrim
            graphics.fillRect(0, 0, width, height)
        }
    }.apply {
        isOpaque = false
        isVisible = false
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = onDismiss()
        })
    }
    val host = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = GoldenDiffColors.panel
        border = RoundedLineBorder(GoldenDiffColors.borderStrong, JBUI.scale(8), 1)
        isVisible = false
    }

    init {
        isOpaque = false
        add(host)
        add(scrim)
        add(content)
        setComponentZOrder(host, 0)
        setComponentZOrder(scrim, 1)
        setComponentZOrder(content, 2)
    }

    fun setOverlay(visible: Boolean) {
        if (scrim.isVisible == visible && host.isVisible == visible) return
        scrim.isVisible = visible
        host.isVisible = visible
        revalidate()
        repaint()
    }

    override fun isOptimizedDrawingEnabled(): Boolean = false

    override fun doLayout() {
        content.setBounds(0, 0, width, height)
        scrim.setBounds(0, 0, width, height)
        val top = anchor.takeIf { it.isShowing }
            ?.let { javax.swing.SwingUtilities.convertPoint(it.parent, it.x, it.y + it.height, this).y }
            ?: 0
        val inset = JBUI.scale(8)
        host.setBounds(inset, top, (width - inset * 2).coerceAtLeast(0), (height - top - inset).coerceAtLeast(0))
    }

    override fun getPreferredSize(): Dimension = content.preferredSize

    override fun getMinimumSize(): Dimension = content.minimumSize
}
