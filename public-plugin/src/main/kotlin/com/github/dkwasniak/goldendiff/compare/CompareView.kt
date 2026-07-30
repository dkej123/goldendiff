package com.github.dkwasniak.goldendiff.compare

import com.github.dkwasniak.goldendiff.toolwindow.AcceptActionButton
import com.github.dkwasniak.goldendiff.toolwindow.FlatHeaderButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.BasicStroke
import java.awt.CardLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.JToggleButton
import javax.swing.Icon

/** Hosts the three comparison modes plus a single-image view, the mode switcher and a zoom control. */
class CompareView(
    private val onModeSelected: (String) -> Unit = {},
    private val onZoomSelected: (String, String) -> Unit = { _, _ -> },
    private val onAccept: () -> Unit = {},
    private val onOpenSeparate: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val mirrors = linkedSetOf<CompareView>()
    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    private val twoUp = TwoUpPanel()
    private val swipe = SwipePanel()
    private val onion = OnionSkinPanel()
    private val diff = DiffPanel()
    private val single = SingleImagePanel()

    private val modeButtons = mutableListOf<JToggleButton>()
    private val acceptedGreen = JBColor(Color(0x2E7D32), Color(0x6FBF73))
    private val acceptPink = JBColor(Color(0xD72F72), Color(0xE7468A))
    private val acceptButton = AcceptActionButton(
        "\u2713 Accept test output",
        acceptPink,
        pill = false,
    ).apply {
        toolTipText = "Accept test output as the golden"
        isVisible = false
        addActionListener { onAccept() }
    }
    private val acceptedLabel = JBLabel("\u2713 Accepted").apply {
        font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 10.5f)
        foreground = acceptedGreen
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(acceptedGreen),
            JBUI.Borders.empty(4, 10),
        )
        isVisible = false
    }
    private val openSeparateButton = onOpenSeparate?.let { action ->
        FlatHeaderButton("", CompareExpandIcon, height = 22, horizontalPadding = 3).apply {
            toolTipText = "Open comparison in a separate window"
            accessibleContext.accessibleName = toolTipText
            preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            minimumSize = preferredSize
            maximumSize = preferredSize
            addActionListener { action() }
        }
    }
    private var titleText: String? = null
    private val titleArea = JBLabel().apply {
        border = JBUI.Borders.empty(4, 6, 2, 0)
        font = Font(Font.MONOSPACED, Font.BOLD, 11)
    }
    private val acceptActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(acceptButton)
        add(acceptedLabel)
        openSeparateButton?.let(::add)
    }
    private val titleActions = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(acceptActions, BorderLayout.EAST)
    }
    private val titleBar = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 4, 4, 6)
        titleArea.alignmentX = LEFT_ALIGNMENT
        titleActions.alignmentX = LEFT_ALIGNMENT
        add(titleArea)
        add(titleActions)
        isVisible = false
    }
    private val status = JBLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(10f)
        border = JBUI.Borders.empty(4)
    }
    private var retryAction: (() -> Unit)? = null
    private val retryButton = JButton("Retry").apply {
        font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 10.5f)
        border = JBUI.Borders.empty(2, 8)
        isVisible = false
        addActionListener { retryAction?.invoke() }
    }
    private val statusBar = JPanel(BorderLayout()).apply {
        add(status, BorderLayout.CENTER)
        add(retryButton, BorderLayout.EAST)
    }
    private val zoomLabel = JBLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(9f)
        horizontalAlignment = SwingConstants.CENTER
        foreground = UIUtil.getContextHelpForeground()
        preferredSize = Dimension(JBUI.scale(26), JBUI.scale(18))
        minimumSize = preferredSize
        maximumSize = preferredSize
    }
    private val zoomOutButton = createZoomButton("\u2212").apply {
        toolTipText = "Zoom out"
        addActionListener { changeZoom(-1) }
    }
    private val zoomInButton = createZoomButton("+").apply {
        toolTipText = "Zoom in"
        addActionListener { changeZoom(1) }
    }
    private val fitButton = FlatHeaderButton("Fit", height = 18, horizontalPadding = 1).apply {
        toolTipText = "Fit images to the available area"
        isFocusable = false
        val compactSize = Dimension(
            getFontMetrics(font).stringWidth(text) + JBUI.scale(6),
            JBUI.scale(18),
        )
        preferredSize = compactSize
        minimumSize = compactSize
        maximumSize = compactSize
        addActionListener {
            currentZoom = ImagePainting.FIT
            pendingZoomAction = "fit"
            zoomTelemetryTimer.restart()
            applyZoom(currentZoom)
        }
    }
    private val zoomOverlay = RoundedZoomPanel().apply {
        add(fitButton)
        add(zoomOutButton)
        add(zoomLabel)
        add(zoomInButton)
    }
    private val zoomBar = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, 12)
        add(zoomOverlay, BorderLayout.EAST)
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
        add(cards, BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(zoomBar, BorderLayout.NORTH)
                add(statusBar, BorderLayout.SOUTH)
            },
            BorderLayout.SOUTH,
        )

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
        mirrors.forEach { it.setTitle(text) }
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
        hideAcceptState()
        setModeButtonsVisible(true)
        cardLayout.show(cards, selectedMode)
        updateZoomControls()
        mirrors.forEach { it.showComparison(old, new, statusText, oldLabel, newLabel) }
    }

    fun showSingle(image: BufferedImage?, statusText: String) {
        single.setImage(image)
        single.setZoom(currentZoom)
        status.text = statusText
        hideRetry()
        hideAcceptState()
        setModeButtonsVisible(false)
        cardLayout.show(cards, MODE_SINGLE)
        updateZoomControls()
        mirrors.forEach { it.showSingle(image, statusText) }
    }

    fun showLoading(statusText: String) {
        showSingle(null, statusText)
    }

    /** A non-comparable state such as an absent or ambiguous Test output. */
    fun showBlocked(
        title: String,
        body: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ) {
        single.setImage(null)
        single.setZoom(currentZoom)
        status.text = "<html><b>$title</b><br>${body.replace("\n", "<br>")}</html>"
        retryAction = action
        retryButton.text = actionLabel.orEmpty()
        retryButton.isVisible = action != null && !actionLabel.isNullOrBlank()
        hideAcceptState()
        setModeButtonsVisible(false)
        cardLayout.show(cards, MODE_SINGLE)
        updateZoomControls()
        mirrors.forEach { it.showBlocked(title, body, actionLabel, action) }
    }

    /** Show an error message with a retry button — used when a reference could not be loaded. */
    fun showRetry(statusText: String, retryLabel: String = "Retry", action: () -> Unit) {
        single.setImage(null)
        single.setZoom(currentZoom)
        status.text = statusText
        retryAction = action
        retryButton.text = retryLabel
        retryButton.isVisible = true
        hideAcceptState()
        setModeButtonsVisible(false)
        cardLayout.show(cards, MODE_SINGLE)
        updateZoomControls()
        mirrors.forEach { it.showRetry(statusText, retryLabel, action) }
    }

    private fun hideRetry() {
        retryButton.isVisible = false
        retryAction = null
    }

    /** Shows the generated-output promotion next to the comparison mode controls. */
    fun showAccept(enabled: Boolean = true) {
        acceptedLabel.isVisible = false
        acceptButton.text = "\u2713 Accept test output"
        acceptButton.isEnabled = enabled
        acceptButton.isVisible = true
        mirrors.forEach { it.showAccept(enabled) }
    }

    /** Keeps the preview stable while the file move and decode happen off the EDT. */
    fun showAccepting() {
        acceptedLabel.isVisible = false
        acceptButton.text = "Accepting\u2026"
        acceptButton.isEnabled = false
        acceptButton.isVisible = true
        mirrors.forEach { it.showAccepting() }
    }

    /** Replaces the comparison with the promoted image and a persistent confirmation state. */
    fun showAccepted(image: BufferedImage?, statusText: String) {
        showSingle(image, statusText)
        acceptButton.isVisible = false
        acceptedLabel.isVisible = true
        mirrors.forEach {
            it.acceptButton.isVisible = false
            it.acceptedLabel.isVisible = true
        }
    }

    /** Keeps a detached comparison rendering in sync while preserving its independent mode/zoom. */
    fun addMirror(view: CompareView) {
        require(view !== this)
        mirrors += view
    }

    fun removeMirror(view: CompareView) {
        mirrors -= view
    }

    private fun hideAcceptState() {
        acceptButton.isVisible = false
        acceptedLabel.isVisible = false
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
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 10.5f)
                margin = JBUI.insets(3, 7)
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
        modeButton("Pixel diff", MODE_DIFF)
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

    private fun createZoomButton(text: String): JButton =
        FlatHeaderButton(text, height = 18, horizontalPadding = 3).apply {
            val accessibleName = if (text == "+") "Zoom in" else "Zoom out"
            accessibleContext.accessibleName = accessibleName
            isFocusable = false
            preferredSize = Dimension(JBUI.scale(18), JBUI.scale(18))
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

/** The same opposite-corner arrows used by the standalone app for detached comparison. */
private object CompareExpandIcon : Icon {
    override fun getIconWidth(): Int = JBUI.scale(14)

    override fun getIconHeight(): Int = JBUI.scale(14)

    override fun paintIcon(component: java.awt.Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UIUtil.getContextHelpForeground()
            g.stroke = BasicStroke(JBUI.scale(1).toFloat())
            val inset = JBUI.scale(2)
            val length = JBUI.scale(4)
            val right = x + iconWidth - inset
            val bottom = y + iconHeight - inset
            val left = x + inset
            val top = y + inset
            g.drawLine(left, top + length, left, top)
            g.drawLine(left, top, left + length, top)
            g.drawLine(right, bottom - length, right, bottom)
            g.drawLine(right, bottom, right - length, bottom)
        } finally {
            g.dispose()
        }
    }
}

private class RoundedZoomPanel : JPanel(
    FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0),
) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 4)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(10)
            g.color = SurfaceColor
            g.fillRoundRect(0, 0, width, height, arc, arc)
            g.color = BorderColor
            g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
    }

    private companion object {
        val SurfaceColor = JBColor(Color(0xF2F3F5), Color(0x2B2D30))
        val BorderColor = JBColor(Color(0xE1E3E8), Color(0x3A3C3F))
    }
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
