package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.telemetry.ReleaseChannel
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Fetches and applies a standalone-app update once [UpdateChecker] has found one.
 *
 * Two install paths, chosen by how the running app was installed:
 * - **Homebrew** — when the matching cask is present, `brew upgrade --cask <cask>` is streamed
 *   in-app so the tap's bumped cask is picked up.
 * - **Otherwise** — the release `.dmg` is downloaded to `~/Downloads` and opened, so the user drags
 *   the app into `/Applications`.
 *
 * Every function is a blocking JVM call meant to run on [kotlinx.coroutines.Dispatchers.IO]; the app
 * remains ad-hoc signed and not notarized, so an updated bundle is re-quarantined (the existing
 * `xattr -dr com.apple.quarantine` caveat still applies).
 */
object UpdateInstaller {

    /** The Homebrew binary and cask name for the install prefix an update can be applied through. */
    data class HomebrewCask(val brew: File, val cask: String)

    // Standard Homebrew prefixes: Apple silicon and Intel respectively.
    private val PREFIXES = listOf(File("/opt/homebrew"), File("/usr/local"))

    /** The cask name Golden Diff publishes on [channel], or null for a channel that never updates. */
    private fun caskName(channel: ReleaseChannel): String? = when (channel) {
        ReleaseChannel.BETA -> "golden-diff@beta"
        ReleaseChannel.STABLE -> "golden-diff"
        ReleaseChannel.DEV -> null
    }

    /**
     * The first Homebrew prefix that both has a `brew` binary and already installed this cask, or
     * null when the app was not installed through Homebrew. Checks two paths on disk — fast and
     * reliable, unlike shelling out to `brew list`.
     */
    fun homebrewCask(channel: ReleaseChannel): HomebrewCask? {
        val cask = caskName(channel) ?: return null
        for (prefix in PREFIXES) {
            val brew = File(prefix, "bin/brew")
            val caskroom = File(prefix, "Caskroom/$cask")
            if (brew.canExecute() && caskroom.isDirectory) return HomebrewCask(brew, cask)
        }
        return null
    }

    /**
     * Runs `brew upgrade --cask <cask>`, streaming merged stdout/stderr lines to [onLine], and
     * returns the process exit code. The prefix's `bin` is prepended to `PATH` so brew finds its own
     * tools; brew auto-update is left on so the tap's bumped cask is fetched.
     */
    fun upgradeViaHomebrew(cask: HomebrewCask, onLine: (String) -> Unit): Int {
        val builder = ProcessBuilder(cask.brew.path, "upgrade", "--cask", cask.cask)
            .redirectErrorStream(true)
        val binDir = cask.brew.parentFile?.path
        if (binDir != null) {
            builder.environment().merge("PATH", binDir) { existing, added -> "$added:$existing" }
        }
        val process = builder.start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        return process.waitFor()
    }

    /** The deterministic release-asset URL for [version] on [channel] (same scheme as the casks). */
    fun dmgUrl(version: String, channel: ReleaseChannel): String {
        val tag = if (channel == ReleaseChannel.BETA) "app-beta-v$version" else "app-v$version"
        return "https://github.com/dkej123/goldendiff/releases/download/$tag/Golden-Diff-$version.dmg"
    }

    /**
     * Downloads the `.dmg` at [url] into `~/Downloads` (falling back to a temp dir when Downloads is
     * missing) and returns the written file. Redirects are followed — GitHub redirects to a CDN.
     */
    fun downloadDmg(url: String, version: String): File {
        val downloads = File(System.getProperty("user.home"), "Downloads")
        val target = File(
            if (downloads.isDirectory) downloads else File(System.getProperty("java.io.tmpdir")),
            "Golden-Diff-$version.dmg",
        )
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        val request = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", "golden-diff-app")
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target.toPath()))
        check(response.statusCode() == 200) { "Download failed with HTTP ${response.statusCode()}" }
        return response.body().toFile()
    }

    /** Mounts the downloaded installer by opening it with the system handler. */
    fun openDmg(file: File) {
        Desktop.getDesktop().open(file)
    }

    /**
     * Strips the macOS quarantine flag from the freshly-installed bundle so Gatekeeper lets it open.
     * The app is ad-hoc signed and not notarized, so an updated bundle is re-quarantined and would
     * otherwise refuse to launch. Returns true when xattr exits cleanly.
     */
    fun removeQuarantine(): Boolean = runCatching {
        ProcessBuilder("/usr/bin/xattr", "-dr", "com.apple.quarantine", INSTALLED_APP_PATH)
            .redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    /**
     * Relaunches the freshly-installed app through LaunchServices, then quits this instance so the new
     * build takes over. `open -n -a` finds the updated bundle by name wherever the cask installed it.
     */
    fun restart() {
        runCatching { ProcessBuilder("/usr/bin/open", "-n", "-a", "Golden Diff").start() }
        exitProcess(0)
    }

    /** Where the Homebrew cask installs the app; also the path in the quarantine caveat. */
    private const val INSTALLED_APP_PATH = "/Applications/Golden Diff.app"
}
