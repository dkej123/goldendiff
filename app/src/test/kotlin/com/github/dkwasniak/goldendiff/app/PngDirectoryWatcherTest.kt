package com.github.dkwasniak.goldendiff.app

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
        val events = Collections.synchronizedList(mutableListOf<PngDirectoryWatcher.Event>())
        val latch = CountDownLatch(1)

        PngDirectoryWatcher(listOf(root)) { batch, _ ->
            events += batch
            if (batch.any { it.file == png && it.kind == PngDirectoryWatcher.Kind.MODIFIED }) latch.countDown()
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
}
