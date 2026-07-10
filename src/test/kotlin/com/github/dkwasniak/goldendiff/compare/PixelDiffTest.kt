package com.github.dkwasniak.goldendiff.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

class PixelDiffTest {

    private fun solid(w: Int, h: Int, argb: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until h) for (x in 0 until w) setRGB(x, y, argb)
    }

    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF
    private fun rgb(argb: Int) = argb and 0xFFFFFF

    @Test
    fun `identical images report no change`() {
        val a = solid(4, 3, 0xFFFFFFFF.toInt())
        val b = solid(4, 3, 0xFFFFFFFF.toInt())

        val result = PixelDiff.compute(a, b)!!

        assertEquals(0, result.changedPixels)
        assertEquals(12, result.totalPixels)
        assertEquals(0.0, result.changedRatio, 0.0)
        assertEquals(4, result.image.width)
        assertEquals(3, result.image.height)
    }

    @Test
    fun `unchanged pixels are dimmed grayscale`() {
        val white = 0xFFFFFFFF.toInt()
        val result = PixelDiff.compute(solid(1, 1, white), solid(1, 1, white))!!

        val px = result.image.getRGB(0, 0)
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        assertEquals("grayscale r==g", r, g)
        assertEquals("grayscale g==b", g, b)
        assertTrue("dimmed alpha < opaque", alpha(px) < 255)
    }

    @Test
    fun `a single differing pixel is counted and highlighted`() {
        val a = solid(2, 1, 0xFF000000.toInt())
        val b = solid(2, 1, 0xFF000000.toInt())
        b.setRGB(0, 0, 0xFFFFFFFF.toInt()) // change one of the two pixels

        val result = PixelDiff.compute(a, b)!!

        assertEquals(1, result.changedPixels)
        assertEquals(0.5, result.changedRatio, 0.0)
        assertEquals("changed pixel is magenta", 0xFF00FF, rgb(result.image.getRGB(0, 0)))
        assertTrue("highlight is visible", alpha(result.image.getRGB(0, 0)) >= 120)
    }

    @Test
    fun `different sized images are downscaled to smaller shared size`() {
        val a = solid(1, 1, 0xFF000000.toInt())
        val b = solid(3, 1, 0xFF000000.toInt())

        val result = PixelDiff.compute(a, b)!!

        assertEquals(1, result.image.width)
        assertEquals(1, result.image.height)
        assertEquals(0, result.changedPixels)
        assertEquals(1, result.totalPixels)
    }

    @Test
    fun `null inputs`() {
        assertNull(PixelDiff.compute(null, null))
        assertNotNull(PixelDiff.compute(null, solid(2, 2, 0xFF123456.toInt())))
    }

    @Test
    fun `single existing image uses its full size and counts as changed`() {
        val result = PixelDiff.compute(null, solid(2, 2, 0xFF123456.toInt()))!!

        assertEquals(2, result.image.width)
        assertEquals(2, result.image.height)
        assertEquals(4, result.changedPixels)
        assertEquals(4, result.totalPixels)
    }
}
