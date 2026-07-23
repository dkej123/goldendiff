package com.github.dkwasniak.goldendiff.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TelemetryTest {
    private val environment = TelemetryEnvironment(
        surface = TelemetrySurface.DESKTOP,
        releaseChannel = ReleaseChannel.BETA,
        appVersion = "1.5.0-beta.1",
    )

    @Test
    fun `every catalog event accepts its exact reviewed contract`() {
        val samples = mapOf(
            "product.installation_first_seen" to emptyMap(),
            "product.session_started" to mapOf(
                "entry_point" to "app_launch", "project_restored" to "false",
                "analytics_enabled" to "true", "diagnostics_enabled" to "false",
                "installation_age_bucket" to "first_day",
            ),
            "product.session_ended" to mapOf(
                "duration_bucket" to "1_4m", "scan_count_bucket" to "2_5",
                "comparison_count_bucket" to "1",
            ),
            "product.project_opened" to mapOf(
                "trigger" to "manual", "result" to "success", "configuration_present" to "true",
            ),
            "product.activation_completed" to mapOf(
                "time_to_value_bucket" to "lt_1m", "sessions_to_activation" to "1",
                "scope" to "current_file", "source" to "working_copy",
            ),
            "product.configuration_saved" to mapOf(
                "match_mode" to "annotated_method", "golden_dir_count_bucket" to "1",
                "generated_dir_count_bucket" to "0", "generated_configured" to "false",
                "trim_enabled" to "false", "changed_golden_dirs" to "true",
                "changed_generated_dirs" to "false", "changed_matching" to "false",
                "changed_filtering" to "false", "changed_display" to "false",
            ),
            "product.browse_scope_selected" to mapOf("from" to "current_file", "to" to "project_changes"),
            "product.comparison_source_selected" to mapOf("from" to "working_copy", "to" to "test_output"),
            "product.source_file_selected" to mapOf(
                "trigger" to "ide_editor", "file_family" to "kotlin", "already_open" to "true",
            ),
            "product.feature_used" to mapOf("feature" to "quick_open"),
            "product.scan_completed" to mapOf(
                "trigger" to "automatic", "scope" to "current_file", "source" to "working_copy",
                "result" to "success_nonempty", "blocker" to "none",
                "duration_bucket" to "100_499ms", "item_count_bucket" to "2_5",
                "modified_count_bucket" to "1", "new_count_bucket" to "0", "cache_hit" to "false",
            ),
            "product.operation_failed" to mapOf(
                "operation" to "scan", "error_category" to "io", "retryable" to "true",
            ),
            "product.comparison_viewed" to mapOf(
                "source" to "working_copy", "result" to "modified",
                "load_duration_bucket" to "100_499ms", "diff_ratio_bucket" to "1_5pct",
                "dimensions" to "same", "cache_hit" to "false", "selection_trigger" to "grid",
            ),
            "product.compare_mode_selected" to mapOf("mode" to "diff", "location" to "main_pane"),
            "product.zoom_selected" to mapOf(
                "zoom" to "equal_100", "action" to "hundred", "location" to "main_pane",
            ),
            "product.golden_deleted" to mapOf("result" to "success", "source" to "working_copy"),
        )

        assertEquals(EventCatalog.names, samples.keys)
        samples.forEach(EventCatalog::validate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `catalog rejects unreviewed properties`() {
        EventCatalog.validate(
            "product.feature_used",
            mapOf("feature" to "quick_open", "path" to "/secret/project"),
        )
    }

    @Test
    fun `does not create backend before opt in and consent is independent`() {
        val factory = RecordingFactory()
        val client = TelemetryClient(environment, MemoryStore(), factory)

        client.sessionStarted("app_launch", false)
        client.captureException(IllegalStateException("boom"), "test")
        assertEquals(0, factory.created)

        client.updateConsent(TelemetryConsent(analytics = true))
        client.sessionStarted("app_launch", false)
        client.captureException(IllegalStateException("boom"), "test")
        assertEquals(1, factory.created)
        assertEquals(1, factory.backend.events.size)
        assertTrue(factory.backend.exceptions.isEmpty())

        client.updateConsent(TelemetryConsent(diagnostics = true))
        assertEquals(1, factory.backend.clearCount)
        assertEquals(1, factory.backend.closeCount)
        client.captureException(IllegalStateException("boom"), "test")
        assertEquals(1, factory.backend.exceptions.size)
    }

    @Test
    fun `opt out clears backend immediately`() {
        val factory = RecordingFactory()
        val client = TelemetryClient(
            environment,
            MemoryStore(),
            factory,
            TelemetryConsent(analytics = true, diagnostics = true),
        )
        client.updateConsent(TelemetryConsent())

        assertEquals(1, factory.backend.clearCount)
        assertEquals(1, factory.backend.closeCount)
    }

    @Test
    fun `common contract contains only coarse environment values`() {
        val factory = RecordingFactory()
        val client = TelemetryClient(
            environment,
            MemoryStore(),
            factory,
            TelemetryConsent(analytics = true),
        )
        client.event("product.feature_used", mapOf("feature" to "quick_open"))

        val event = factory.backend.events.single()
        assertEquals("product.feature_used", event.name)
        assertEquals("1", event.properties["schema_version"])
        assertEquals("desktop", event.properties["surface"])
        assertEquals("beta", event.properties["release_channel"])
        assertTrue(event.properties["session_id"]!!.isNotBlank())
        assertTrue(event.properties["user.id"]!!.isNotBlank())
        event.properties.values.forEach {
            assertFalse("unsafe value: $it", PrivacySanitizer.containsSensitiveValue(it))
        }
    }

    @Test
    fun `product events are capped per UTC day`() {
        val factory = RecordingFactory()
        val client = TelemetryClient(
            environment,
            MemoryStore(),
            factory,
            TelemetryConsent(analytics = true),
            fixedClock(),
        )
        repeat(205) {
            client.event("product.feature_used", mapOf("feature" to "quick_open"))
        }
        assertEquals(200, factory.backend.events.size)
    }

    @Test
    fun `errors are capped and duplicate fingerprints are suppressed for five minutes`() {
        val factory = RecordingFactory()
        val client = TelemetryClient(
            environment,
            MemoryStore(),
            factory,
            TelemetryConsent(diagnostics = true),
            fixedClock(),
        )
        repeat(25) { index ->
            client.captureException(IllegalStateException("/Users/alice/project/$index.png"), "fp-$index")
        }
        client.captureException(IllegalStateException("again"), "fp-1")

        assertEquals(20, factory.backend.exceptions.size)
        assertFalse(factory.backend.exceptions.first().first.message.orEmpty().contains("/Users/alice"))
    }

    @Test
    fun `source file selection is deduplicated without putting memory key in payload`() {
        val factory = RecordingFactory()
        val client = TelemetryClient(
            environment,
            MemoryStore(),
            factory,
            TelemetryConsent(analytics = true),
        )
        repeat(2) {
            client.sourceFileSelected("/private/project/Secret.kt", "project_tree", "kotlin", false)
        }

        val event = factory.backend.events.single()
        assertFalse(event.properties.values.any { "Secret" in it || "/private/project" in it })
    }

    @Test
    fun `activation is emitted once per installation and surface`() {
        val store = MemoryStore()
        val first = RecordingFactory()
        TelemetryClient(environment, store, first, TelemetryConsent(analytics = true))
            .activationCompleted("current_file", "working_copy")
        val second = RecordingFactory()
        TelemetryClient(environment, store, second, TelemetryConsent(analytics = true))
            .activationCompleted("current_file", "working_copy")

        assertEquals(1, first.backend.events.size)
        assertTrue(second.backend.events.isEmpty())
    }

    @Test
    fun `installation id is not created before consent`() {
        val store = MemoryStore()
        val client = TelemetryClient(environment, store, RecordingFactory())
        assertEquals(null, store.get("telemetry.installation_id"))

        client.updateConsent(TelemetryConsent(analytics = true))
        client.event("product.feature_used", mapOf("feature" to "quick_open"))
        assertNotEquals(null, store.get("telemetry.installation_id"))
    }

    @Test
    fun `installation id persists while session id rotates between launches`() {
        val store = MemoryStore()
        val firstFactory = RecordingFactory()
        val secondFactory = RecordingFactory()

        TelemetryClient(environment, store, firstFactory, TelemetryConsent(analytics = true))
            .event("product.feature_used", mapOf("feature" to "quick_open"))
        TelemetryClient(environment, store, secondFactory, TelemetryConsent(analytics = true))
            .event("product.feature_used", mapOf("feature" to "quick_open"))

        val first = firstFactory.backend.events.single().properties
        val second = secondFactory.backend.events.single().properties
        assertEquals(first["user.id"], second["user.id"])
        assertNotEquals(first["session_id"], second["session_id"])
    }

    @Test
    fun `parallel first events share one installation id`() {
        val factory = RecordingFactory()
        val client = TelemetryClient(
            environment,
            MemoryStore(),
            factory,
            TelemetryConsent(analytics = true),
        )
        val ready = CountDownLatch(20)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(20)

        repeat(20) {
            Thread {
                ready.countDown()
                start.await()
                client.event("product.feature_used", mapOf("feature" to "quick_open"))
                finished.countDown()
            }.start()
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))

        assertEquals(1, factory.backend.events.map { it.properties.getValue("user.id") }.toSet().size)
    }

    @Test
    fun `app update preserves installation id and does not emit another first seen event`() {
        val store = MemoryStore()
        val firstFactory = RecordingFactory()
        val updatedFactory = RecordingFactory()

        val firstClient = TelemetryClient(environment, store, firstFactory, TelemetryConsent(analytics = true))
        firstClient.installationFirstSeen()
        firstClient.sessionStarted("app_launch", false)

        val updatedClient = TelemetryClient(
            environment.copy(releaseChannel = ReleaseChannel.STABLE, appVersion = "1.6.0"),
            store,
            updatedFactory,
            TelemetryConsent(analytics = true),
        )
        updatedClient.installationFirstSeen()
        updatedClient.sessionStarted("app_launch", false)

        assertEquals(
            firstFactory.backend.events.last().properties["user.id"],
            updatedFactory.backend.events.single().properties["user.id"],
        )
        assertEquals(
            listOf("product.installation_first_seen", "product.session_started"),
            firstFactory.backend.events.map { it.name },
        )
        assertEquals(listOf("product.session_started"), updatedFactory.backend.events.map { it.name })
        assertEquals("1.6.0", updatedFactory.backend.events.single().properties["app_version"])
    }

    @Test
    fun `session reports coarse installation age without changing installation id`() {
        val now = fixedClock().millis()
        val cases = mapOf(
            0 to "first_day",
            1 to "day_1",
            4 to "days_2_6",
            14 to "days_7_29",
            45 to "days_30_89",
            120 to "days_90_plus",
        )

        cases.forEach { (ageDays, expectedBucket) ->
            val store = MemoryStore().apply {
                put(
                    "telemetry.installation_first_seen_at.desktop",
                    (now - ageDays * 86_400_000L).toString(),
                )
            }
            val factory = RecordingFactory()
            TelemetryClient(
                environment,
                store,
                factory,
                TelemetryConsent(analytics = true),
                fixedClock(),
            ).sessionStarted("app_launch", false)

            assertEquals(expectedBucket, factory.backend.events.single().properties["installation_age_bucket"])
        }
    }

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC)
}

