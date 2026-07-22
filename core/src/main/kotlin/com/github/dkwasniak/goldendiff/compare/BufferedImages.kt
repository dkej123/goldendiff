package com.github.dkwasniak.goldendiff.compare

import java.awt.image.BufferedImage

/**
 * Bridge between AWT images and the toolkit-neutral [ArgbImage] used by the comparison logic.
 *
 * Kept apart from [ArgbImage] and [PixelDiff] on purpose: those must stay free of any image type so
 * they can back a Compose/Skia renderer as well. This file is the one place that knows about AWT, and
 * a Skia equivalent can sit next to it without either having to change.
 */

fun BufferedImage.toArgbImage(): ArgbImage {
    val pixels = IntArray(width * height)
    // Bulk read via the raster in one call; the per-pixel getRGB path goes through the ColorModel on
    // every access and is measurably slower on the large goldens this tool routinely opens.
    getRGB(0, 0, width, height, pixels, 0, width)
    return ArgbImage(width, height, pixels)
}

fun ArgbImage.toBufferedImage(): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
        it.setRGB(0, 0, width, height, pixels, 0, width)
    }
