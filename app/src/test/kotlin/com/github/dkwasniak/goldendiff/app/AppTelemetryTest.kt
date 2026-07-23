package com.github.dkwasniak.goldendiff.app

import org.junit.Assert.assertSame
import org.junit.Test

class AppTelemetryTest {
    @Test
    fun `uncaught exception is forwarded to previous handler after reporting`() {
        var reported: Throwable? = null
        var forwarded: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, throwable -> forwarded = throwable }
        val handler = ChainedUncaughtExceptionHandler({ reported = it }, previous)
        val error = IllegalStateException("controlled")

        handler.uncaughtException(Thread.currentThread(), error)

        assertSame(error, reported)
        assertSame(error, forwarded)
    }
}
