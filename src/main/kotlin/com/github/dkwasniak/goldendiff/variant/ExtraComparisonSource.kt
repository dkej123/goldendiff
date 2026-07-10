package com.github.dkwasniak.goldendiff.variant

import com.github.dkwasniak.goldendiff.match.CurrentScreen
import com.github.dkwasniak.goldendiff.settings.ScreenshotSettings
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JComponent

enum class ExtraComparisonItemStatus {
    UNCHANGED,
    MODIFIED,
    NEW,
}

data class ExtraComparisonItem(
    val file: File,
    val title: String = file.name,
    val isLoading: Boolean = !file.isFile,
    val status: ExtraComparisonItemStatus = ExtraComparisonItemStatus.UNCHANGED,
)

/**
 * Optional comparison source contributed by a build variant.
 *
 * Implementations are discovered with ServiceLoader, so the public build can avoid compiling or
 * packaging project-specific sources.
 */
interface ExtraComparisonSource {
    val id: String
    val title: String
    val compareLabel: String
        get() = title

    fun createSettingsComponent(project: Project): ExtraSettingsComponent? = null

    fun refreshKey(screen: CurrentScreen.Screen): List<String> = emptyList()

    fun findItems(project: Project, screen: CurrentScreen.Screen, settings: ScreenshotSettings): List<ExtraComparisonItem> =
        findFiles(project, screen, settings).map { ExtraComparisonItem(it) }

    fun findFiles(project: Project, screen: CurrentScreen.Screen, settings: ScreenshotSettings): List<File>

    fun listStatus(files: List<File>): String? = null

    fun listStatusForItems(items: List<ExtraComparisonItem>): String? =
        listStatus(items.map { it.file })

    fun loadComparison(
        project: Project,
        file: File,
        screen: CurrentScreen.Screen,
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
