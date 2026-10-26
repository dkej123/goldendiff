package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.scan.GoldenSourceVariant
import com.github.dkwasniak.goldendiff.scan.MergedGoldenItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GoldenDisplayStatusTest {

    private val golden = File("/tmp/golden.png")
    private val generated = File("/tmp/out/golden.png")

    @Test
    fun `merged statuses map one to one without an accepted overlay`() {
        assertEquals(GoldenDisplayStatus.FAILED, goldenDisplayStatus(failed(), accepted = false))
        assertEquals(GoldenDisplayStatus.MODIFIED, goldenDisplayStatus(working(ExtraComparisonItemStatus.MODIFIED), false))
        assertEquals(GoldenDisplayStatus.NEW, goldenDisplayStatus(working(ExtraComparisonItemStatus.NEW), false))
        assertEquals(GoldenDisplayStatus.UNCHANGED, goldenDisplayStatus(working(ExtraComparisonItemStatus.UNCHANGED), false))
    }

    @Test
    fun `accepted overlay replaces every status except failed`() {
        assertEquals(GoldenDisplayStatus.ACCEPTED, goldenDisplayStatus(working(ExtraComparisonItemStatus.MODIFIED), true))
        assertEquals(GoldenDisplayStatus.ACCEPTED, goldenDisplayStatus(working(ExtraComparisonItemStatus.NEW), true))
        assertEquals(GoldenDisplayStatus.ACCEPTED, goldenDisplayStatus(working(ExtraComparisonItemStatus.UNCHANGED), true))
        assertEquals(GoldenDisplayStatus.FAILED, goldenDisplayStatus(failed(), accepted = true))
    }

    @Test
    fun `worst status follows failed modified new accepted unchanged`() {
        assertEquals(
            GoldenDisplayStatus.FAILED,
            worstDisplayStatus(listOf(GoldenDisplayStatus.NEW, GoldenDisplayStatus.FAILED, GoldenDisplayStatus.MODIFIED)),
        )
        assertEquals(
            GoldenDisplayStatus.MODIFIED,
            worstDisplayStatus(listOf(GoldenDisplayStatus.NEW, GoldenDisplayStatus.MODIFIED)),
        )
        assertEquals(
            GoldenDisplayStatus.NEW,
            worstDisplayStatus(listOf(GoldenDisplayStatus.ACCEPTED, GoldenDisplayStatus.NEW)),
        )
        assertEquals(
            GoldenDisplayStatus.ACCEPTED,
            worstDisplayStatus(listOf(GoldenDisplayStatus.UNCHANGED, GoldenDisplayStatus.ACCEPTED)),
        )
        assertEquals(GoldenDisplayStatus.UNCHANGED, worstDisplayStatus(emptyList()))
    }

    @Test
    fun `index and extension statuses map to display statuses`() {
        assertEquals(GoldenDisplayStatus.FAILED, SnapshotFileStatus.FAILED.toDisplayStatus())
        assertEquals(GoldenDisplayStatus.UNCHANGED, SnapshotFileStatus.UNCHANGED.toDisplayStatus())
        assertEquals(GoldenDisplayStatus.MODIFIED, ExtraComparisonItemStatus.MODIFIED.toDisplayStatus())
        assertEquals(GoldenDisplayStatus.NEW, ExtraComparisonItemStatus.NEW.toDisplayStatus())
    }

    @Test
    fun `badge and status texts follow the design copy`() {
        assertNull(GoldenDisplayStatus.UNCHANGED.badgeLabel)
        assertEquals("Failed", GoldenDisplayStatus.FAILED.badgeLabel)
        assertEquals("Accepted", GoldenDisplayStatus.ACCEPTED.badgeLabel)
        assertEquals("New — not in HEAD", GoldenDisplayStatus.NEW.statusText)
        assertEquals("Accepted — saved as golden", GoldenDisplayStatus.ACCEPTED.statusText)
    }

    @Test
    fun `accept button is enabled only for an acceptable built-in test output`() {
        assertEquals(AcceptButtonState.ENABLED, acceptButtonState(GoldenGridItem.BuiltIn(failed()), accepted = false, accepting = false))
        assertEquals(
            AcceptButtonState.DISABLED,
            acceptButtonState(GoldenGridItem.BuiltIn(working(ExtraComparisonItemStatus.MODIFIED)), false, false),
        )
        assertEquals(AcceptButtonState.DISABLED, acceptButtonState(null, accepted = false, accepting = false))
    }

    @Test
    fun `accept button reports accepting and accepted states`() {
        val consumed = GoldenGridItem.BuiltIn(working(ExtraComparisonItemStatus.MODIFIED))
        assertEquals(AcceptButtonState.ACCEPTING, acceptButtonState(GoldenGridItem.BuiltIn(failed()), false, accepting = true))
        assertEquals(AcceptButtonState.ACCEPTED, acceptButtonState(consumed, accepted = true, accepting = false))
        assertEquals(
            "A new failing Test output can be accepted again",
            AcceptButtonState.ENABLED,
            acceptButtonState(GoldenGridItem.BuiltIn(failed()), accepted = true, accepting = false),
        )
    }

    private fun failed() = MergedGoldenItem(
        golden,
        GoldenSourceVariant(golden, ExtraComparisonItemStatus.UNCHANGED),
        GoldenSourceVariant(generated, ExtraComparisonItemStatus.MODIFIED),
    )

    private fun working(status: ExtraComparisonItemStatus) =
        MergedGoldenItem(golden, GoldenSourceVariant(golden, status), null)
}
