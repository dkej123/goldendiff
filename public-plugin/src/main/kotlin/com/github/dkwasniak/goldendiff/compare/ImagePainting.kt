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

    /**
     * Geometry lives in core's [ImageLayout] so the Swing and Compose renderers place images
     * identically; these are thin AWT-shaped adapters over it.
     */
    const val FIT = ImageLayout.FIT

    fun renderRect(zoom: Double, imgW: Int, imgH: Int, areaW: Int, areaH: Int): Rectangle =
        ImageLayout.renderRect(zoom, imgW, imgH, areaW, areaH).toAwt()

    fun boundingSize(a: BufferedImage?, b: BufferedImage?): Pair<Int, Int> =
        ImageLayout.boundingSize(a?.width ?: 0, a?.height ?: 0, b?.width ?: 0, b?.height ?: 0)

    fun scaleForBounding(zoom: Double, boundingW: Int, boundingH: Int, areaW: Int, areaH: Int): Double =
        ImageLayout.scaleForBounding(zoom, boundingW, boundingH, areaW, areaH)

    fun bottomRect(imgW: Int, imgH: Int, scale: Double, area: Rectangle): Rectangle =
        ImageLayout.bottomRect(imgW, imgH, scale, area.toLayout()).toAwt()

    fun fitRect(imgW: Int, imgH: Int, areaW: Int, areaH: Int): Rectangle =
        ImageLayout.fitRect(imgW, imgH, areaW, areaH).toAwt()

    private fun IntRect.toAwt(): Rectangle = Rectangle(x, y, width, height)

    private fun Rectangle.toLayout(): IntRect = IntRect(x, y, width, height)

    fun paintCheckerboard(g: Graphics2D, rect: Rectangle) {
        val tile = ImageLayout.CHECKER_TILE
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

    /**
     * Transparent-padding trimming lives in core so the standalone app shares it; these keep the
     * existing call sites working.
     */
    fun trimTransparentBorder(image: BufferedImage?, enabled: Boolean): BufferedImage? =
        TransparentBorder.trim(image, enabled)

    fun trimTransparentBorder(image: BufferedImage): BufferedImage = TransparentBorder.trim(image)

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
