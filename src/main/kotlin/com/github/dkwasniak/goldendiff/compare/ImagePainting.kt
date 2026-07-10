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

    /** Logical size shared by both images so overlays line up (max of the two dimensions). */
    fun commonSize(a: BufferedImage?, b: BufferedImage?): Pair<Int, Int> {
        val w = maxOf(a?.width ?: 0, b?.width ?: 0)
        val h = maxOf(a?.height ?: 0, b?.height ?: 0)
        return w to h
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

    fun drawImage(g: Graphics2D, image: BufferedImage, rect: Rectangle) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, rect.x, rect.y, rect.width, rect.height, null)
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
