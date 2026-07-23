package com.github.dkwasniak.goldendiff.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.github.dkwasniak.goldendiff.app.generated.resources.Res
import com.github.dkwasniak.goldendiff.app.generated.resources.app_icon
import com.github.dkwasniak.goldendiff.platform.Os
import kotlinx.coroutines.CoroutineExceptionHandler
import org.jetbrains.compose.resources.painterResource

fun main() {
    // Read before AWT starts: the native window chrome takes its appearance from this property once,
    // at initialisation, so setting it later would leave a dark title bar over a light window.
    val preferences = UiPreferences.load()
    if (Os.current == Os.MAC) {
        System.setProperty(
            "apple.awt.application.appearance",
            if (preferences.useDarkTheme) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
        )
    }
    AppTelemetry.installUncaughtExceptionBridge()
    AppTelemetry.startSession(projectRestored = AppConfig.lastProject() != null)
    application {
        val coroutineExceptionHandler = remember {
            CoroutineExceptionHandler { _, throwable ->
                AppTelemetry.client.captureException(throwable, "coroutine:${throwable.javaClass.name}")
            }
        }
        val scope = rememberCoroutineScope { coroutineExceptionHandler }
        val state = remember { AppState(scope, preferences, AppTelemetry.client) }

        LaunchedEffect(Unit) {
            AppConfig.lastProject()?.let { state.openProject(it, restored = true) }
        }

        Window(
            onCloseRequest = {
                state.close()
                exitApplication()
            },
            title = "Golden Diff",
            icon = painterResource(Res.drawable.app_icon),
            state = rememberWindowState(size = DpSize(1400.dp, 900.dp)),
            onPreviewKeyEvent = { event ->
                // Previewed at the window rather than bound to a component, so the shortcut works wherever
                // focus happens to be - the tree, the list, or nothing at all.
                when {
                    event.type != KeyEventType.KeyDown -> false
                    Shortcuts.isQuickOpen(event) -> {
                        state.featureUsed("quick_open")
                        state.quickOpenQuery = ""
                        state.quickOpenVisible = true
                        true
                    }
                    event.key == Key.Escape && state.quickOpenVisible -> {
                        state.quickOpenVisible = false
                        true
                    }
                    else -> false
                }
            },
        ) {
            GoldenDiffWindow(state)
        }

        // A real second window rather than an overlay, so the comparison stays visible while the
        // golden directories are being set up.
        if (state.settingsVisible) SettingsWindow(state) { state.settingsVisible = false }

        // The compare pane detached into its own window; follows the live selection.
        if (state.compareWindowVisible) ComparisonWindow(state) { state.compareWindowVisible = false }
        if (AppTelemetry.consentPromptVisible) TelemetryConsentWindow()
    }
}

/**
 * Platform-appropriate shortcuts, resolved in one place.
 *
 * Each is IntelliJ's own "Go to File" binding on that platform, so the shortcut a developer already
 * has in muscle memory works here too.
 */
object Shortcuts {

    val quickOpenLabel: String = if (Os.current == Os.MAC) "⇧⌘O" else "Ctrl+Shift+N"

    fun isQuickOpen(event: KeyEvent): Boolean =
        if (Os.current == Os.MAC) {
            event.isMetaPressed && event.isShiftPressed && event.key == Key.O
        } else {
            event.isCtrlPressed && event.isShiftPressed && event.key == Key.N
        }
}
