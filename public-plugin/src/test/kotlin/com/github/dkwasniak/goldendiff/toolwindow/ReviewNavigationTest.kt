package com.github.dkwasniak.goldendiff.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewNavigationTest {

    /** Files order of every View; B, D and E have changes. */
    private val all = listOf("A.kt", "B.kt", "C.kt", "D.kt", "E.kt")
    private val queue = listOf("B.kt", "D.kt", "E.kt")

    @Test
    fun `steps one queued View forward and backward with wrap-around`() {
        assertEquals("D.kt", steppedQueueViewId(all, queue, "B.kt", 1))
        assertEquals("B.kt", steppedQueueViewId(all, queue, "E.kt", 1))
        assertEquals("E.kt", steppedQueueViewId(all, queue, "B.kt", -1))
    }

    @Test
    fun `an unchanged View opened by hand steps to the nearest queued View in Files order`() {
        assertEquals("D.kt", steppedQueueViewId(all, queue, "C.kt", 1))
        assertEquals("B.kt", steppedQueueViewId(all, queue, "C.kt", -1))
        assertEquals("B.kt", steppedQueueViewId(all, queue, "A.kt", 1))
        assertEquals("E.kt", steppedQueueViewId(all, queue, "A.kt", -1))
    }

    @Test
    fun `without a selection stepping starts at either end of the queue`() {
        assertEquals("B.kt", steppedQueueViewId(all, queue, null, 1))
        assertEquals("E.kt", steppedQueueViewId(all, queue, null, -1))
    }

    @Test
    fun `an empty queue has nowhere to step`() {
        assertNull(steppedQueueViewId(all, emptyList(), "A.kt", 1))
    }

    @Test
    fun `position is one based inside the queue and absent outside it`() {
        assertEquals("2 / 3", queuePositionLabel(queue, "D.kt"))
        assertNull(queuePositionLabel(queue, "C.kt"))
        assertNull(queuePositionLabel(emptyList(), null))
    }
}
