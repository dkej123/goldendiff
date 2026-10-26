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
    fun `golden strip thumbnails keep the image aspect at a fixed height`() {
        val landscape = GoldenStripPanel.thumbnailSize(200, 100, 104)
        val missing = GoldenStripPanel.thumbnailSize(null, null, 104)
        val panorama = GoldenStripPanel.thumbnailSize(1000, 100, 104)

        assertEquals(java.awt.Dimension(208, 104), landscape)
        assertEquals(104, missing.height)
        assertTrue("placeholder is portrait", missing.width < missing.height)
        assertTrue("very wide images are capped", panorama.width <= (104 * 2.4).toInt())
    }

    @Test
    fun `golden strip labels drop the segments shared by every golden`() {
        assertEquals(
            listOf("chargeLeague", "allego"),
            GoldenStripPanel.stripLabels(
                listOf(
                    "com.example.Tests.IonityHeader.chargeLeague.Dark_en_NIGHT_PIXEL_7_PRO",
                    "com.example.Tests.IonityHeader.allego.Dark_en_NIGHT_PIXEL_7_PRO",
                ),
            ),
        )
        assertEquals(listOf("a", "a.dark"), GoldenStripPanel.stripLabels(listOf("pkg.T.a", "pkg.T.a.dark")))
        assertEquals(listOf("Paparazzi_Test_dark"), GoldenStripPanel.stripLabels(listOf("Paparazzi_Test_dark")))
        assertEquals(listOf("greetingCardScreen"), GoldenStripPanel.stripLabels(listOf("com.example.Tests.greetingCardScreen")))
    }

    @Test
    fun `very wide goldens are letterboxed inside the capped thumbnail without distortion`() {
        val box = GoldenStripPanel.thumbnailSize(1080, 200, 104)
        val rect = GoldenStripPanel.fittedImageRect(1080, 200, box)

        assertEquals(box.width, rect.width)
        assertEquals(1080.0 / 200, rect.width.toDouble() / rect.height, 0.05)
        assertEquals((box.height - rect.height) / 2, rect.y)

        val normal = GoldenStripPanel.fittedImageRect(200, 100, GoldenStripPanel.thumbnailSize(200, 100, 104))
        assertEquals(java.awt.Rectangle(0, 0, 208, 104), normal)
    }

    @Test
    fun `status chip is a compact accessible toggle`() {
        val chip = StatusChipToggle("Failed", JBColor(Color.RED, Color.RED))

        assertEquals(JBUI.scale(20), chip.preferredSize.height)
        assertEquals("Failed status filter", chip.accessibleContext.accessibleName)
        chip.doClick()
        assertTrue(chip.isSelected)
    }

    @Test
    fun `status pill hides for unchanged and shows the badge label otherwise`() {
        val pill = PillLabel()

        pill.showStatus(GoldenDisplayStatus.UNCHANGED)
        assertFalse(pill.isVisible)

        pill.showStatus(GoldenDisplayStatus.ACCEPTED)
        assertTrue(pill.isVisible)
        assertEquals("Accepted", pill.text)
    }

    @Test
    fun `tabs header exposes the failed count to screen readers`() {
        val header = ReviewTabsHeader {}

        header.render(ReviewTab.REVIEW, failedViews = 3)

        val files = header.getComponent(0) as javax.swing.JToggleButton
        assertEquals("Files, 3 failed", files.accessibleContext.accessibleName)
        assertFalse(files.isSelected)
    }
}
