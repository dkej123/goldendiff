package com.github.dkwasniak.goldendiff.telemetry

import io.sentry.Breadcrumb
import io.sentry.ISpan
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.SpanStatus
import io.sentry.protocol.User
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

class SentryTelemetryBackend private constructor(
    private val analyticsEnabled: Boolean,
    private val diagnosticsEnabled: Boolean,
) : TelemetryBackend {

    override fun capture(event: ProductEvent) {
        if (!analyticsEnabled) return
        configureUser(event.properties["user.id"])
        val transaction = Sentry.startTransaction(event.name, "product")
        event.properties.forEach { (key, value) ->
            if (key == "user.id") {
                transaction.setData(key, value)
            } else {
                transaction.setTag(key, value)
            }
        }
        Sentry.configureScope { scope ->
            scope.addBreadcrumb(Breadcrumb().apply {
                category = "product"
                message = event.name
                level = io.sentry.SentryLevel.INFO
            })
        }
        transaction.status = SpanStatus.OK
        transaction.finish()
    }

    override fun captureException(throwable: Throwable, fingerprint: String) {
        if (!diagnosticsEnabled) return
        Sentry.withScope { scope ->
            scope.setFingerprint(listOf(fingerprint))
            Sentry.captureException(throwable)
        }
    }

    override fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan {
        if (!analyticsEnabled) return NoOpSpan
        configureUser(properties["user.id"])
        val transaction = Sentry.startTransaction(name, "performance")
        properties.forEach { (key, value) ->
            if (key == "user.id") {
                transaction.setData(key, value)
            } else {
                transaction.setTag(key, value)
            }
        }
        return SentrySpan(transaction)
    }

    private fun configureUser(userId: String?) {
        Sentry.configureScope { scope ->
            scope.user = userId?.let { User().apply { id = it } }
        }
    }

    override fun clear() {
        Sentry.configureScope { it.clear() }
    }

    override fun close() {
        if (diagnosticsEnabled) Sentry.endSession()
        Sentry.close()
    }

    private class SentrySpan(private val span: ISpan) : TelemetrySpan {
        private val finished = AtomicBoolean()

        override fun finish(status: TelemetrySpanStatus) {
            if (!finished.compareAndSet(false, true)) return
            span.status = when (status) {
                TelemetrySpanStatus.OK -> SpanStatus.OK
                TelemetrySpanStatus.CANCELLED -> SpanStatus.CANCELLED
                TelemetrySpanStatus.ERROR -> SpanStatus.INTERNAL_ERROR
            }
            span.finish()
        }
    }

    companion object {
        fun create(
            dsn: String,
            release: String,
            environment: String,
            consent: TelemetryConsent,
        ): TelemetryBackend {
            if (dsn.isBlank() || (!consent.analytics && !consent.diagnostics)) return OfflineTelemetryBackend
            Sentry.init { options ->
                options.dsn = dsn
                options.release = release
                options.environment = environment
                options.isSendDefaultPii = false
                options.isAttachStacktrace = true
                options.serverName = null
                options.isEnableUncaughtExceptionHandler = false
                options.isEnableAutoSessionTracking = false
                options.isEnableShutdownHook = false
                options.tracesSampleRate = 1.0
                options.maxBreadcrumbs = 20
                options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                    if (consent.diagnostics) event else null
                }
                options.beforeSendTransaction = SentryOptions.BeforeSendTransactionCallback { transaction, _ ->
                    if (consent.analytics) transaction else null
                }
            }
            if (consent.diagnostics) Sentry.startSession()
            return SentryTelemetryBackend(consent.analytics, consent.diagnostics)
        }

        fun dsnFromResources(classLoader: ClassLoader = SentryTelemetryBackend::class.java.classLoader): String {
            val properties = Properties()
            classLoader.getResourceAsStream("golden-diff-telemetry.properties")
                ?.use(properties::load)
            return properties.getProperty("sentry.dsn")
                .orEmpty()
                .ifBlank { properties.getProperty("dsn").orEmpty() }
                .trim()
        }
    }
}

object OfflineTelemetryBackend : TelemetryBackend {
    override val acceptsProductEvents: Boolean = false
    override fun capture(event: ProductEvent) = Unit
    override fun captureException(throwable: Throwable, fingerprint: String) = Unit
    override fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan = NoOpSpan
    override fun clear() = Unit
    override fun close() = Unit
}
