package com.github.dkwasniak.goldendiff.compare

/** A rectangle in device pixels. Replaces java.awt.Rectangle so the geometry stays toolkit-free. */
data class IntRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
    }
}

/**
 * Where each image goes on screen, shared by every comparison mode.
 *
 * All of it is pure arithmetic, which is the point: the Swing renderer and the Compose renderer must
 * place images identically, or the same pair of goldens would appear to line up differently depending
 * on which one you opened.
 */
object ImageLayout {

    /** Sentinel zoom value meaning "fit to the available area". */
    const val FIT = 0.0

    /**
     * Draw rectangle for an image given the current zoom: fit-to-area when [zoom] == [FIT], otherwise
     * the image scaled by the explicit factor and centered in the area.
     */
    fun renderRect(zoom: Double, imgW: Int, imgH: Int, areaW: Int, areaH: Int): IntRect {
        if (imgW <= 0 || imgH <= 0 || areaW <= 0 || areaH <= 0) return IntRect.EMPTY
        if (zoom == FIT) return fitRect(imgW, imgH, areaW, areaH)
        val w = (imgW * zoom).toInt().coerceAtLeast(1)
        val h = (imgH * zoom).toInt().coerceAtLeast(1)
        return IntRect(maxOf(0, (areaW - w) / 2), maxOf(0, (areaH - h) / 2), w, h)
    }

    /**
     * Bounding size that contains both images, preserving their true relative scale.
     *
     * Every compare mode draws into this box so a pure height difference is not mistaken for a width
     * difference — a shorter image keeps its real width instead of being stretched to fill.
     */
    fun boundingSize(aW: Int, aH: Int, bW: Int, bH: Int): Pair<Int, Int> =
        maxOf(aW, bW) to maxOf(aH, bH)

    /** Uniform scale that fits the [boundingW]x[boundingH] box into the area at the given [zoom]. */
    fun scaleForBounding(zoom: Double, boundingW: Int, boundingH: Int, areaW: Int, areaH: Int): Double {
        if (boundingW <= 0 || boundingH <= 0 || areaW <= 0 || areaH <= 0) return 1.0
        return renderRect(zoom, boundingW, boundingH, areaW, areaH).width.toDouble() / boundingW
    }

    /**
     * Draw rectangle for a single image at [scale], horizontally centered and anchored to the bottom
     * of [area].
     *
     * Bottom-anchoring keeps bottom-aligned content (bottom sheets, nav bars) on a shared baseline
     * across HEAD and the working copy, and the shared [scale] preserves true relative sizes.
     */
    fun bottomRect(imgW: Int, imgH: Int, scale: Double, area: IntRect): IntRect {
        if (imgW <= 0 || imgH <= 0) return IntRect(area.x, area.y, 0, 0)
        val w = (imgW * scale).toInt().coerceAtLeast(1)
        val h = (imgH * scale).toInt().coerceAtLeast(1)
        return IntRect(
            area.x + (area.width - w) / 2,
            area.y + (area.height - h).coerceAtLeast(0),
            w,
            h,
        )
    }

    /** Rectangle inside [areaW]x[areaH] that fits [imgW]x[imgH] preserving aspect ratio, centered. */
    fun fitRect(imgW: Int, imgH: Int, areaW: Int, areaH: Int): IntRect {
        if (imgW <= 0 || imgH <= 0 || areaW <= 0 || areaH <= 0) return IntRect.EMPTY
        val scale = minOf(areaW.toDouble() / imgW, areaH.toDouble() / imgH).coerceAtMost(1.0)
        val w = (imgW * scale).toInt().coerceAtLeast(1)
        val h = (imgH * scale).toInt().coerceAtLeast(1)
        return IntRect((areaW - w) / 2, (areaH - h) / 2, w, h)
    }

    /** Checkerboard tile size, in pixels, used behind transparent image areas. */
    const val CHECKER_TILE = 8
}
