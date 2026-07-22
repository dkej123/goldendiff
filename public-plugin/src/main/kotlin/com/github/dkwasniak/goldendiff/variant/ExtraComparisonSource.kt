package com.github.dkwasniak.goldendiff.variant

import com.github.dkwasniak.goldendiff.match.Screen
import com.github.dkwasniak.goldendiff.settings.ScreenshotSettings
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JComponent

// ExtraComparisonItem and ExtraComparisonItemStatus are pure data and live in :core, in this same
// package, so both the plugin and the standalone app can consume scan results.

/** First-run directory defaults contributed by a source, applied when no settings are persisted yet. */
data class ExtraFirstRunDefaults(
    val goldenPaths: List<String> = emptyList(),
    val generatedPaths: List<String> = emptyList(),
)

/**
 * Optional comparison source contributed by a dependent plugin.
 *
 * Implementations are discovered through the `com.github.dkwasniak.goldendiff.comparisonSource`
 * extension point, so the public plugin never compiles or packages the extra sources; a separate
 * plugin that `<depends>` on it registers the extension.
 */
interface ExtraComparisonSource {
    val id: String
    val title: String
    val compareLabel: String
        get() = title

    /**
     * First-run directory defaults for a fresh project. Consulted only when no settings are persisted
     * yet; `null` (the default) leaves the directories empty.
     */
    fun firstRunDefaults(): ExtraFirstRunDefaults? = null

    fun createSettingsComponent(project: Project): ExtraSettingsComponent? = null

    fun refreshKey(screen: Screen): List<String> = emptyList()

    fun findItems(project: Project, screen: Screen, settings: ScreenshotSettings): List<ExtraComparisonItem> =
        findFiles(project, screen, settings).map { ExtraComparisonItem(it) }

    fun findFiles(project: Project, screen: Screen, settings: ScreenshotSettings): List<File>

    fun listStatus(files: List<File>): String? = null

    fun listStatusForItems(items: List<ExtraComparisonItem>): String? =
        listStatus(items.map { it.file })

    fun loadComparison(
        project: Project,
        file: File,
        screen: Screen,
        settings: ScreenshotSettings,
    ): ExtraComparisonResult
}

interface ExtraSettingsComponent {
    val component: JComponent

    fun isModified(): Boolean

    @Throws(ConfigurationException::class)
    fun apply()

    fun reset()
}

sealed class ExtraComparisonResult {
    data class Single(val image: BufferedImage?, val statusText: String) : ExtraComparisonResult()

    data class Comparison(
        val old: BufferedImage,
        val new: BufferedImage,
        val statusText: String,
        val oldLabel: String,
        val newLabel: String,
    ) : ExtraComparisonResult()
}
