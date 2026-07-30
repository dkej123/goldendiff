package com.github.dkwasniak.goldendiff.toolwindow

import com.github.dkwasniak.goldendiff.telemetry.NoOpSpan
import com.github.dkwasniak.goldendiff.telemetry.ProductEvent
import com.github.dkwasniak.goldendiff.telemetry.ReleaseChannel
import com.github.dkwasniak.goldendiff.telemetry.TelemetryBackend
import com.github.dkwasniak.goldendiff.telemetry.TelemetryConsent
import com.github.dkwasniak.goldendiff.telemetry.TelemetryClient
import com.github.dkwasniak.goldendiff.telemetry.TelemetryEnvironment
import com.github.dkwasniak.goldendiff.telemetry.TelemetrySpan
import com.github.dkwasniak.goldendiff.telemetry.TelemetryStore
import com.github.dkwasniak.goldendiff.telemetry.TelemetrySurface
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginTelemetryReporterTest : BasePlatformTestCase() {

    fun testLifecycleAndEventsDelegateWithoutProjectDataInPayload() {
        val backend = RecordingBackend()
        val store = MemoryStore()
        val client = TelemetryClient(
            environment = TelemetryEnvironment(
                surface = TelemetrySurface.PLUGIN,
                releaseChannel = ReleaseChannel.BETA,
                appVersion = "test",
                ideProduct = "intellij_idea",
                ideBuildMajor = "241",
            ),
            store = store,
            backendFactory = { backend },
            initialConsent = TelemetryConsent(analytics = true),
        )
        var opened = 0
        var closed = 0
        val reporter = PluginTelemetryReporter(
            client = client,
            openPanel = { opened++ },
            closePanel = { closed++ },
        )

        reporter.panelOpened(project)
        reporter.event("product.feature_used", mapOf("feature" to "detached_comparison"))
        reporter.sourceFileSelected("/private/project/Secret.kt", "project_tree", "kotlin", false)
        reporter.panelClosed()

        assertEquals(1, opened)
        assertEquals(1, closed)
        assertEquals(2, backend.events.size)
        assertFalse(backend.events.any { event ->
            event.properties.values.any { "Secret.kt" in it || "/private/project" in it }
        })
    }

    private class MemoryStore : TelemetryStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class RecordingBackend : TelemetryBackend {
        val events = mutableListOf<ProductEvent>()
        override fun capture(event: ProductEvent) {
            events += event
        }
        override fun captureException(throwable: Throwable, fingerprint: String) = Unit
        override fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan = NoOpSpan
        override fun clear() = Unit
        override fun close() = Unit
    }
}
