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
import com.github.dkwasniak.goldendiff.platform.Os

fun main() = application {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }

    LaunchedEffect(Unit) {
        AppConfig.lastProject()?.let(state::openProject)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Golden Diff",
        state = rememberWindowState(size = DpSize(1400.dp, 900.dp)),
        onPreviewKeyEvent = { event ->
            // Previewed at the window rather than bound to a component, so the shortcut works wherever
            // focus happens to be - the tree, the list, or nothing at all.
            when {
                event.type != KeyEventType.KeyDown -> false
                Shortcuts.isQuickOpen(event) -> {
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
