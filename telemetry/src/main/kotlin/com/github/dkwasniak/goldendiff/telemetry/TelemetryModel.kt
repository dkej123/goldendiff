package com.github.dkwasniak.goldendiff.telemetry

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

enum class TelemetrySurface(val wireValue: String) {
    DESKTOP("desktop"),
    PLUGIN("plugin"),
}

enum class ReleaseChannel(val wireValue: String) {
    STABLE("stable"),
    BETA("beta"),
    DEV("dev");

    companion object {
        fun fromVersion(version: String): ReleaseChannel = when {
            version.contains("beta", ignoreCase = true) -> BETA
            version.contains("dev", ignoreCase = true) ||
                version.contains("snapshot", ignoreCase = true) -> DEV
            else -> STABLE
        }
    }
}

data class TelemetryConsent(
    val analytics: Boolean = false,
    val diagnostics: Boolean = false,
)

data class TelemetryEnvironment(
    val surface: TelemetrySurface,
    val releaseChannel: ReleaseChannel,
    val appVersion: String,
    val ideProduct: String? = null,
    val ideBuildMajor: String? = null,
) {
    init {
        if (surface == TelemetrySurface.PLUGIN) {
            require(ideProduct in setOf("android_studio", "intellij_idea", "other"))
            require(ideBuildMajor?.matches(Regex("""\d{3}""")) == true)
        }
    }

    internal fun commonProperties(sessionId: String, installationId: String): Map<String, String> = buildMap {
        put("schema_version", "1")
        put("surface", surface.wireValue)
        put("release_channel", releaseChannel.wireValue)
        put("app_version", appVersion)
        put("os_family", platformOsFamily())
        put("os_major", majorVersion(System.getProperty("os.version")))
        put("jvm_major", majorVersion(System.getProperty("java.specification.version")))
        put("session_id", sessionId)
        put("user.id", installationId)
        if (surface == TelemetrySurface.PLUGIN) {
            put("ide_product", requireNotNull(ideProduct))
            put("ide_build_major", requireNotNull(ideBuildMajor))
        }
    }
}

data class ProductEvent(
    val name: String,
    val properties: Map<String, String>,
)

enum class TelemetrySpanStatus {
    OK,
    CANCELLED,
    ERROR,
}

interface TelemetrySpan : AutoCloseable {
    fun finish(status: TelemetrySpanStatus = TelemetrySpanStatus.OK)
    override fun close() = finish()
}

interface TelemetryStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

interface TelemetryBackend : AutoCloseable {
    val acceptsProductEvents: Boolean
        get() = true
    fun capture(event: ProductEvent)
    fun captureException(throwable: Throwable, fingerprint: String)
    fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan
    fun clear()
    override fun close()
}

fun interface TelemetryBackendFactory {
    fun create(consent: TelemetryConsent): TelemetryBackend
}

internal class ProductRateLimiter(
    private val store: TelemetryStore,
    private val clock: Clock,
    private val limit: Int = 200,
) {
    @Synchronized
    fun tryAcquire(): Boolean {
        val day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString()
        val storedDay = store.get(KEY_DAY)
        val count = if (storedDay == day) store.get(KEY_COUNT)?.toIntOrNull() ?: 0 else 0
        if (count >= limit) return false
        store.put(KEY_DAY, day)
        store.put(KEY_COUNT, (count + 1).toString())
        return true
    }

    private companion object {
        const val KEY_DAY = "telemetry.product.day"
        const val KEY_COUNT = "telemetry.product.count"
    }
}

internal fun installationId(store: TelemetryStore): String =
    store.get("telemetry.installation_id") ?: UUID.randomUUID().toString().also {
        store.put("telemetry.installation_id", it)
    }

private fun platformOsFamily(): String {
    val name = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return when {
        "mac" in name || "darwin" in name -> "macos"
        "win" in name -> "windows"
        "linux" in name -> "linux"
        else -> "other"
    }
}

private fun majorVersion(value: String?): String =
    value.orEmpty().trim().substringBefore('.').filter(Char::isDigit).ifEmpty { "0" }
