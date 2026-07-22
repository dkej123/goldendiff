package com.github.dkwasniak.goldendiff.compare

/**
 * A decoded image as a flat row-major array of packed ARGB pixels.
 *
 * This is the currency the core comparison code deals in. It exists so that pixel work does not have
 * to name a toolkit image type: the IDE plugin renders through Swing and the standalone app through
 * Compose/Skia, and forcing either one's image class into the shared logic would tie the core to a UI
 * stack. Converting at the edges also happens to be faster than the alternative — `BufferedImage.getRGB`
 * per pixel goes through a `ColorModel` on every call, while this reads a plain array.
 */
class ArgbImage(
    val width: Int,
    val height: Int,
    /** Row-major, `width * height` packed ARGB values. */
    val pixels: IntArray,
) {
    init {
        require(pixels.size == width * height) {
            "pixels.size=${pixels.size} does not match ${width}x$height"
        }
    }

    fun getRgb(x: Int, y: Int): Int = pixels[y * width + x]

    fun setRgb(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    companion object {
        fun empty(width: Int, height: Int): ArgbImage =
            ArgbImage(width, height, IntArray(width * height))
    }
}
