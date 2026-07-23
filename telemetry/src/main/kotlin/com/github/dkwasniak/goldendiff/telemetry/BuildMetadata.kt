package com.github.dkwasniak.goldendiff.telemetry

import java.util.Properties

/**
 * Explicit build provenance, independent of the version string.
 *
 * [isBeta] is derived from the version; [isDeveloperBuild] is read from the generated
 * `golden-diff-telemetry.properties` (`build.developer`), which every local build writes as `true`
 * and only `-PreleaseBuild=true` writes as `false`. The effective [releaseChannel] collapses to
 * [ReleaseChannel.DEV] for developer builds, which keeps analytics, diagnostics and the update check
 * fully offline regardless of the version or any injected keys.
 *
 * Shared by the desktop app and the public plugin so both surfaces decide "is this a dev build?" and
 * "which badges apply?" the same way.
 */
data class BuildMetadata(
    val version: String,
    val isDeveloperBuild: Boolean,
) {
    val isBeta: Boolean = version.contains("beta", ignoreCase = true)

    val releaseChannel: ReleaseChannel = when {
        isDeveloperBuild -> ReleaseChannel.DEV
        isBeta -> ReleaseChannel.BETA
        else -> ReleaseChannel.STABLE
    }

    /**
     * The channel the update check and installer target. Unlike [releaseChannel] it never collapses to
     * [ReleaseChannel.DEV]: a developer build still looks for updates on the beta or stable channel it
     * belongs to (from the version), so the update mechanism works in dev while telemetry stays offline.
     */
    val updateChannel: ReleaseChannel = if (isBeta) ReleaseChannel.BETA else ReleaseChannel.STABLE

    /** Footer badges in display order: DEV BUILD before BETA when both apply; empty for a stable release. */
    val badges: List<BuildBadge> = buildList {
        if (isDeveloperBuild) add(BuildBadge.DEV)
        if (isBeta) add(BuildBadge.BETA)
    }

    companion object {
        /**
         * Reads provenance from the build [properties]. A missing `build.developer` is treated as a
         * developer build, so a stripped or stale resource fails closed (offline) rather than open.
         */
        fun from(version: String, properties: Properties): BuildMetadata =
            BuildMetadata(
                version = version.ifBlank { "dev" },
                isDeveloperBuild = properties.getProperty("build.developer")?.toBoolean() ?: true,
            )
    }
}

/** A small footer marker for non-stable builds. */
enum class BuildBadge(val label: String) {
    DEV("DEV BUILD"),
    BETA("BETA"),
}
