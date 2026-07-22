package com.github.dkwasniak.goldendiff.settings

/**
 * Default configuration values that are not specific to any host.
 *
 * These describe the conventions of the screenshot-testing tools themselves (Roborazzi, Paparazzi,
 * Compose Preview Screenshot…), not anything about the IDE, so both the plugin's persisted settings
 * and the standalone app's config file seed themselves from here. Matching-mode defaults live next to
 * the matching code in [com.github.dkwasniak.goldendiff.match.MatchingDefaults].
 */
object GoldenDiffDefaults {

    /** Selects generated test output; the first capture group maps back to the golden's base name. */
    const val GENERATED_FILE_REGEX = "^(.+)_actual\\.png$"

    /**
     * File-name suffixes (before the extension) whose files are artifacts rather than goldens
     * themselves — e.g. Roborazzi's `_compare` / `_actual` output — and are excluded from golden lists.
     */
    val EXCLUDED_SUFFIXES = listOf("_compare", "_actual")
}
