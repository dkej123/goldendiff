package com.github.dkwasniak.goldendiff.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * `fetchInParallel` backs the Figma reference download: the node-tree fetch (`/nodes`) and the
 * image render (`/images`) are independent, so they run concurrently instead of sequentially.
 * On a cold Figma cache each call is several seconds, so overlapping them roughly halves the wait.
 */
class ParallelFetchTest {

    @Test
    fun `returns both results paired in order`() {
        val (first, second) = fetchInParallel({ "frame" }, { "imageUrl" })

        assertEquals("frame", first)
        assertEquals("imageUrl", second)
    }

    @Test
    fun `runs the two calls concurrently rather than sequentially`() {
        val elapsed = measureTimeMillis {
            fetchInParallel({ Thread.sleep(200) }, { Thread.sleep(200) })
        }

        // Sequential would be ~400ms; overlapped stays well under that.
        assertTrue("expected overlap but took ${elapsed}ms", elapsed < 350)
    }

    @Test
    fun `invokes each supplier exactly once`() {
        val firstCalls = AtomicInteger()
        val secondCalls = AtomicInteger()

        fetchInParallel({ firstCalls.incrementAndGet() }, { secondCalls.incrementAndGet() })

        assertEquals(1, firstCalls.get())
        assertEquals(1, secondCalls.get())
    }

    @Test
    fun `propagates an exception from the second call unwrapped`() {
        try {
            fetchInParallel<String, String>({ "ok" }, { error("boom") })
            fail("expected the second call's exception to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `propagates an exception from the first call`() {
        try {
            fetchInParallel<String, String>({ error("first failed") }, { "ok" })
            fail("expected the first call's exception to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("first failed", e.message)
        }
    }
}
