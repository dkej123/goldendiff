package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.watch.PngChangeEvent
import com.github.dkwasniak.goldendiff.watch.PngChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PngDirectoryWatcherTest {

    @Test
    fun `reports modification of an existing png and ignores other files`() {
        val root = Files.createTempDirectory("golden-diff-watch").toFile()
        val png = root.resolve("screen.png").apply { writeBytes(byteArrayOf(1)) }
        val events = Collections.synchronizedList(mutableListOf<PngChangeEvent>())
        val latch = CountDownLatch(1)

        PngDirectoryWatcher(listOf(root)) { batch, _ ->
            events += batch
            if (batch.any { it.file == png && it.kind == PngChangeKind.MODIFIED }) latch.countDown()
        }.use {
            root.resolve("notes.txt").writeText("ignored")
            png.writeBytes(byteArrayOf(2))
            assertTrue("modified PNG was not reported", latch.await(5, TimeUnit.SECONDS))
        }
        assertTrue(events.none { it.file.extension.equals("txt", ignoreCase = true) })
    }

    @Test
    fun `reports png created immediately inside a new directory`() {
        val root = Files.createTempDirectory("golden-diff-watch-tree").toFile()
        val latch = CountDownLatch(1)

        PngDirectoryWatcher(listOf(root)) { batch, _ ->
            if (batch.any { it.file.name == "new.png" }) latch.countDown()
        }.use {
            root.resolve("new-output").mkdirs()
            root.resolve("new-output/new.png").writeBytes(byteArrayOf(1, 2, 3))
            assertTrue("PNG in a newly created directory was not reported", latch.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `png state detects a fast undo even when replacement has the same size`() {
        val root = Files.createTempDirectory("golden-diff-watch-state").toFile()
        val png = root.resolve("screen.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val accepted = pngFileState(png)

        png.writeBytes(byteArrayOf(4, 3, 2, 1))

        assertTrue(accepted != pngFileState(png))
    }

    @Test
    fun `png state represents a consumed generated output`() {
        val root = Files.createTempDirectory("golden-diff-watch-deleted").toFile()
        val png = root.resolve("screen_actual.png").apply { writeBytes(byteArrayOf(1)) }
        png.delete()

        assertEquals(PngFileState(exists = false, size = 0L, contentHash = 0), pngFileState(png))
    }
}
