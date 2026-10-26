package com.github.dkwasniak.goldendiff.compare

import com.github.dkwasniak.goldendiff.toolwindow.AcceptActionButton
import com.github.dkwasniak.goldendiff.toolwindow.AcceptButtonState
import com.github.dkwasniak.goldendiff.toolwindow.FlatHeaderButton
import com.github.dkwasniak.goldendiff.toolwindow.GoldenDiffColors
import com.github.dkwasniak.goldendiff.toolwindow.SegmentedControl
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Review comparison area: the Side / Swipe / Onion / Diff mode row with Accept on the right, the
 * comparison canvases with a floating zoom control, and a message line that is shown only when
 * there is no regular comparison (loading, blocked, retry, single image).
 */
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

    private val modes = SegmentedControl(
        listOf(MODE_TWO_UP to "Side", MODE_SWIPE to "Swipe", MODE_ONION to "Onion", MODE_DIFF to "Diff"),
    ) { mode -> selectMode(mode) }
    private val acceptButton = AcceptActionButton("✓ Accept", GoldenDiffColors.brand, pill = false).apply {
        toolTipText = "Accept test output as the golden"
        addActionListener { onAccept() }
    }
    private var acceptState = AcceptButtonState.DISABLED
    private val openSeparateButton = onOpenSeparate?.let { action ->
        FlatHeaderButton("", CompareExpandIcon, height = 22, horizontalPadding = 3).apply {
            toolTipText = "Open comparison in a separate window"
            getAccessibleContext()?.accessibleName = toolTipText
            preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            minimumSize = preferredSize
            maximumSize = preferredSize
            addActionListener { action() }
        }
    }
    private val modeRow = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = GoldenDiffColors.background
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(GoldenDiffColors.border, 0, 0, 1, 0),
            JBUI.Borders.empty(6, 8, 6, 10),
        )
        add(modes, BorderLayout.WEST)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                openSeparateButton?.let(::add)
                add(acceptButton)
            },
            BorderLayout.EAST,
        )
    }
    private val status = JBLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
        foreground = GoldenDiffColors.dim
        border = JBUI.Borders.empty(6, 12)
    }
    private var retryAction: (() -> Unit)? = null
    private val retryButton = JButton("Retry").apply {
        font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(10.5f).toFloat())
        isVisible = false
        addActionListener { retryAction?.invoke() }
    }
    private val messageBar = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = GoldenDiffColors.background
        add(status, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(4))).apply {
                isOpaque = false
                add(retryButton)
            },
            BorderLayout.EAST,
        )
    }
    private val zoomLabel = JBLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(9f).toFloat())
        horizontalAlignment = JBLabel.CENTER
        foreground = UIUtil.getContextHelpForeground()
        preferredSize = Dimension(JBUI.scale(30), JBUI.scale(18))
        minimumSize = preferredSize
        maximumSize = preferredSize
    }
    private val zoomOutButton = createZoomButton("−").apply {
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
        val compactSize = Dimension(getFontMetrics(font).stringWidth(text) + JBUI.scale(6), JBUI.scale(18))
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

    private val zoomLayer = FixedBottomRightOverlayPanel(cards, zoomOverlay, margin = 10) {
        when {
            !showingComparison -> 0
            selectedMode == MODE_SWIPE -> swipe.controlsHeight()
            selectedMode == MODE_ONION -> onion.controlsHeight()
            else -> 0
        }
    }

    private var selectedMode = MODE_TWO_UP
    private var showingComparison = false
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
        isOpaque = true
        background = GoldenDiffColors.background
        cards.add(twoUp, MODE_TWO_UP)
        cards.add(swipe, MODE_SWIPE)
        cards.add(onion, MODE_ONION)
        cards.add(diff, MODE_DIFF)
        cards.add(single, MODE_SINGLE)

        add(modeRow, BorderLayout.NORTH)
        add(zoomLayer, BorderLayout.CENTER)
        add(messageBar, BorderLayout.SOUTH)

        modes.select(selectedMode)
        cardLayout.show(cards, MODE_SINGLE)
        setComparisonControls(false)
        renderAcceptState()
        updateZoomControls()
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = updateZoomControls()
            },
        )
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
        showMessage(statusText, visible = false)
        hideRetry()
        setComparisonControls(true)
        cardLayout.show(cards, selectedMode)
        updateZoomControls()
        mirrors.forEach { it.showComparison(old, new, statusText, oldLabel, newLabel) }
    }

    fun showSingle(image: BufferedImage?, statusText: String) {
        single.setImage(image)
        single.setZoom(currentZoom)
        showMessage(statusText, visible = statusText.isNotBlank())
        hideRetry()
        setComparisonControls(false)
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
        showMessage("<html><b>$title</b><br>${body.replace("\n", "<br>")}</html>", visible = true)
        retryAction = action
        retryButton.text = actionLabel.orEmpty()
        retryButton.isVisible = action != null && !actionLabel.isNullOrBlank()
        setComparisonControls(false)
        cardLayout.show(cards, MODE_SINGLE)
        updateZoomControls()
        mirrors.forEach { it.showBlocked(title, body, actionLabel, action) }
    }

    /** Show an error message with a retry button — used when a reference could not be loaded. */
    fun showRetry(statusText: String, retryLabel: String = "Retry", action: () -> Unit) {
        single.setImage(null)
        single.setZoom(currentZoom)
        showMessage(statusText, visible = true)
        retryAction = action
        retryButton.text = retryLabel
        retryButton.isVisible = true
        setComparisonControls(false)
        cardLayout.show(cards, MODE_SINGLE)
        updateZoomControls()
        mirrors.forEach { it.showRetry(statusText, retryLabel, action) }
    }

    /** Accept is always present in the mode row; only a promotable Test output enables it. */
    internal fun setAcceptState(state: AcceptButtonState) {
        acceptState = state
        renderAcceptState()
        mirrors.forEach { it.setAcceptState(state) }
    }

    /** Keeps a detached comparison rendering in sync while preserving its independent mode/zoom. */
    fun addMirror(view: CompareView) {
        require(view !== this)
        mirrors += view
    }

    fun removeMirror(view: CompareView) {
        mirrors -= view
    }

    private fun renderAcceptState() {
        acceptButton.text = when (acceptState) {
            AcceptButtonState.ACCEPTING -> "Accepting…"
            AcceptButtonState.ACCEPTED -> "✓ Accepted"
            AcceptButtonState.ENABLED, AcceptButtonState.DISABLED -> "✓ Accept"
        }
        acceptButton.isEnabled = acceptState == AcceptButtonState.ENABLED
        acceptButton.toolTipText = when (acceptState) {
            AcceptButtonState.ENABLED -> "Accept test output as the golden"
            AcceptButtonState.ACCEPTED -> "Test output accepted as the golden"
            AcceptButtonState.ACCEPTING -> "Accepting test output…"
            AcceptButtonState.DISABLED -> "No test output to accept"
        }
        acceptButton.revalidate()
        acceptButton.repaint()
    }

    private fun showMessage(text: String, visible: Boolean) {
        status.text = text
        messageBar.isVisible = visible
        revalidate()
    }

    private fun hideRetry() {
        retryButton.isVisible = false
        retryAction = null
    }

    private fun selectMode(mode: String) {
        selectedMode = mode
        if (showingComparison) cardLayout.show(cards, mode)
        zoomLayer.repositionOverlay()
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

    private fun setComparisonControls(comparison: Boolean) {
        showingComparison = comparison
        modes.isEnabled = comparison
        zoomLayer.repositionOverlay()
    }

    private fun applyZoom(zoom: Double) {
        twoUp.setZoom(zoom)
        swipe.setZoom(zoom)
        onion.setZoom(zoom)
        diff.setZoom(zoom)
        single.setZoom(zoom)
        updateZoomControls()
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
        if (!showingComparison) {
            single.effectiveZoom()
        } else {
            when (selectedMode) {
                MODE_TWO_UP -> twoUp.effectiveZoom()
                MODE_SWIPE -> swipe.effectiveZoom()
                MODE_ONION -> onion.effectiveZoom()
                else -> diff.effectiveZoom()
            }
        }

    private fun createZoomButton(text: String): JButton =
        FlatHeaderButton(text, height = 18, horizontalPadding = 3).apply {
            val accessibleName = if (text == "+") "Zoom in" else "Zoom out"
            getAccessibleContext()?.accessibleName = accessibleName
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
            g.color = GoldenDiffColors.header
            g.fillRoundRect(0, 0, width, height, arc, arc)
            g.color = GoldenDiffColors.border
            g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
    }
}
