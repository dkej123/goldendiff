package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.telemetry.ReleaseChannel
import com.github.dkwasniak.goldendiff.version.SemVer
import org.json.JSONArray
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Once-per-launch "is there a newer build?" check for the standalone app.
 *
 * The app ships through GitHub releases: stable builds are full releases tagged `app-v<ver>`, beta
 * builds are prereleases tagged `app-beta-v<ver>`. A running build only ever looks at its own
 * channel — a stable install compares against stable releases, a beta install against betas — so a
 * beta tester is never nudged onto a stable that predates their prerelease line, and vice versa.
 *
 * The result is cached for [CACHE_TTL_MS] so restarts do not re-hit GitHub; the cache stores the
 * newest release seen (not a yes/no answer), so it stays correct after the app updates itself.
 */
object UpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/dkej123/goldendiff/releases?per_page=30"
    private const val STABLE_TAG_PREFIX = "app-v"
    private const val BETA_TAG_PREFIX = "app-beta-v"
    private val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24)

    /** A newer build the user can install, with the page to get it from. */
    data class UpdateInfo(val version: String, val url: String)

    /** One GitHub release, reduced to what the channel logic needs. */
    data class Release(val tag: String, val prerelease: Boolean) {
        /** The version this release carries for [channel], or null if it belongs to another channel. */
        fun versionFor(channel: ReleaseChannel): String? = when (channel) {
            ReleaseChannel.BETA ->
                tag.takeIf { it.startsWith(BETA_TAG_PREFIX) }?.removePrefix(BETA_TAG_PREFIX)
            ReleaseChannel.STABLE ->
                tag.takeIf { it.startsWith(STABLE_TAG_PREFIX) && !it.startsWith(BETA_TAG_PREFIX) && !prerelease }
                    ?.removePrefix(STABLE_TAG_PREFIX)
            ReleaseChannel.DEV -> null
        }
    }

    /**
     * Cached-then-networked check. Returns the newer build for [channel], or null when up to date,
     * when running a dev build, or when the network is unreachable (failures are silent by design).
     */
    fun check(
        currentVersion: String,
        channel: ReleaseChannel,
        nowMs: Long = System.currentTimeMillis(),
        fetch: () -> List<Release> = ::fetchReleases,
    ): UpdateInfo? {
        if (channel == ReleaseChannel.DEV) return null
        val store = UpdateStore.load()
        val latest = if (store.isFresh(channel, nowMs)) {
            store.latest()
        } else {
            newestForChannel(channel, fetch()).also { store.saveLatest(channel, it, nowMs) }
        }
        return latest?.takeIf { isNewer(it.version, currentVersion) }
            ?.let { UpdateInfo(it.version, it.url) }
    }

    /** Records that the user dismissed the banner for [version] so it does not reappear. */
    fun rememberBannerDismissed(version: String) = UpdateStore.rememberDismissed(version)

    /** True when the banner for [version] has not been dismissed yet. */
    fun shouldShowBanner(version: String): Boolean = UpdateStore.load().dismissedVersion != version

    /** The highest-versioned release belonging to [channel], or null if none. */
    fun newestForChannel(channel: ReleaseChannel, releases: List<Release>): UpdateInfo? =
        releases.mapNotNull { release ->
            val version = release.versionFor(channel) ?: return@mapNotNull null
            val semVer = SemVer.parse(version) ?: return@mapNotNull null
            Triple(semVer, version, releaseUrl(release.tag))
        }.maxByOrNull { it.first }?.let { UpdateInfo(it.second, it.third) }

    /** SemVer-aware "is [candidate] a newer version than [current]?" */
    fun isNewer(candidate: String, current: String): Boolean = SemVer.isNewer(candidate, current)

    private fun releaseUrl(tag: String): String =
        "https://github.com/dkej123/goldendiff/releases/tag/$tag"

    private fun fetchReleases(): List<Release> = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val request = HttpRequest.newBuilder(URI(RELEASES_API))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "golden-diff-app")
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return emptyList()
        val array = JSONArray(response.body())
        (0 until array.length()).mapNotNull { i ->
            val obj = array.getJSONObject(i)
            if (obj.optBoolean("draft", false)) return@mapNotNull null
            val tag = obj.optString("tag_name").ifBlank { return@mapNotNull null }
            Release(tag = tag, prerelease = obj.optBoolean("prerelease", false))
        }
    }.getOrDefault(emptyList())
}

/** Small properties-file cache under the user config dir; mirrors [UiPreferences]' storage. */
private object UpdateStore {

    private val file: File
        get() = File(System.getProperty("user.home"), ".config/golden-diff/update.properties")

    class Snapshot(private val props: Properties) {
        private val lastCheckMs = props.getProperty("lastCheckMs")?.toLongOrNull() ?: 0L
        private val channel = props.getProperty("channel").orEmpty()
        val dismissedVersion: String? = props.getProperty("dismissedVersion")

        fun isFresh(channel: ReleaseChannel, nowMs: Long): Boolean =
            this.channel == channel.wireValue &&
                nowMs - lastCheckMs in 0..TimeUnit.HOURS.toMillis(24)

        fun latest(): UpdateChecker.UpdateInfo? {
            val version = props.getProperty("latestVersion").orEmpty().ifBlank { return null }
            val url = props.getProperty("latestUrl").orEmpty().ifBlank { return null }
            return UpdateChecker.UpdateInfo(version, url)
        }

        fun saveLatest(channel: ReleaseChannel, latest: UpdateChecker.UpdateInfo?, nowMs: Long) {
            props.setProperty("lastCheckMs", nowMs.toString())
            props.setProperty("channel", channel.wireValue)
            props.setProperty("latestVersion", latest?.version.orEmpty())
            props.setProperty("latestUrl", latest?.url.orEmpty())
            store(props)
        }
    }

    fun load(): Snapshot {
        val props = Properties()
        if (file.isFile) runCatching { file.inputStream().use(props::load) }
        return Snapshot(props)
    }

    fun rememberDismissed(version: String) {
        val props = Properties()
        if (file.isFile) runCatching { file.inputStream().use(props::load) }
        props.setProperty("dismissedVersion", version)
        store(props)
    }

    private fun store(props: Properties) {
        runCatching {
            file.parentFile.mkdirs()
            file.outputStream().use { props.store(it, "Golden Diff — update check") }
        }
    }
}
