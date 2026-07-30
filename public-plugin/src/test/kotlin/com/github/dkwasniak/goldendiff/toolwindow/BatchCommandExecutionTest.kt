package com.github.dkwasniak.goldendiff.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCommandExecutionTest {

    @Test
    fun `all successful commands produce success`() {
        val aggregator = BatchCommandAggregator(listOf("one", "two"))

        assertTrue(aggregator.record(0, 0))
        assertTrue(aggregator.record(1, 0))

        val outcome = aggregator.outcome()
        assertTrue(outcome is BatchCommandOutcome.Success)
        assertEquals(listOf(0, 0), outcome.results.map { it.exitCode })
    }

    @Test
    fun `nonzero command is recorded and does not prevent the next command`() {
        val aggregator = BatchCommandAggregator(listOf("one", "two", "three"))

        assertTrue(aggregator.record(0, 7))
        assertTrue(aggregator.record(1, 0))
        assertTrue(aggregator.record(2, 3))

        val outcome = aggregator.outcome()
        assertTrue(outcome is BatchCommandOutcome.PartialFailure)
        assertEquals(listOf(7, 0, 3), outcome.results.map { it.exitCode })
        assertEquals(0, (outcome as BatchCommandOutcome.PartialFailure).incompleteCommands)
    }

    @Test
    fun `all failed commands produce failure`() {
        val aggregator = BatchCommandAggregator(listOf("one", "two"))

        aggregator.record(0, 1)
        aggregator.record(1, 2)

        assertTrue(aggregator.outcome() is BatchCommandOutcome.Failure)
    }

    @Test
    fun `premature termination is a failure or partial failure based on completed work`() {
        val noSuccess = BatchCommandAggregator(listOf("one", "two"))
        noSuccess.record(0, 1)
        val failure = noSuccess.outcome() as BatchCommandOutcome.Failure
        assertEquals(1, failure.incompleteCommands)

        val someSuccess = BatchCommandAggregator(listOf("one", "two"))
        someSuccess.record(0, 0)
        val partial = someSuccess.outcome() as BatchCommandOutcome.PartialFailure
        assertEquals(1, partial.incompleteCommands)
    }

    @Test
    fun `cancel prevents further records and has a distinct outcome`() {
        val aggregator = BatchCommandAggregator(listOf("one", "two"))
        aggregator.record(0, 0)

        aggregator.cancel()

        assertFalse(aggregator.record(1, 0))
        val outcome = aggregator.outcome()
        assertTrue(outcome is BatchCommandOutcome.Cancelled)
        assertEquals(listOf("one"), outcome.results.map { it.command })
    }

    @Test
    fun `out of order or duplicate exit markers are ignored`() {
        val aggregator = BatchCommandAggregator(listOf("one", "two"))

        assertFalse(aggregator.record(1, 0))
        assertTrue(aggregator.record(0, 0))
        assertFalse(aggregator.record(0, 0))
        assertTrue(aggregator.record(1, 0))
    }

    @Test
    fun `marker parser handles split chunks and unrelated output`() {
        val results = mutableListOf<Pair<Int, Int>>()
        val parser = ExitMarkerParser("__MARKER__") { index, exitCode -> results += index to exitCode }

        parser.accept("test output\n__MARK")
        parser.accept("ER__:0:1\r\nmore output\n__MARKER__:1:")
        parser.accept("0")
        parser.finish()

        assertEquals(listOf(0 to 1, 1 to 0), results)
    }

    @Test
    fun `posix wrapper runs every command without fail-fast chaining`() {
        val wrapper = BatchCommandExecution.shellWrapper(listOf("first", "second"), "__MARKER__")

        assertTrue(wrapper.contains("first"))
        assertTrue(wrapper.contains("second"))
        assertTrue(wrapper.contains("__MARKER__:0:"))
        assertTrue(wrapper.contains("__MARKER__:1:"))
        assertFalse(wrapper.contains("&&"))
    }
}
