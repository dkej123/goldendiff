package com.github.dkwasniak.goldendiff

import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.telemetry.PluginTelemetryService
import com.github.dkwasniak.goldendiff.toolwindow.CommandExecution
import com.github.dkwasniak.goldendiff.update.PluginUpdateStartupActivity
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginCompatibilityBytecodeTest {

    @Test
    fun `oldest supported IDE does not require new Kotlin coroutine runtime`() {
        assertClassDoesNotReference(PluginUpdateStartupActivity::class.java, "SpillingKt")
    }

    @Test
    fun `plugin uses no verifier-reported internal or experimental platform APIs`() {
        assertClassDoesNotReference(PluginUpdateStartupActivity::class.java, "PluginManagerConfigurable")
        assertClassDoesNotReference(PluginTelemetryService::class.java, "PluginManagerCore")
        assertClassDoesNotReference(CommandExecution::class.java, "WriteIntentReadAction")
        assertClassDoesNotReference(CurrentScreen::class.java, "ActionsKt")
    }

    private fun assertClassDoesNotReference(type: Class<*>, forbiddenName: String) {
        val resourceName = "/${type.name.replace('.', '/')}.class"
        val bytecode = requireNotNull(type.getResourceAsStream(resourceName)) {
            "Missing bytecode resource $resourceName"
        }.use { it.readBytes().toString(Charsets.ISO_8859_1) }

        assertFalse(
            "${type.name} must not reference $forbiddenName",
            forbiddenName in bytecode,
        )
    }
}
