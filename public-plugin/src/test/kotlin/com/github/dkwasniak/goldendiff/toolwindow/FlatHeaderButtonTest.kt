package com.github.dkwasniak.goldendiff.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Test

class FlatHeaderButtonTest {
    @Test
    fun `construction initializes the accessible name without crashing`() {
        val button = FlatHeaderButton("Fit", height = 18, horizontalPadding = 3)

        assertEquals("Fit", button.getAccessibleContext()?.accessibleName)
    }
}
