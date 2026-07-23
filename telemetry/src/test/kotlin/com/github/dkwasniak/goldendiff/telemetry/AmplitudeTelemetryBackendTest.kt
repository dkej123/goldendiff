package com.github.dkwasniak.goldendiff.telemetry

import com.amplitude.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AmplitudeTelemetryBackendTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `maps stable identity session and reviewed properties to official SDK event`() {
        val event = sampleEvent().toAmplitudeEvent(clock.millis())

        assertEquals("product.feature_used", event.eventType)
        assertEquals("9d13bce0-f581-4dc5-a0de-07968b78fd19", event.userId)
        assertEquals(event.userId, event.deviceId)
        assertEquals(1784808000000, event.timestamp)
        assertEquals(1784808000000, event.sessionId)
        assertEquals("1.5.0-beta.1", event.appVersion)
        assertEquals("desktop", event.platform)
        assertEquals("macos", event.osName)
        assertEquals("15", event.osVersion)
        assertEquals("quick_open", event.eventProperties.getString("feature"))
        assertEquals("desktop", event.userProperties.getString("surface"))
        assertFalse(event.eventProperties.has("user.id"))
        assertNotEquals("", event.insertId)
    }

    @Test
    fun `normal close flushes while consent clear discards buffered events`() {
        val normalClient = RecordingAmplitudeSdkClient()
        AmplitudeTelemetryBackend(normalClient, clock).apply {
            capture(sampleEvent())
            close()
        }
        assertEquals(1, normalClient.events.size)
        assertEquals(1, normalClient.flushCount)
        assertEquals(1, normalClient.shutdownCount)

        val optedOutClient = RecordingAmplitudeSdkClient()
        AmplitudeTelemetryBackend(optedOutClient, clock).apply {
            capture(sampleEvent())
            clear()
            close()
        }
        assertEquals(1, optedOutClient.events.size)
        assertEquals(0, optedOutClient.flushCount)
        assertEquals(1, optedOutClient.shutdownCount)
    }

    @Test
    fun `keeps Amplitude session id when consent backend is recreated`() {
        val sessionId = "a51ba37b-6768-46ba-92ea-c34382bd432b"
        val first = sampleEvent(sessionId).toAmplitudeEvent(clock.millis())
        val second = sampleEvent(sessionId).toAmplitudeEvent(
            Clock.offset(clock, Duration.ofMinutes(5)).millis(),
        )

        assertEquals(first.sessionId, second.sessionId)
        assertNotEquals(first.timestamp, second.timestamp)
    }

    @Test
    fun `uses the Amplitude EU ingestion endpoint`() {
        assertEquals(
            "https://api.eu.amplitude.com/2/httpapi",
            AmplitudeTelemetryBackend.EU_ENDPOINT,
        )
    }

    @Test
    fun `router keeps analytics separate from diagnostics and performance`() {
        val analytics = RoutingRecordingBackend()
        val monitoring = RoutingRecordingBackend()
        val backend = GoldenDiffTelemetryBackend(analytics, monitoring)

        backend.capture(sampleEvent())
        backend.captureException(IllegalStateException("boom"), "fingerprint")
        backend.startSpan("golden.scan", mapOf("scope" to "current_file"))

        assertEquals(1, analytics.events.size)
        assertTrue(analytics.exceptions.isEmpty())
        assertTrue(analytics.spans.isEmpty())
        assertTrue(monitoring.events.isEmpty())
        assertEquals(1, monitoring.exceptions.size)
        assertEquals(listOf("golden.scan"), monitoring.spans)
    }

    private fun sampleEvent(
        sessionId: String = "c11774c9-bc47-48aa-aab4-c5af91688a84",
    ) = ProductEvent(
        name = "product.feature_used",
        properties = mapOf(
            "schema_version" to "1",
            "surface" to "desktop",
            "release_channel" to "beta",
            "app_version" to "1.5.0-beta.1",
            "os_family" to "macos",
            "os_major" to "15",
            "jvm_major" to "21",
            "session_id" to sessionId,
            "user.id" to "9d13bce0-f581-4dc5-a0de-07968b78fd19",
            "feature" to "quick_open",
        ),
    )
}

private class RecordingAmplitudeSdkClient : AmplitudeSdkClient {
    val events = mutableListOf<Event>()
    var flushCount = 0
    var shutdownCount = 0

    override fun logEvent(event: Event) {
        events += event
    }

    override fun flushEvents() {
        flushCount++
    }

    override fun shutdown() {
        shutdownCount++
    }
}

private class RoutingRecordingBackend : TelemetryBackend {
    val events = mutableListOf<ProductEvent>()
    val exceptions = mutableListOf<Pair<Throwable, String>>()
    val spans = mutableListOf<String>()

    override fun capture(event: ProductEvent) {
        events += event
    }

    override fun captureException(throwable: Throwable, fingerprint: String) {
        exceptions += throwable to fingerprint
    }

    override fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan {
        spans += name
        return NoOpSpan
    }

    override fun clear() = Unit
    override fun close() = Unit
}
