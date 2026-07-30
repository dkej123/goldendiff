package com.github.dkwasniak.goldendiff.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import javax.swing.JMenuItem
import javax.swing.JToolTip
import com.intellij.util.ui.EmptyIcon
import java.awt.event.KeyEvent

class CompactSwingControlsTest {
    @Test
    fun `accept action width is only text plus handoff padding`() {
        val button = AcceptActionButton(
            "Accept all (3)",
            JBColor(Color.RED, Color.RED),
            pill = true,
        )

        assertEquals(JBUI.scale(22), button.preferredSize.height)
        assertEquals(
            button.getFontMetrics(button.font).stringWidth(button.text) + JBUI.scale(18),
            button.preferredSize.width,
        )
        assertFalse(button.isContentAreaFilled)
        assertFalse(button.isBorderPainted)
    }

    @Test
    fun `long path tooltip wraps within the Files pane width`() {
        val tooltip = JToolTip()
        tooltip.tipText = boundedPathTooltip(
            "presentation/src/main/kotlin/com/example/feature/map/MapScreen.kt",
            JBUI.scale(260),
            tooltip.getFontMetrics(tooltip.font),
        )

        assertTrue(
            "Tooltip width ${tooltip.preferredSize.width} exceeded ${JBUI.scale(260)}",
            tooltip.preferredSize.width <= JBUI.scale(260),
        )
        assertTrue(tooltip.preferredSize.height > tooltip.getFontMetrics(tooltip.font).height)
    }

    @Test
    fun `styled context menu keeps compact accessible action rows`() {
        val popup = StyledContextMenu().apply {
            addHeader("MapScreen.kt")
            addSectionSeparator()
            addAction("Generate for file") {}
        }
        val action = popup.getComponent(2) as JMenuItem

        assertEquals(JBUI.scale(24), action.preferredSize.height)
        assertEquals("Generate for file", action.accessibleContext?.accessibleName)
        assertFalse(popup.isOpaque)
    }

    @Test
    fun `golden column choices fill all available horizontal space`() {
        val layouts = (1..3).map { goldenGridGeometry(500, it) }

        assertEquals(listOf(1, 2, 3), layouts.map { it.columns })
        assertTrue(layouts.all { it.cardWidth == it.cellWidth - 8 })
        assertTrue(
            layouts.zipWithNext().all { (first, second) ->
                second.cardWidth < first.cardWidth
            },
        )
    }

    @Test
    fun `golden column choice is constrained to supported layouts`() {
        assertEquals(1, goldenGridGeometry(500, 0).columns)
        assertEquals(3, goldenGridGeometry(500, 4).columns)
    }

    @Test
    fun `grid column button can initialize before its accessible context exists`() {
        val button = GridColumnButton(3)

        assertEquals("Show goldens in 3 columns", button.toolTipText)
    }

    @Test
    fun `extra source filter matches Files chip geometry and exposes icon selection and accessibility`() {
        val chip = ExtraSourceFilterChip()
        val icon = EmptyIcon.create(10, 14)
        chip.configure("Figma", icon, selected = true)

        assertEquals(JBUI.scale(20), chip.preferredSize.height)
        assertEquals(icon, chip.icon)
        assertTrue(chip.isSelected)
        assertTrue(chip.isVisible)
        assertEquals("Figma golden filter", chip.getAccessibleContext()?.accessibleName)

        chip.dispatchEvent(
            KeyEvent(chip, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_SPACE, ' '),
        )
        assertTrue(chip.isFocusable)
    }

    @Test
    fun `extra source filter hides and clears selection without source data`() {
        val chip = ExtraSourceFilterChip()
        chip.configure("Figma", EmptyIcon.create(10), selected = true)

        chip.configure(null, null, selected = false)

        assertFalse(chip.isVisible)
        assertFalse(chip.isSelected)
    }

    @Test
    fun `extension badge stays inside the top right corner of a golden card`() {
        val geometry = sourceBadgeGeometry(
            cardWidth = JBUI.scale(120),
            textWidth = JBUI.scale(26),
            iconWidth = JBUI.scale(10),
        )

        assertEquals(JBUI.scale(4), geometry.y)
        assertEquals(JBUI.scale(18), geometry.height)
        assertEquals(JBUI.scale(4), JBUI.scale(120) - geometry.x - geometry.width)
        assertTrue(geometry.x >= 0)
    }
}
