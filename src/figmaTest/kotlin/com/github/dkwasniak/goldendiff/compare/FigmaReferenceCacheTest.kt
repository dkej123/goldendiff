package com.github.dkwasniak.goldendiff.compare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FigmaReferenceCacheTest {

    @Test
    fun `prune keeps newest files within byte limit`() {
        val dir = Files.createTempDirectory("figma-cache").toFile()
        try {
            val oldest = dir.resolve("oldest.png").apply { writeBytes(ByteArray(6)) }
            val middle = dir.resolve("middle.png").apply { writeBytes(ByteArray(6)) }
            val newest = dir.resolve("newest.png").apply { writeBytes(ByteArray(6)) }
            oldest.setLastModified(1)
            middle.setLastModified(2)
            newest.setLastModified(3)

            FigmaReferenceCache.prune(dir, maxBytes = 12)

            assertFalse(oldest.exists())
            assertTrue(middle.exists())
            assertTrue(newest.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
