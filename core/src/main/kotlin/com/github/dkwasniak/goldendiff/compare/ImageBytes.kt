package com.github.dkwasniak.goldendiff.compare

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Reading and decoding the working-copy side of a comparison.
 *
 * Bytes and images are kept as separate steps on purpose: comparing raw bytes is how callers cheaply
 * decide "no change vs HEAD" without paying for two PNG decodes.
 *
 * Both operations do I/O — never call them on a UI thread.
 */
object ImageBytes {

    fun workingBytes(file: File): ByteArray? = runCatching { file.readBytes() }.getOrNull()

    fun decode(bytes: ByteArray?): BufferedImage? =
        bytes?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
}
