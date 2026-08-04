package com.github.dkwasniak.goldendiff.watch

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PngChangeClassifierTest {

    @Test
    fun `refresh mode ignores empty batches`() {
        assertEquals(PngRefreshMode.NONE, pngRefreshMode(emptyList(), overflow = false))
    }

    @Test
    fun `refresh mode updates modifications in place`() {
        val modified = listOf(PngChangeEvent(File("screen.png"), PngChangeKind.MODIFIED))

        assertEquals(PngRefreshMode.IN_PLACE, pngRefreshMode(modified, overflow = false))
    }

    @Test
    fun `refresh mode rescans after structural events or overflow`() {
        val created = listOf(PngChangeEvent(File("screen.png"), PngChangeKind.CREATED))
        val deleted = listOf(PngChangeEvent(File("screen.png"), PngChangeKind.DELETED))

        assertEquals(PngRefreshMode.RESCAN, pngRefreshMode(created, overflow = false))
        assertEquals(PngRefreshMode.RESCAN, pngRefreshMode(deleted, overflow = false))
        assertEquals(PngRefreshMode.RESCAN, pngRefreshMode(emptyList(), overflow = true))
    }
}
