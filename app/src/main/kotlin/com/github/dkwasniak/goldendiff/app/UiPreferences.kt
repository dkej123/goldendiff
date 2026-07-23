package com.github.dkwasniak.goldendiff.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.dkwasniak.goldendiff.app.ui.Appearance
import com.github.dkwasniak.goldendiff.platform.Os
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Preview size, as a percentage rather than three presets.
 *
 * S/M/L could not express "one notch bigger", which is the adjustment actually wanted while going
 * through a wall of phone screenshots. A card is [BASE_WIDTH] wide at 100%; the grid wraps to as
 * many columns as fit, so a wide enough card turns the grid into a single-column list on its own.
 */
object ThumbnailScale {
    const val MIN = 10
    const val MAX = 200
    const val STEP = 10
    const val DEFAULT = 100

    private const val BASE_WIDTH = 110

    fun gridWidth(percent: Int): Int = BASE_WIDTH * percent / 100
}

/**
 * View preferences that belong to the person, not to the project.
 *
 * Kept apart from [AppConfig], which is keyed per project: it would be surprising for the thumbnail
 * size or the theme to change just because a different repository was opened. Every setter writes
 * through immediately — there are three fields and no meaningful write cost.
 */
class UiPreferences private constructor(
    appearance: Appearance,
    thumbnailScale: Int,
    leftPaneCollapsed: Boolean,
) {
    var appearance by mutableStateOf(appearance)
        private set
    /** Preview size in percent, always a multiple of [ThumbnailScale.STEP]. */
    var thumbnailScale by mutableStateOf(thumbnailScale)
        private set
    var leftPaneCollapsed by mutableStateOf(leftPaneCollapsed)
        private set

    fun selectAppearance(value: Appearance) = update { appearance = value }

    fun stepThumbnailScale(delta: Int) = update {
        thumbnailScale = (thumbnailScale + delta).coerceIn(ThumbnailScale.MIN, ThumbnailScale.MAX)
    }

    fun toggleLeftPane() = update { leftPaneCollapsed = !leftPaneCollapsed }

    /** Resolves [Appearance.SYSTEM] against the OS; anything else is taken literally. */
    val useDarkTheme: Boolean
        get() = when (appearance) {
            Appearance.DARK -> true
            Appearance.LIGHT -> false
            Appearance.SYSTEM -> systemPrefersDark
        }

    private inline fun update(block: () -> Unit) {
        block()
        save()
    }

    private fun save() {
        val props = Properties().apply {
            setProperty("appearance", appearance.name)
            setProperty("thumbnailScale", thumbnailScale.toString())
            setProperty("leftPaneCollapsed", leftPaneCollapsed.toString())
        }
        runCatching {
            file.parentFile.mkdirs()
            file.outputStream().use { props.store(it, "Golden Diff — view preferences") }
        }
    }

    companion object {
        private val file: File
            get() = File(System.getProperty("user.home"), ".config/golden-diff/ui.properties")

        fun load(): UiPreferences {
            val props = Properties()
            if (file.isFile) runCatching { file.inputStream().use(props::load) }
            return UiPreferences(
                appearance = Appearance.fromName(props.getProperty("appearance")),
                thumbnailScale = (props.getProperty("thumbnailScale")?.toIntOrNull() ?: ThumbnailScale.DEFAULT)
                    .coerceIn(ThumbnailScale.MIN, ThumbnailScale.MAX),
                leftPaneCollapsed = props.getProperty("leftPaneCollapsed").toBoolean(),
            )
        }

        /**
         * Read once at startup rather than observed.
         *
         * macOS has no cheap change notification reachable from plain JVM code, and the AWT
         * appearance property is already fixed by then — so a live switch would leave the window
         * chrome and the content disagreeing. Users who switch mid-session pick Dark or Light.
         */
        private val systemPrefersDark: Boolean by lazy {
            if (Os.current != Os.MAC) return@lazy true
            runCatching {
                val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor(2, TimeUnit.SECONDS)
                output.trim().equals("Dark", ignoreCase = true)
            }.getOrDefault(true)
        }
    }
}
