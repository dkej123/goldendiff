package com.github.dkwasniak.goldendiff.telemetry

import com.amplitude.Amplitude
import com.amplitude.Event
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin privacy boundary over Amplitude's official JVM SDK.
 *
 * Events reach this backend only after analytics opt-in and validation against [EventCatalog]. The
 * SDK owns batching, background delivery and retry. The project API key is an ingestion identifier
 * that is public by design; no management or secret key is packaged.
 */
class AmplitudeTelemetryBackend internal constructor(
    private val client: AmplitudeSdkClient,
    private val clock: Clock = Clock.systemUTC(),
) : TelemetryBackend {
    private val stopped = AtomicBoolean()

    override fun capture(event: ProductEvent) {
        if (stopped.get()) return
        client.logEvent(event.toAmplitudeEvent(clock.millis()))
    }

    override fun captureException(throwable: Throwable, fingerprint: String) = Unit

    override fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan = NoOpSpan

    /**
     * Opt-out deliberately shuts down without flushing. The official SDK then discards buffered
     * events and stops its worker pools.
     */
    override fun clear() {
        if (stopped.compareAndSet(false, true)) client.shutdown()
    }

    /**
     * A normal application shutdown flushes accepted events before releasing SDK resources.
     */
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        client.flushEvents()
        client.shutdown()
    }

    companion object {
        const val EU_ENDPOINT = "https://api.eu.amplitude.com/2/httpapi"

        fun create(apiKey: String): TelemetryBackend =
            if (apiKey.isBlank()) {
                OfflineTelemetryBackend
            } else {
                AmplitudeTelemetryBackend(
                    DefaultAmplitudeSdkClient(apiKey.trim()),
                )
            }
    }
}

internal interface AmplitudeSdkClient {
    fun logEvent(event: Event)
    fun flushEvents()
    fun shutdown()
}

private class DefaultAmplitudeSdkClient(apiKey: String) : AmplitudeSdkClient {
    private val delegate = Amplitude.getInstance("golden-diff-${UUID.randomUUID()}").apply {
        init(apiKey)
        setServerUrl(AmplitudeTelemetryBackend.EU_ENDPOINT)
        setEventUploadThreshold(10)
        setEventUploadPeriodMillis(1_000)
        setFlushTimeout(5_000)
    }

    override fun logEvent(event: Event) = delegate.logEvent(event)

    override fun flushEvents() = delegate.flushEvents()

    override fun shutdown() {
        try {
            delegate.shutdown()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

private object AmplitudeSessionStarts {
    private val values = ConcurrentHashMap<String, Long>()

    fun get(sessionId: String, fallback: Long): Long = values.computeIfAbsent(sessionId) { fallback }
}

internal fun ProductEvent.toAmplitudeEvent(time: Long): Event {
    val userId = requireNotNull(properties["user.id"])
    val sessionId = properties.getValue("session_id")
    val eventProperties: Map<String, Any> = properties
        .filterKeys { it != "user.id" }
        .mapValues { it.value }
    val userProperties: Map<String, Any> = buildMap {
        put("surface", properties.getValue("surface"))
        put("app_version", properties.getValue("app_version"))
        put("release_channel", properties.getValue("release_channel"))
        put("os_family", properties.getValue("os_family"))
        put("os_major", properties.getValue("os_major"))
        put("jvm_major", properties.getValue("jvm_major"))
        properties["ide_product"]?.let { put("ide_product", it) }
        properties["ide_build_major"]?.let { put("ide_build_major", it) }
    }

    return Event(name, userId, userId).apply {
        timestamp = time
        this.sessionId = AmplitudeSessionStarts.get(sessionId, time)
        appVersion = properties.getValue("app_version")
        platform = properties.getValue("surface")
        osName = properties.getValue("os_family")
        osVersion = properties.getValue("os_major")
        setEventProperties(eventProperties)
        setUserProperties(userProperties)
    }
}
