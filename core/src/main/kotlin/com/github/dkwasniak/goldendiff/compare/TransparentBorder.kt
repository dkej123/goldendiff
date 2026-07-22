package com.github.dkwasniak.goldendiff.compare

import java.awt.image.BufferedImage

/**
 * Cropping fully transparent padding from around image content.
 *
 * Screenshot tools routinely pad output with transparency, which makes two goldens of the same screen
 * look different sizes when they are not. Trimming is opt-in because the padding itself is sometimes
 * the thing being reviewed.
 */
object TransparentBorder {

    /** [trim] applied only when [enabled]; a null-safe no-op otherwise. */
    fun trim(image: BufferedImage?, enabled: Boolean): BufferedImage? =
        if (enabled && image != null) trim(image) else image

    /**
     * Crops fully transparent (alpha == 0) borders on every side.
     *
     * Returns the original image when it has no alpha channel or nothing to trim. An entirely
     * transparent image is also returned unchanged rather than cropped to zero size, which would
     * otherwise turn "an empty screenshot" into "a broken image".
     */
    fun trim(image: BufferedImage): BufferedImage {
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
        if (top > bottom) return image
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
}
