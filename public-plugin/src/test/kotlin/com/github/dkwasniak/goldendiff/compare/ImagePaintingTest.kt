package com.github.dkwasniak.goldendiff.compare

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Rectangle
import java.awt.image.BufferedImage

class ImagePaintingTest {

    private fun image(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `fitRect preserves aspect ratio and centers`() {
        // 100x50 image into a 200x200 area fits to width 200 -> would be 400 tall, so bound by height:
        // actually 200/100=2, 200/50=4 -> min scale 2 -> but capped at 1.0 (no upscaling).
        val rect = ImagePainting.fitRect(100, 50, 200, 200)
        assertEquals(100, rect.width)
        assertEquals(50, rect.height)
        assertEquals((200 - 100) / 2, rect.x)
        assertEquals((200 - 50) / 2, rect.y)
    }

    @Test
    fun `fitRect scales down large images to fit`() {
        val rect = ImagePainting.fitRect(400, 200, 200, 200)
        assertEquals(200, rect.width)
        assertEquals(100, rect.height)
    }

    @Test
    fun `invalid dimensions produce an empty rect`() {
        assertEquals(0, ImagePainting.fitRect(0, 10, 100, 100).width)
        assertEquals(0, ImagePainting.renderRect(1.0, 10, 10, 0, 100).width)
    }

    @Test
    fun `renderRect with FIT delegates to fitRect`() {
        val fit = ImagePainting.fitRect(400, 200, 200, 200)
        val render = ImagePainting.renderRect(ImagePainting.FIT, 400, 200, 200, 200)
        assertEquals(fit, render)
    }

    @Test
    fun `renderRect with explicit zoom scales and centers`() {
        val rect = ImagePainting.renderRect(2.0, 50, 25, 400, 400)
        assertEquals(100, rect.width)
        assertEquals(50, rect.height)
        assertEquals((400 - 100) / 2, rect.x)
        assertEquals((400 - 50) / 2, rect.y)
    }

    @Test
    fun `scaleForBounding fits the bounding box into the area`() {
        // 100x50 box into a 400x400 area at fit → scaled to width 400 → scale 4.0 (capped at fit).
        assertEquals(2.0, ImagePainting.scaleForBounding(2.0, 100, 50, 400, 400), 0.0001)
    }

    @Test
    fun `bottomRect centers horizontally and anchors to the bottom`() {
        val area = Rectangle(10, 20, 200, 100)
        val rect = ImagePainting.bottomRect(50, 25, 2.0, area)
        assertEquals(100, rect.width)
        assertEquals(50, rect.height)
        assertEquals(10 + (200 - 100) / 2, rect.x)
        assertEquals(20 + (100 - 50), rect.y)
    }

    @Test
    fun `boundingSize takes the larger bounds so relative scale is preserved`() {
        val (w, h) = ImagePainting.boundingSize(image(100, 40), image(80, 60))
        assertEquals(100, w)
        assertEquals(60, h)
    }

    @Test
    fun `boundingSize keeps equal width when only height differs`() {
        // Regression: two goldens with the same width but different height must not be rendered
        // as if their widths differ (a shorter image previously got stretched to full width).
        val (w, h) = ImagePainting.boundingSize(image(719, 258), image(719, 216))
        assertEquals(719, w)
        assertEquals(258, h)
    }

    @Test
    fun `boundingSize handles nulls`() {
        val (w, h) = ImagePainting.boundingSize(null, image(30, 20))
        assertEquals(30, w)
        assertEquals(20, h)
    }
}
