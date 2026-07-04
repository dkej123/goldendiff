package com.github.dkwasniak.goldendiff.compare

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.image.BufferedImage
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.JToggleButton

/** Hosts the three comparison modes plus a single-image view, the mode switcher and a zoom control. */
class CompareView : JPanel(BorderLayout()) {

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    private val twoUp = TwoUpPanel()
    private val swipe = SwipePanel()
    private val onion = OnionSkinPanel()
    private val diff = DiffPanel()
    private val single = SingleImagePanel()

    private val modeBar: JPanel
    private val status = JBLabel().apply { border = JBUI.Borders.empty(4) }

    private var selectedMode = MODE_TWO_UP
    private var currentZoom = ImagePainting.FIT

    init {
        cards.add(twoUp, MODE_TWO_UP)
        cards.add(swipe, MODE_SWIPE)
        cards.add(onion, MODE_ONION)
        cards.add(diff, MODE_DIFF)
        cards.add(single, MODE_SINGLE)

        modeBar = buildModeBar()
        add(buildToolbar(), BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        cardLayout.show(cards, MODE_SINGLE)
        modeBar.isVisible = false
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
        modeBar.isVisible = true
        cardLayout.show(cards, selectedMode)
    }

    fun showSingle(image: BufferedImage?, statusText: String) {
        single.setImage(image)
        single.setZoom(currentZoom)
        status.text = statusText
        modeBar.isVisible = false
        cardLayout.show(cards, MODE_SINGLE)
    }

    private fun applyZoom(zoom: Double) {
        twoUp.setZoom(zoom)
        swipe.setZoom(zoom)
        onion.setZoom(zoom)
        diff.setZoom(zoom)
        single.setZoom(zoom)
    }

    private fun buildToolbar(): JPanel {
        val zoomBar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(4)))
        zoomBar.add(JBLabel("Zoom:"))
        val zoomCombo = ComboBox(ZOOM_OPTIONS.keys.toTypedArray())
        zoomCombo.addActionListener {
            currentZoom = ZOOM_OPTIONS[zoomCombo.selectedItem] ?: ImagePainting.FIT
            applyZoom(currentZoom)
        }
        zoomBar.add(zoomCombo)

        return JPanel(BorderLayout()).apply {
            add(modeBar, BorderLayout.WEST)
            add(zoomBar, BorderLayout.EAST)
        }
    }

    private fun buildModeBar(): JPanel {
        val group = ButtonGroup()
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))

        fun button(title: String, mode: String): JToggleButton =
            JToggleButton(title).apply {
                isSelected = mode == selectedMode
                addActionListener {
                    selectedMode = mode
                    cardLayout.show(cards, mode)
                }
                group.add(this)
                bar.add(this)
            }

        button("Side by side", MODE_TWO_UP)
        button("Swipe", MODE_SWIPE)
        button("Onion skin", MODE_ONION)
        button("Diff", MODE_DIFF)
        return bar
    }

    companion object {
        private const val MODE_TWO_UP = "twoup"
        private const val MODE_SWIPE = "swipe"
        private const val MODE_ONION = "onion"
        private const val MODE_DIFF = "diff"
        private const val MODE_SINGLE = "single"

        private val ZOOM_OPTIONS = linkedMapOf(
            "Fit" to ImagePainting.FIT,
            "50%" to 0.5,
            "75%" to 0.75,
            "100%" to 1.0,
            "150%" to 1.5,
            "200%" to 2.0,
            "400%" to 4.0,
        )
    }
}
