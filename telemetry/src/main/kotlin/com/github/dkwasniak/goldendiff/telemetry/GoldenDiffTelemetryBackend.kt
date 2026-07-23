package com.github.dkwasniak.goldendiff.telemetry

/**
 * Routes privacy-consented product analytics to Amplitude and diagnostics/performance to Sentry.
 */
class GoldenDiffTelemetryBackend internal constructor(
    private val analytics: TelemetryBackend,
    private val monitoring: TelemetryBackend,
) : TelemetryBackend {
    override val acceptsProductEvents: Boolean
        get() = analytics.acceptsProductEvents

    override fun capture(event: ProductEvent) = analytics.capture(event)

    override fun captureException(throwable: Throwable, fingerprint: String) =
        monitoring.captureException(throwable, fingerprint)

    override fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan =
        monitoring.startSpan(name, properties)

    override fun clear() {
        analytics.clear()
        monitoring.clear()
    }

    override fun close() {
        analytics.close()
        monitoring.close()
    }

    companion object {
        fun create(
            amplitudeApiKey: String,
            sentryDsn: String,
            release: String,
            environment: String,
            consent: TelemetryConsent,
        ): TelemetryBackend {
            if (!consent.analytics && !consent.diagnostics) return OfflineTelemetryBackend
            return GoldenDiffTelemetryBackend(
                analytics = if (consent.analytics) {
                    AmplitudeTelemetryBackend.create(amplitudeApiKey)
                } else {
                    OfflineTelemetryBackend
                },
                monitoring = SentryTelemetryBackend.create(
                    dsn = sentryDsn,
                    release = release,
                    environment = environment,
                    consent = consent,
                ),
            )
        }
    }
}
