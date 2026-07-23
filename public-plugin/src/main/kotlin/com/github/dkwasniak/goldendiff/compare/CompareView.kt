package com.github.dkwasniak.goldendiff.compare

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.JToggleButton
import kotlin.math.ceil

/** Hosts the three comparison modes plus a single-image view, the mode switcher and a zoom control. */
class CompareView(
    private val onModeSelected: (String) -> Unit = {},
    private val onZoomSelected: (String, String) -> Unit = { _, _ -> },
    private val onCopyPath: () -> Unit = {},
) : JPanel(BorderLayout()) {

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    private val twoUp = TwoUpPanel()
    private val swipe = SwipePanel()
    private val onion = OnionSkinPanel()
    private val diff = DiffPanel()
    private val single = SingleImagePanel()

    private val modeButtons = mutableListOf<JToggleButton>()
    private var titleText: String? = null
    // A JTextArea (not a JLabel) because file names are single long tokens without spaces, which the
    // Swing HTML/CSS renderer refuses to break; a char-wrapping text area wraps them reliably.
    private val titleArea = WrapLabel()
    private val copyTitleButton = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Copy file name"
        isFocusable = false
        margin = JBUI.insets(2)
        addActionListener {
            titleText?.let {
                onCopyPath()
                CopyPasteManager.getInstance().setContents(StringSelection(it))
                showCopiedBalloon(this)
            }
        }
    }
    private val titleBar = JPanel(BorderLayout()).apply {
        add(titleArea, BorderLayout.CENTER)
        // Keep the button pinned to the top so it stays put when the file name wraps to several lines.
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(copyTitleButton, BorderLayout.NORTH)
            },
            BorderLayout.EAST,
        )
        isVisible = false
    }
    private val status = JBLabel().apply { border = JBUI.Borders.empty(4) }
    private var retryAction: (() -> Unit)? = null
    private val retryButton = JButton("Retry").apply {
        border = JBUI.Borders.empty(2, 8)
        isVisible = false
        addActionListener { retryAction?.invoke() }
    }
    private val statusBar = JPanel(BorderLayout()).apply {
        add(status, BorderLayout.CENTER)
        add(retryButton, BorderLayout.EAST)
    }
    private val zoomLabel = JBLabel().apply {
        border = JBUI.Borders.empty(6, 0)
        horizontalAlignment = SwingConstants.CENTER
        alignmentX = CENTER_ALIGNMENT
        preferredSize = Dimension(JBUI.scale(40), JBUI.scale(28))
        minimumSize = preferredSize
        maximumSize = preferredSize
    }
    private val zoomOutButton = createZoomButton("-", ZoomIcon(false)).apply {
        toolTipText = "Zoom out"
        addActionListener { changeZoom(-1) }
    }
    private val zoomInButton = createZoomButton("+", ZoomIcon(true)).apply {
        toolTipText = "Zoom in"
        addActionListener { changeZoom(1) }
    }
    private val zoomOverlay = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(2)
        isOpaque = false
        add(zoomInButton)
        add(zoomOutButton)
        add(zoomLabel)
    }

    private var selectedMode = MODE_TWO_UP
    private var currentZoom = ImagePainting.FIT
    private var pendingZoomAction = "zoom_in"
    private val zoomTelemetryTimer = Timer(500) {
        val zoom = when {
            currentZoom == ImagePainting.FIT -> "fit"
            currentZoom < 1.0 -> "lt_100"
            currentZoom == 1.0 -> "equal_100"
            else -> "gt_100"
        }
        onZoomSelected(zoom, pendingZoomAction)
    }.apply { isRepeats = false }

    init {
        cards.add(twoUp, MODE_TWO_UP)
        cards.add(swipe, MODE_SWIPE)
        cards.add(onion, MODE_ONION)
        cards.add(diff, MODE_DIFF)
        cards.add(single, MODE_SINGLE)

        add(
            JPanel(BorderLayout()).apply {
                add(titleBar, BorderLayout.NORTH)
                add(buildToolbar(), BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        add(BottomRightOverlayPanel(cards, zoomOverlay), BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)

        cardLayout.show(cards, MODE_SINGLE)
        setModeButtonsVisible(false)
        updateZoomControls()
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = updateZoomControls()
            },
        )
    }

    /** Sets the file name shown as a header at the top of the preview; hidden when null/blank. */
    fun setTitle(text: String?) {
        titleText = text?.takeIf { it.isNotBlank() }
        titleArea.text = titleText.orEmpty()
        titleArea.toolTipText = titleText
        titleBar.isVisible = titleText != null
        titleBar.revalidate()
    }

    private fun showCopiedBalloon(target: JComponent) {
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder("Copied to clipboard", MessageType.INFO, null)
            .setFadeoutTime(1500)
            .createBalloon()
            .show(RelativePoint.getCenterOf(target), Balloon.Position.above)
    }

    /** Read-only, transparent, char-wrapping text area that behaves like a multi-line label. */
    private class WrapLabel : JTextArea() {
        init {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            // Word wrapping would not break a space-less file name; character wrapping does.
            wrapStyleWord = false
            border = JBUI.Borders.empty(4, 6, 2, 0)
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = UIUtil.getLabelForeground()
            // BorderLayout asks for the preferred height before the real width is known; once the width
            // is applied a revalidate makes the parent re-measure the now-correct wrapped height.
            addComponentListener(
                object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) = revalidate()
                },
            )
        }

        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize()
            val currentWidth = width
            if (currentWidth <= 0 || document.length == 0) return base
            return try {
                val end = modelToView2D(document.length)
                Dimension(currentWidth, ceil(end.maxY).toInt() + insets.bottom)
            } catch (e: Exception) {
                base
            }
        }
    }

    fun showComparison(
        old: BufferedImage?,
        new: BufferedImage?,
        statusText: String,
        oldLabel: String = "HEAD",
        newLabel: String = "Working copy",
    ) {
        twoUp.setImages(old, new)
        swipe.setImages(old, new)
        onion.setImages(old, new)
        diff.setImages(old, new)
        twoUp.setLabels(oldLabel, newLabel)
        swipe.setLabels(oldLabel, newLabel)
        onion.setLabels(oldLabel, newLabel)
        applyZoom(currentZoom)
        status.text = statusText
        hideRetry()
        setModeButtonsVisible(true)
        cardLayout.show(cards, selectedMode)
        updateZoomControls()
    }

    fun showSingle(image: BufferedImage?, statusText: String) {
        single.setImage(image)
        single.setZoom(currentZoom)
        status.text = statusText
        hideRetry()
        setModeButtonsVisible(false)
        cardLayout.show(cards, MODE_SINGLE)
        updateZoomControls()
    }

    /** Show an error message with a retry button — used when a reference could not be loaded. */
    fun showRetry(statusText: String, retryLabel: String = "Retry", action: () -> Unit) {
        single.setImage(null)
        single.setZoom(currentZoom)
        status.text = statusText
        retryAction = action
        retryButton.text = retryLabel
        retryButton.isVisible = true
        setModeButtonsVisible(false)
        cardLayout.show(cards, MODE_SINGLE)
        updateZoomControls()
    }

    private fun hideRetry() {
        retryButton.isVisible = false
        retryAction = null
    }

    private fun applyZoom(zoom: Double) {
        twoUp.setZoom(zoom)
        swipe.setZoom(zoom)
        onion.setZoom(zoom)
        diff.setZoom(zoom)
        single.setZoom(zoom)
        updateZoomControls()
    }

    private fun buildToolbar(): JPanel {
        val group = ButtonGroup()
        val toolbar = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))

        fun modeButton(title: String, mode: String): JToggleButton =
            JToggleButton(title).apply {
                isSelected = mode == selectedMode
                addActionListener {
                    selectedMode = mode
                    cardLayout.show(cards, mode)
                    updateZoomControls()
                    onModeSelected(
                        when (mode) {
                            MODE_TWO_UP -> "side_by_side"
                            MODE_SWIPE -> "swipe"
                            MODE_ONION -> "onion"
                            else -> "diff"
                        },
                    )
                }
                group.add(this)
                modeButtons.add(this)
                toolbar.add(this)
            }

        modeButton("Side by side", MODE_TWO_UP)
        modeButton("Swipe", MODE_SWIPE)
        modeButton("Onion skin", MODE_ONION)
        modeButton("Diff", MODE_DIFF)
        return toolbar
    }

    private fun setModeButtonsVisible(visible: Boolean) {
        modeButtons.forEach { it.isVisible = visible }
    }

    private fun changeZoom(direction: Int) {
        val effective = effectiveZoom()
        currentZoom = if (direction > 0) {
            ZOOM_STEPS.firstOrNull { it > effective + 0.001 } ?: ZOOM_STEPS.last()
        } else {
            ZOOM_STEPS.lastOrNull { it < effective - 0.001 } ?: ZOOM_STEPS.first()
        }
        pendingZoomAction = if (direction > 0) "zoom_in" else "zoom_out"
        zoomTelemetryTimer.restart()
        applyZoom(currentZoom)
    }

    private fun updateZoomControls() {
        val effective = effectiveZoom()
        zoomLabel.text = "${(effective * 100).toInt()}%"
        zoomOutButton.isEnabled = effective > ZOOM_STEPS.first() + 0.001
        zoomInButton.isEnabled = effective < ZOOM_STEPS.last() - 0.001
    }

    private fun effectiveZoom(): Double =
        when (selectedMode) {
            MODE_TWO_UP -> twoUp.effectiveZoom()
            MODE_SWIPE -> swipe.effectiveZoom()
            MODE_ONION -> onion.effectiveZoom()
            MODE_DIFF -> diff.effectiveZoom()
            else -> single.effectiveZoom()
        }

    private fun createZoomButton(accessibleName: String, icon: Icon): JButton =
        JButton(icon).apply {
            accessibleContext.accessibleName = accessibleName
            isFocusable = false
            alignmentX = CENTER_ALIGNMENT
            margin = JBUI.insets(0)
            preferredSize = Dimension(JBUI.scale(32), JBUI.scale(32))
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

    companion object {
        private const val MODE_TWO_UP = "twoup"
        private const val MODE_SWIPE = "swipe"
        private const val MODE_ONION = "onion"
        private const val MODE_DIFF = "diff"
        private const val MODE_SINGLE = "single"

        private val ZOOM_STEPS = listOf(0.25, 0.50, 0.75, 1.0, 1.5, 2.0, 4.0)
    }
}

