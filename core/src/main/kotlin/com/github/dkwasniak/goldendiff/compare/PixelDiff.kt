package com.github.dkwasniak.goldendiff.compare

/**
 * Pure pixel-difference computation shared by the diff compare mode (and unit-tested in isolation).
 *
 * The two images are compared on a canvas the size of their bounding box (the larger width and the
 * larger height), each placed at its native resolution, horizontally centered and anchored to the
 * bottom — the same placement the visual compare modes use, so bottom-aligned content stays on a
 * shared baseline instead of being squashed to the shorter image's height. Pixels covered by only
 * one image count as changed; areas covered by neither stay transparent. When only one image exists
 * the result uses that image's dimensions. Unchanged pixels are drawn as a dimmed grayscale so they
 * stay as visible context; changed pixels are highlighted in magenta whose opacity grows with the
 * magnitude of the change.
 *
 * Works on [ArgbImage] rather than any toolkit image type, so the same code backs both the Swing and
 * the Compose renderer; callers convert at the edges (see `BufferedImages.kt`).
 */
object PixelDiff {

    private const val HIGHLIGHT_RGB = 0xFF00FF // magenta
    private const val MIN_HIGHLIGHT_ALPHA = 120
    private const val DIM_ALPHA = 70

    data class Result(val image: ArgbImage, val changedPixels: Int, val totalPixels: Int) {
        val changedRatio: Double
            get() = if (totalPixels == 0) 0.0 else changedPixels.toDouble() / totalPixels
    }

    /** Where an image sits on the shared canvas. Replaces java.awt.Rectangle to keep this file toolkit-free. */
    private data class Placement(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(px: Int, py: Int): Boolean =
            px >= x && py >= y && px < x + width && py < y + height
    }

    /** Returns null when there is nothing to compare (both images null / empty). */
    fun compute(old: ArgbImage?, new: ArgbImage?): Result? {
        val width = maxOf(old?.width ?: 0, new?.width ?: 0)
        val height = maxOf(old?.height ?: 0, new?.height ?: 0)
        if (width <= 0 || height <= 0) return null

        val oldRect = old?.let { bottomCenteredRect(it, width, height) }
        val newRect = new?.let { bottomCenteredRect(it, width, height) }

        val out = ArgbImage.empty(width, height)
        var changed = 0
        var total = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val o = pixelAt(old, oldRect, x, y)
                val n = pixelAt(new, newRect, x, y)
                if (o == null && n == null) continue // outside both images: leave transparent
                total++
                if (o != null && n != null && o == n) {
                    out.setRgb(x, y, dim(o))
                } else {
                    changed++
                    out.setRgb(x, y, highlight(o, n))
                }
            }
        }
        return Result(out, changed, total)
    }

    /** Native-size placement of [image] on a [w]x[h] canvas: horizontally centered, bottom-anchored. */
    private fun bottomCenteredRect(image: ArgbImage, w: Int, h: Int): Placement =
        Placement((w - image.width) / 2, h - image.height, image.width, image.height)

    private fun pixelAt(image: ArgbImage?, rect: Placement?, x: Int, y: Int): Int? {
        if (image == null || rect == null || !rect.contains(x, y)) return null
        return image.getRgb(x - rect.x, y - rect.y)
    }

    private fun dim(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
        return (DIM_ALPHA shl 24) or (luminance shl 16) or (luminance shl 8) or luminance
    }

    private fun highlight(o: Int?, n: Int?): Int {
        val magnitude = if (o == null || n == null) 255 else channelDistance(o, n)
        val alpha = (MIN_HIGHLIGHT_ALPHA + magnitude * (255 - MIN_HIGHLIGHT_ALPHA) / 255)
            .coerceIn(MIN_HIGHLIGHT_ALPHA, 255)
        return (alpha shl 24) or HIGHLIGHT_RGB
    }

    /** Largest absolute per-channel difference (0..255) across A, R, G, B. */
    private fun channelDistance(a: Int, b: Int): Int {
        var max = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val d = kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
            if (d > max) max = d
        }
        return max
    }
}
