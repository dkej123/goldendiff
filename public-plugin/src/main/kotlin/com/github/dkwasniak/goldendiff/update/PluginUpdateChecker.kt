package com.github.dkwasniak.goldendiff.update

import com.github.dkwasniak.goldendiff.telemetry.ReleaseChannel
import com.github.dkwasniak.goldendiff.version.SemVer
import com.google.gson.JsonParser
import com.intellij.util.io.HttpRequests

/**
 * Asks JetBrains Marketplace whether a newer Golden Diff has been published on this build's channel.
 *
 * The IDE already surfaces plugin updates on its own; this is the extra, proactive balloon. A build
 * only looks at its own channel — a stable install checks the default channel, a beta install checks
 * the `beta` channel — so beta testers are pointed at the next beta, not dragged back to stable.
 */
object PluginUpdateChecker {

    // Numeric Marketplace id for "Golden Diff" (the same id beta testers subscribe to).
    private const val PLUGIN_ID = 32662
    private const val PLUGIN_PAGE = "https://plugins.jetbrains.com/plugin/$PLUGIN_ID-golden-diff"

    data class UpdateInfo(val version: String, val url: String)

    /** The newer published version for [channel], or null when up to date / dev / unreachable. */
    fun check(currentVersion: String, channel: ReleaseChannel): UpdateInfo? {
        if (channel == ReleaseChannel.DEV) return null
        val latest = fetchLatest(channel) ?: return null
        return latest.takeIf { SemVer.isNewer(it.version, currentVersion) }
    }

    private fun fetchLatest(channel: ReleaseChannel): UpdateInfo? = runCatching {
        val channelParam = if (channel == ReleaseChannel.BETA) "&channel=beta" else ""
        val url = "https://plugins.jetbrains.com/api/plugins/$PLUGIN_ID/updates?size=1$channelParam"
        val body = HttpRequests.request(url).productNameAsUserAgent().readString()
        val updates = JsonParser.parseString(body).asJsonArray
        if (updates.isEmpty) return null
        val version = updates[0].asJsonObject.get("version")?.asString?.takeIf { it.isNotBlank() }
            ?: return null
        UpdateInfo(version, PLUGIN_PAGE)
    }.getOrNull()
}
