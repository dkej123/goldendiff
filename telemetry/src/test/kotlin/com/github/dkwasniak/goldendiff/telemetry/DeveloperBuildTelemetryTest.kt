package com.github.dkwasniak.goldendiff.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * A developer build runs the [TelemetryClient] with an empty effective consent (the host layer
 * substitutes it for the saved preferences). This pins the guarantee that empty consent produces no
 * backend, no installation id, no events, no spans and no exception reports — even when a caller
 * still tries to emit them.
 */
class DeveloperBuildTelemetryTest {

    private val environment = TelemetryEnvironment(
        surface = TelemetrySurface.DESKTOP,
        releaseChannel = ReleaseChannel.DEV,
        appVersion = "1.5.0-beta.4",
    )

    @Test fun `empty consent never builds a backend`() {
        val factory = CountingFactory()
        TelemetryClient(environment, MapStore(), factory, initialConsent = TelemetryConsent())
        assertEquals(0, factory.created)
    }

    @Test fun `events spans and exceptions are dropped and no installation id is stored`() {
        val factory = CountingFactory()
        val store = MapStore()
        val client = TelemetryClient(environment, store, factory, initialConsent = TelemetryConsent())

        assertFalse(client.event("product.feature_used", mapOf("feature" to "update_homebrew")))
        assertSame(NoOpSpan, client.startSpan("golden.scan", mapOf("scope" to "current_file")))
        client.captureException(IllegalStateException("boom"), "test")
        client.installationFirstSeen()
        client.sessionStarted("app_launch", projectRestored = false)

        assertEquals(0, factory.created)
        assertEquals(0, factory.backend.events.size)
        assertEquals(0, factory.backend.exceptions.size)
        // The installation id is only minted when something is actually emitted; offline it stays absent.
        assertNull(store.get("telemetry.installation_id"))
    }

    @Test fun `diagnostics listener sees events spans and exceptions even when consent is empty`() {
        val logged = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val client = TelemetryClient(
            environment,
            MapStore(),
            CountingFactory(),
            initialConsent = TelemetryConsent(),
            diagnostics = { category, detail -> logged += category to detail },
        )

        client.event("product.feature_used", mapOf("feature" to "update_homebrew"))
        client.startSpan("golden.scan", mapOf("scope" to "current_file"))
        client.captureException(IllegalStateException("boom"), "test")

        val categories = logged.map { it.first }
        assertTrue("analytics" in categories)
        assertTrue("span" in categories)
        assertTrue("exception" in categories)
        // The offline suppression is visible in the detail, so a developer sees what would have been sent.
        assertTrue(logged.any { it.first == "analytics" && it.second.contains("suppressed") })
    }

    private class MapStore : TelemetryStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class CountingFactory : TelemetryBackendFactory {
        var created = 0
        val backend = CountingBackend()
        override fun create(consent: TelemetryConsent): TelemetryBackend {
            created++
            return backend
        }
    }

    private class CountingBackend : TelemetryBackend {
        val events = Collections.synchronizedList(mutableListOf<ProductEvent>())
        val exceptions = Collections.synchronizedList(mutableListOf<Pair<Throwable, String>>())
        override fun capture(event: ProductEvent) {
            events += event
        }
        override fun captureException(throwable: Throwable, fingerprint: String) {
            exceptions += throwable to fingerprint
        }
        override fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan = NoOpSpan
        override fun clear() = Unit
        override fun close() = Unit
    }
}
