package com.github.dkwasniak.goldendiff.compare

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Shared helpers so the three comparison modes align images identically. */
object ImagePainting {

    private val CHECKER_LIGHT = JBColor(Color(0xFFFFFF), Color(0x5A5A5A))
    private val CHECKER_DARK = JBColor(Color(0xE0E0E0), Color(0x4A4A4A))

    /** Sentinel zoom value meaning "fit to the available area". */
    const val FIT = 0.0

    /**
     * Draw rectangle for an image given the current zoom: fit-to-area when [zoom] == [FIT],
     * otherwise the image scaled by the explicit factor and centered in the component.
     */
    fun renderRect(zoom: Double, imgW: Int, imgH: Int, areaW: Int, areaH: Int): Rectangle {
        if (imgW <= 0 || imgH <= 0 || areaW <= 0 || areaH <= 0) return Rectangle(0, 0, 0, 0)
        if (zoom == FIT) return fitRect(imgW, imgH, areaW, areaH)
        val w = (imgW * zoom).toInt().coerceAtLeast(1)
        val h = (imgH * zoom).toInt().coerceAtLeast(1)
        return Rectangle(maxOf(0, (areaW - w) / 2), maxOf(0, (areaH - h) / 2), w, h)
    }

    /**
     * Bounding size that contains both images: it preserves the true relative scale of the two
     * images. Used by every compare mode so a pure height difference is not mistaken for a width
     * difference — both images are drawn into the same bounding box preserving their own aspect
     * ratio, so a shorter image keeps its real width instead of being stretched to fill.
     */
    fun boundingSize(a: BufferedImage?, b: BufferedImage?): Pair<Int, Int> {
        val w = maxOf(a?.width ?: 0, b?.width ?: 0)
        val h = maxOf(a?.height ?: 0, b?.height ?: 0)
        return w to h
    }

    /** Uniform scale that fits the [boundingW]x[boundingH] box into the area at the given [zoom]. */
    fun scaleForBounding(zoom: Double, boundingW: Int, boundingH: Int, areaW: Int, areaH: Int): Double {
        if (boundingW <= 0 || boundingH <= 0 || areaW <= 0 || areaH <= 0) return 1.0
        val rect = renderRect(zoom, boundingW, boundingH, areaW, areaH)
        return rect.width.toDouble() / boundingW
    }

    /**
     * Draw rectangle for a single image at [scale], horizontally centered and anchored to the bottom
     * of [area]. Bottom-anchoring keeps bottom-aligned content (e.g. bottom sheets) on a shared
     * baseline across HEAD / working copy, and a common [scale] preserves the true relative sizes.
     */
    fun bottomRect(imgW: Int, imgH: Int, scale: Double, area: Rectangle): Rectangle {
        if (imgW <= 0 || imgH <= 0) return Rectangle(area.x, area.y, 0, 0)
        val w = (imgW * scale).toInt().coerceAtLeast(1)
        val h = (imgH * scale).toInt().coerceAtLeast(1)
        return Rectangle(
            area.x + (area.width - w) / 2,
            area.y + (area.height - h).coerceAtLeast(0),
            w,
            h,
        )
    }

    /** Rectangle inside [areaW]x[areaH] that fits [imgW]x[imgH] preserving aspect ratio, centered. */
    fun fitRect(imgW: Int, imgH: Int, areaW: Int, areaH: Int): Rectangle {
        if (imgW <= 0 || imgH <= 0 || areaW <= 0 || areaH <= 0) return Rectangle(0, 0, 0, 0)
        val scale = minOf(areaW.toDouble() / imgW, areaH.toDouble() / imgH).coerceAtMost(1.0)
        val w = (imgW * scale).toInt().coerceAtLeast(1)
        val h = (imgH * scale).toInt().coerceAtLeast(1)
        return Rectangle((areaW - w) / 2, (areaH - h) / 2, w, h)
    }

    fun paintCheckerboard(g: Graphics2D, rect: Rectangle) {
        val tile = 8
        var y = rect.y
        var row = 0
        while (y < rect.y + rect.height) {
            var x = rect.x
            var col = 0
            while (x < rect.x + rect.width) {
                g.color = if ((row + col) % 2 == 0) CHECKER_LIGHT else CHECKER_DARK
                val w = minOf(tile, rect.x + rect.width - x)
                val h = minOf(tile, rect.y + rect.height - y)
                g.fillRect(x, y, w, h)
                x += tile
                col++
            }
            y += tile
            row++
        }
    }

    /**
     * Draw [image] to fill [rect] exactly. Callers always pass a rect already at the image's aspect
     * ratio (from [renderRect] / [bottomRect]), so filling it directly avoids a second aspect-fit whose
     * independent rounding could leave the image ~1px short of [rect] — which let the checkerboard,
     * painted on the full [rect], peek out as a thin sliver on one edge.
     */
    fun drawImage(g: Graphics2D, image: BufferedImage, rect: Rectangle) {
        if (rect.width <= 0 || rect.height <= 0) return
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, rect.x, rect.y, rect.width, rect.height, null)
    }

    /** [trimTransparentBorder] applied only when [enabled]; a no-op (and null-safe) otherwise. */
    fun trimTransparentBorder(image: BufferedImage?, enabled: Boolean): BufferedImage? =
        if (enabled && image != null) trimTransparentBorder(image) else image

    /**
     * Crop away fully-transparent (alpha == 0) borders on every side so an image with transparent
     * padding is shown tight to its actual content. Returns the original image when it has no alpha
     * channel or no transparent border to trim, and null when the content is entirely transparent.
     */
    fun trimTransparentBorder(image: BufferedImage): BufferedImage {
        if (!image.colorModel.hasAlpha()) return image
        val w = image.width
        val h = image.height
        var top = 0
        var bottom = h - 1
        var left = 0
        var right = w - 1
        val row = IntArray(w)

        fun rowHasContent(y: Int): Boolean {
            image.getRGB(0, y, w, 1, row, 0, w)
            return row.any { it ushr 24 != 0 }
        }

        fun columnHasContent(x: Int): Boolean {
            for (y in top..bottom) if (image.getRGB(x, y) ushr 24 != 0) return true
            return false
        }

        while (top <= bottom && !rowHasContent(top)) top++
        if (top > bottom) return image // fully transparent — leave as-is rather than produce a 0-size crop
        while (bottom > top && !rowHasContent(bottom)) bottom--
        while (left < right && !columnHasContent(left)) left++
        while (right > left && !columnHasContent(right)) right--

        if (top == 0 && left == 0 && right == w - 1 && bottom == h - 1) return image
        val cropW = right - left + 1
        val cropH = bottom - top + 1
        val copy = BufferedImage(cropW, cropH, BufferedImage.TYPE_INT_ARGB)
        copy.createGraphics().apply {
            drawImage(image.getSubimage(left, top, cropW, cropH), 0, 0, null)
            dispose()
        }
        return copy
    }

    fun ellipsize(text: String, metrics: FontMetrics, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (metrics.stringWidth(text) <= maxWidth) return text
        val ellipsis = "..."
        if (metrics.stringWidth(ellipsis) > maxWidth) return ""
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }
}