class PrivacySanitizerTest {
    @Test
    fun `sanitizes mac windows linux and file uri paths`() {
        val input = listOf(
            "/Users/alice/work/App/Foo.kt",
            "/home/alice/work/Foo.kt",
            """C:\Users\alice\work\Foo.kt""",
            "file:///Users/alice/work/image.png",
        )
        input.forEach { path ->
            val sanitized = PrivacySanitizer.sanitize("Failed at $path", File("/Users/alice/work"))
            assertFalse(sanitized, PrivacySanitizer.containsSensitiveValue(sanitized))
            assertTrue(sanitized, "<path>" in sanitized)
        }
    }
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

private class RecordingFactory : TelemetryBackendFactory {
    var created = 0
    val backend = RecordingBackend()
    override fun create(consent: TelemetryConsent): TelemetryBackend {
        created++
        backend.analytics = consent.analytics
        backend.diagnostics = consent.diagnostics
        return backend
    }
}

private class RecordingBackend : TelemetryBackend {
    val events = Collections.synchronizedList(mutableListOf<ProductEvent>())
    val exceptions = Collections.synchronizedList(mutableListOf<Pair<Throwable, String>>())
    var analytics = false
    var diagnostics = false
    var clearCount = 0
    var closeCount = 0

    override fun capture(event: ProductEvent) {
        if (analytics) events += event
    }
    override fun captureException(throwable: Throwable, fingerprint: String) {
        if (diagnostics) exceptions += throwable to fingerprint
    }
    override fun startSpan(name: String, properties: Map<String, String>): TelemetrySpan = NoOpSpan
    override fun clear() {
        clearCount++
    }
    override fun close() {
        closeCount++
    }
}