private class ZoomIcon(
    private val isPlus: Boolean,
) : Icon {
    private val size = JBUI.scale(16)

    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size

    override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = if (c?.isEnabled == false) {
                JBUI.CurrentTheme.Label.disabledForeground()
            } else {
                JBUI.CurrentTheme.Label.foreground()
            }
            val centerX = x + iconWidth / 2
            val centerY = y + iconHeight / 2
            val half = JBUI.scale(5)
            g2.drawLine(centerX - half, centerY, centerX + half, centerY)
            if (isPlus) {
                g2.drawLine(centerX, centerY - half, centerX, centerY + half)
            }
        } finally {
            g2.dispose()
        }
    }
}

private class BottomRightOverlayPanel(
    private val content: JComponent,
    private val overlay: JComponent,
) : JPanel(null) {
    init {
        add(content)
        add(overlay)
        setComponentZOrder(overlay, 0)
        isOpaque = false
        content.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = positionOverlay()
                override fun componentMoved(e: ComponentEvent) = positionOverlay()
            },
        )
        attachScrollListeners(content)
    }

    override fun doLayout() {
        content.setBounds(0, 0, width, height)
        positionOverlay()
    }

    private fun positionOverlay() {
        val overlaySize = overlay.preferredSize
        val margin = JBUI.scale(12)
        overlay.setBounds(
            (width - overlaySize.width - margin).coerceAtLeast(margin),
            (height - overlaySize.height - margin).coerceAtLeast(margin),
            overlaySize.width,
            overlaySize.height,
        )
        overlay.repaint()
    }

    private fun attachScrollListeners(component: java.awt.Component) {
        if (component is JScrollPane) {
            component.horizontalScrollBar.addAdjustmentListener { positionOverlay() }
            component.verticalScrollBar.addAdjustmentListener { positionOverlay() }
            component.viewport.addChangeListener { positionOverlay() }
        }
        if (component is Container) {
            for (index in 0 until component.componentCount) {
                attachScrollListeners(component.getComponent(index))
            }
        }
    }

    override fun getPreferredSize(): Dimension = content.preferredSize

    override fun getMinimumSize(): Dimension = content.minimumSize
}

private class WrapLayout(
    align: Int,
    hgap: Int,
    vgap: Int,
) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = false).apply { width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
            val maxWidth = if (target.width > 0) {
                target.width - horizontalInsetsAndGap
            } else {
                Int.MAX_VALUE
            }
            val dimension = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (index in 0 until target.componentCount) {
                val component = target.getComponent(index)
                if (!component.isVisible) continue
                val size = if (preferred) component.preferredSize else component.minimumSize
                if (rowWidth > 0 && rowWidth + hgap + size.width > maxWidth) {
                    addRow(dimension, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth > 0) rowWidth += hgap
                rowWidth += size.width
                rowHeight = maxOf(rowHeight, size.height)
            }

            addRow(dimension, rowWidth, rowHeight)
            dimension.width += horizontalInsetsAndGap
            dimension.height += insets.top + insets.bottom + vgap * 2
            return dimension
        }
    }

    private fun addRow(dimension: Dimension, rowWidth: Int, rowHeight: Int) {
        dimension.width = maxOf(dimension.width, rowWidth)
        if (dimension.height > 0) dimension.height += vgap
        dimension.height += rowHeight
    }
}
