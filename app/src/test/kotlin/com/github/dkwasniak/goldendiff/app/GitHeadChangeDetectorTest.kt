package com.github.dkwasniak.goldendiff.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHeadChangeDetectorTest {
    @Test
    fun `reports only a real revision change and ignores unavailable reads`() {
        val revisions = ArrayDeque(listOf("first", "first", null, "second", "second"))
        val detector = GitHeadChangeDetector { revisions.removeFirst() }

        assertFalse(detector.poll())
        assertFalse(detector.poll())
        assertFalse(detector.poll())
        assertTrue(detector.poll())
        assertFalse(detector.poll())
    }
}
