package com.github.dkwasniak.goldendiff.telemetry

import java.io.File
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TelemetryClient(
    private val environment: TelemetryEnvironment,
    private val store: TelemetryStore,
    private val backendFactory: TelemetryBackendFactory,
    initialConsent: TelemetryConsent = TelemetryConsent(),
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val sessionId = UUID.randomUUID().toString()
    private val startedAt = clock.millis()
    private val scans = AtomicInteger()
    private val comparisons = AtomicInteger()
    private val ended = AtomicBoolean()
    private val selectedFiles = ConcurrentHashMap.newKeySet<String>()
    private val errorFingerprints = ConcurrentHashMap<String, Long>()
    private val errorCount = AtomicInteger()
    private val rateLimiter = ProductRateLimiter(store, clock)
    private val lock = Any()

    @Volatile
    var consent: TelemetryConsent = initialConsent
        private set

    @Volatile
    private var backend: TelemetryBackend? = null

    @Volatile
    private var cachedInstallationId: String? = null

    init {
        val sessions = (store.get(sessionCountKey())?.toIntOrNull() ?: 0) + 1
        store.put(sessionCountKey(), sessions.toString())
        applyConsent(initialConsent)
    }

    fun updateConsent(value: TelemetryConsent) {
        synchronized(lock) {
            if (consent == value) return
            consent = value
            backend?.clear()
            backend?.close()
            backend = null
            applyConsent(value)
        }
    }

    fun sessionStarted(entryPoint: String, projectRestored: Boolean) {
        event(
            "product.session_started",
            mapOf(
                "entry_point" to entryPoint,
                "project_restored" to projectRestored.toString(),
                "analytics_enabled" to consent.analytics.toString(),
                "diagnostics_enabled" to consent.diagnostics.toString(),
                "installation_age_bucket" to installationAgeBucket(),
            ),
        )
    }

    fun installationFirstSeen() {
        val key = "telemetry.installation_first_seen.${environment.surface.wireValue}"
        if (!consent.analytics) return
        val timestampKey = installationFirstSeenTimestampKey()
        if (store.get(timestampKey)?.toLongOrNull() == null) {
            store.put(timestampKey, clock.millis().toString())
        }
        if (store.get(key) == "true") return
        if (event("product.installation_first_seen", emptyMap())) {
            store.put(key, "true")
        }
    }

    fun sessionEnded() {
        if (!ended.compareAndSet(false, true)) return
        event(
            "product.session_ended",
            mapOf(
                "duration_bucket" to TelemetryBuckets.session(clock.millis() - startedAt),
                "scan_count_bucket" to TelemetryBuckets.count(scans.get()),
                "comparison_count_bucket" to TelemetryBuckets.count(comparisons.get()),
            ),
        )
    }

    fun event(name: String, properties: Map<String, String>): Boolean {
        EventCatalog.validate(name, properties)
        if (!consent.analytics) return false
        val activeBackend = backend
        if (activeBackend == null || !activeBackend.acceptsProductEvents) return false
        if (!rateLimiter.tryAcquire()) return false
        if (name == "product.scan_completed") scans.incrementAndGet()
        if (name == "product.comparison_viewed") comparisons.incrementAndGet()
        val common = environment.commonProperties(sessionId, currentInstallationId())
        activeBackend.capture(ProductEvent(name, common + properties))
        return true
    }

    fun sourceFileSelected(memoryKey: String, trigger: String, fileFamily: String, alreadyOpen: Boolean) {
        if (!selectedFiles.add(memoryKey)) return
        event(
            "product.source_file_selected",
            mapOf(
                "trigger" to trigger,
                "file_family" to fileFamily,
                "already_open" to alreadyOpen.toString(),
            ),
        )
    }

    fun activationCompleted(scope: String, source: String) {
        val key = "telemetry.activation.${environment.surface.wireValue}"
        if (store.get(key) == "true") return
        val emitted = event(
            "product.activation_completed",
            mapOf(
                "time_to_value_bucket" to TelemetryBuckets.timeToValue(clock.millis() - startedAt),
                "sessions_to_activation" to activationSessionsBucket(),
                "scope" to scope,
                "source" to source,
            ),
        )
        if (emitted) store.put(key, "true")
    }

    fun captureException(
        throwable: Throwable,
        fingerprint: String,
        projectRoot: File? = null,
    ) {
        if (!consent.diagnostics || errorCount.get() >= 20) return
        val now = clock.millis()
        val previous = errorFingerprints.put(fingerprint, now)
        if (previous != null && now - previous < 5 * 60_000) return
        errorCount.incrementAndGet()
        backend?.captureException(PrivacySanitizer.sanitizedThrowable(throwable, projectRoot), fingerprint)
    }

    fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan {
        require(name in PERFORMANCE_SPANS) { "Unknown telemetry span: $name" }
        require(properties.keys.all { it in SPAN_PROPERTIES }) { "Unsafe span property" }
        properties.forEach { (_, value) ->
            require(!PrivacySanitizer.containsSensitiveValue(value)) { "Unsafe span value" }
        }
        if (!consent.analytics || !rateLimiter.tryAcquire()) return NoOpSpan
        return backend?.startSpan(
            name,
            environment.commonProperties(sessionId, currentInstallationId()) + properties,
        ) ?: NoOpSpan
    }

    fun <T> measureSpan(name: String, properties: Map<String, String>, block: () -> T): T {
        val span = startSpan(name, properties)
        return try {
            block().also { span.finish(TelemetrySpanStatus.OK) }
        } catch (error: Throwable) {
            span.finish(TelemetrySpanStatus.ERROR)
            throw error
        }
    }

    override fun close() {
        sessionEnded()
        synchronized(lock) {
            backend?.close()
            backend = null
        }
    }

    private fun applyConsent(value: TelemetryConsent) {
        if ((value.analytics || value.diagnostics) && backend == null) {
            backend = backendFactory.create(value)
        }
    }

    private fun activationSessionsBucket(): String {
        val sessions = store.get(sessionCountKey())?.toIntOrNull() ?: 1
        return when {
            sessions <= 1 -> "1"
            sessions == 2 -> "2"
            sessions <= 5 -> "3_5"
            else -> "6_plus"
        }
    }

    private fun sessionCountKey(): String = "telemetry.sessions.${environment.surface.wireValue}"

    private fun installationFirstSeenTimestampKey(): String =
        "telemetry.installation_first_seen_at.${environment.surface.wireValue}"

    private fun installationAgeBucket(): String {
        val firstSeenAt = store.get(installationFirstSeenTimestampKey())?.toLongOrNull() ?: clock.millis()
        val ageDays = ((clock.millis() - firstSeenAt).coerceAtLeast(0) / 86_400_000L).toInt()
        return when {
            ageDays == 0 -> "first_day"
            ageDays == 1 -> "day_1"
            ageDays <= 6 -> "days_2_6"
            ageDays <= 29 -> "days_7_29"
            ageDays <= 89 -> "days_30_89"
            else -> "days_90_plus"
        }
    }

    private fun currentInstallationId(): String =
        cachedInstallationId ?: synchronized(lock) {
            cachedInstallationId ?: installationId(store).also { cachedInstallationId = it }
        }

    companion object {
        val PERFORMANCE_SPANS = setOf(
            "golden.scan", "golden.match", "git.status", "git.head_read", "generated.lookup",
            "image.decode", "pixel_diff.compute", "comparison.load",
        )
        private val SPAN_PROPERTIES = setOf("surface", "scope", "source", "cache_hit", "result")
    }
}

object NoOpSpan : TelemetrySpan {
    override fun finish(status: TelemetrySpanStatus) = Unit
}
