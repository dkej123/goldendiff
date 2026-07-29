package com.github.dkwasniak.goldendiff.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldensPaneLogicTest {

    @Test
    fun `accept all is visible only when there are candidates`() {
        assertTrue(shouldShowAcceptAll(count = 1))
        assertFalse(shouldShowAcceptAll(count = 0))
    }
}
