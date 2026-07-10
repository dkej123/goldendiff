package com.github.dkwasniak.goldendiff.compare

import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Pure pixel-difference computation shared by the diff compare mode (and unit-tested in isolation).
 *
 * When both images exist but differ in size, both are downscaled to the smaller shared dimensions
 * before comparison so size-only differences do not flood the heatmap. When only one image exists,
 * the result uses that image's dimensions. Unchanged pixels are drawn as a dimmed grayscale so they
 * stay as visible context; changed pixels are highlighted in magenta whose opacity grows with the
 * magnitude of the change.
 */
object PixelDiff {

    private const val HIGHLIGHT_RGB = 0xFF00FF // magenta
    private const val MIN_HIGHLIGHT_ALPHA = 120
    private const val DIM_ALPHA = 70

    data class Result(val image: BufferedImage, val changedPixels: Int, val totalPixels: Int) {
        val changedRatio: Double
            get() = if (totalPixels == 0) 0.0 else changedPixels.toDouble() / totalPixels
    }

    /** Returns null when there is nothing to compare (both images null / empty). */
    fun compute(old: BufferedImage?, new: BufferedImage?): Result? {
        val width = if (old != null && new != null) minOf(old.width, new.width) else maxOf(old?.width ?: 0, new?.width ?: 0)
        val height = if (old != null && new != null) minOf(old.height, new.height) else maxOf(old?.height ?: 0, new?.height ?: 0)
        if (width <= 0 || height <= 0) return null

        val scaledOld = old?.scaledToIfNeeded(width, height)
        val scaledNew = new?.scaledToIfNeeded(width, height)

        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var changed = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val o = scaledOld?.getRGB(x, y)
                val n = scaledNew?.getRGB(x, y)
                if (o != null && n != null && o == n) {
                    out.setRGB(x, y, dim(o))
                } else {
                    changed++
                    out.setRGB(x, y, highlight(o, n))
                }
            }
        }
        return Result(out, changed, width * height)
    }

    private fun BufferedImage.scaledToIfNeeded(w: Int, h: Int): BufferedImage {
        if (width == w && height == h) return this
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(this, 0, 0, w, h, null)
        g.dispose()
        return out
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
