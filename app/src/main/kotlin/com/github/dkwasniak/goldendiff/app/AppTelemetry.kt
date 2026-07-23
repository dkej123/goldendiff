package com.github.dkwasniak.goldendiff.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.dkwasniak.goldendiff.telemetry.ReleaseChannel
import com.github.dkwasniak.goldendiff.telemetry.GoldenDiffTelemetryBackend
import com.github.dkwasniak.goldendiff.telemetry.TelemetryClient
import com.github.dkwasniak.goldendiff.telemetry.TelemetryConsent
import com.github.dkwasniak.goldendiff.telemetry.TelemetryEnvironment
import com.github.dkwasniak.goldendiff.telemetry.TelemetryStore
import com.github.dkwasniak.goldendiff.telemetry.TelemetrySurface
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

class AppTelemetrySettings private constructor(
    analytics: Boolean,
    diagnostics: Boolean,
    promptShown: Boolean,
) {
    var analyticsEnabled by mutableStateOf(analytics)
        private set
    var diagnosticsEnabled by mutableStateOf(diagnostics)
        private set
    var consentPromptShown by mutableStateOf(promptShown)
        private set

    val consent: TelemetryConsent
        get() = TelemetryConsent(analyticsEnabled, diagnosticsEnabled)

    fun setAnalytics(enabled: Boolean) {
        analyticsEnabled = enabled
        save()
    }

    fun setDiagnostics(enabled: Boolean) {
        diagnosticsEnabled = enabled
        save()
    }

    fun decide(analytics: Boolean, diagnostics: Boolean) {
        analyticsEnabled = analytics
        diagnosticsEnabled = diagnostics
        consentPromptShown = true
        save()
    }

    private fun save() {
        AppTelemetryStore.put("consent.analytics", analyticsEnabled.toString())
        AppTelemetryStore.put("consent.diagnostics", diagnosticsEnabled.toString())
        AppTelemetryStore.put("consent.prompt_shown", consentPromptShown.toString())
    }

    companion object {
        fun load(): AppTelemetrySettings = AppTelemetrySettings(
            analytics = AppTelemetryStore.get("consent.analytics").toBoolean(),
            diagnostics = AppTelemetryStore.get("consent.diagnostics").toBoolean(),
            promptShown = AppTelemetryStore.get("consent.prompt_shown").toBoolean(),
        )
    }
}

object AppTelemetryStore : TelemetryStore {
    private val file: File
        get() = File(System.getProperty("user.home"), ".config/golden-diff/telemetry.properties")

    @Synchronized
    override fun get(key: String): String? = load().getProperty(key)

    @Synchronized
    override fun put(key: String, value: String) {
        val properties = load().apply { setProperty(key, value) }
        runCatching {
            file.parentFile.mkdirs()
            file.outputStream().use { properties.store(it, "Golden Diff telemetry preferences") }
        }
    }

    @Synchronized
    override fun remove(key: String) {
        val properties = load().apply { remove(key) }
        runCatching {
            file.parentFile.mkdirs()
            file.outputStream().use { properties.store(it, "Golden Diff telemetry preferences") }
        }
    }

    private fun load(): Properties = Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use(::load) }
    }
}

object AppTelemetry {
    val settings: AppTelemetrySettings = AppTelemetrySettings.load()

    private val buildProperties = Properties().apply {
        AppTelemetry::class.java.classLoader.getResourceAsStream("golden-diff-telemetry.properties")
            ?.use(::load)
    }
    private val version = buildProperties.getProperty("version").orEmpty().ifBlank { "dev" }
    private val releaseChannel = ReleaseChannel.fromVersion(version)
    val client = TelemetryClient(
        environment = TelemetryEnvironment(
            surface = TelemetrySurface.DESKTOP,
            releaseChannel = releaseChannel,
            appVersion = version,
        ),
        store = AppTelemetryStore,
        backendFactory = { consent ->
            GoldenDiffTelemetryBackend.create(
                amplitudeApiKey = buildProperties.getProperty("amplitude.api_key").orEmpty(),
                sentryDsn = buildProperties.getProperty("sentry.dsn").orEmpty(),
                release = "golden-diff-app@$version",
                environment = releaseChannel.wireValue,
                consent = consent,
            )
        },
        initialConsent = settings.consent,
    )
    private val sessionStarted = AtomicBoolean()
    private val sessionEventSent = AtomicBoolean()
    var consentPromptVisible by mutableStateOf(!settings.consentPromptShown)
        private set

    fun updateConsent() {
        client.updateConsent(settings.consent)
        sendSessionEventIfEnabled()
    }

    fun startSession(projectRestored: Boolean) {
        sessionStarted.set(true)
        restoredProject = projectRestored
        sendSessionEventIfEnabled()
    }

    fun decideConsent(analytics: Boolean, diagnostics: Boolean) {
        settings.decide(analytics, diagnostics)
        consentPromptVisible = false
        updateConsent()
    }

    fun installUncaughtExceptionBridge() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            ChainedUncaughtExceptionHandler(
                reporter = { throwable ->
                    client.captureException(throwable, "uncaught:${throwable.javaClass.name}")
                },
                previous = previous,
            ),
        )
    }

    private var restoredProject = false

    private fun sendSessionEventIfEnabled() {
        if (
            sessionStarted.get() &&
            settings.analyticsEnabled &&
            sessionEventSent.compareAndSet(false, true)
        ) {
            client.installationFirstSeen()
            client.sessionStarted("app_launch", restoredProject)
        }
    }
}

internal class ChainedUncaughtExceptionHandler(
    private val reporter: (Throwable) -> Unit,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        reporter(throwable)
        if (previous != null) previous.uncaughtException(thread, throwable) else throwable.printStackTrace()
    }
}
