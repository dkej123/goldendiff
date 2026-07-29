package com.github.dkwasniak.goldendiff.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompletionNotificationTest {
    @Test
    fun `disabled foreground and cancelled completions do not notify`() {
        val scope = CompletionCommandScope.Project("Demo")
        assertNull(CompletionNotificationPolicy.decide(false, false, CompletionCommandKind.VERIFY, scope, CompletionResult.SUCCESS))
        assertNull(CompletionNotificationPolicy.decide(true, true, CompletionCommandKind.VERIFY, scope, CompletionResult.FAILURE))
        assertNull(CompletionNotificationPolicy.decide(true, false, CompletionCommandKind.VERIFY, scope, CompletionResult.CANCELLED))
    }

    @Test
    fun `success and failure messages contain kind scope target and result`() {
        assertEquals(
            CompletionNotification("Verify finished", "Demo · whole project"),
            CompletionNotificationPolicy.decide(
                true,
                false,
                CompletionCommandKind.VERIFY,
                CompletionCommandScope.Project("Demo"),
                CompletionResult.SUCCESS,
            ),
        )
        assertEquals(
            CompletionNotification("Generate failed", "Login.kt · current file"),
            CompletionNotificationPolicy.decide(
                true,
                false,
                CompletionCommandKind.GENERATE,
                CompletionCommandScope.File("Login.kt"),
                CompletionResult.FAILURE,
            ),
        )
    }
}
