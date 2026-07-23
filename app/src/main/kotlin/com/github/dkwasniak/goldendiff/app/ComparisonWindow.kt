package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.github.dkwasniak.goldendiff.app.ui.CopyIcon
import com.github.dkwasniak.goldendiff.app.ui.DarkTokens
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.IconButton
import com.github.dkwasniak.goldendiff.app.ui.LightTokens
import com.github.dkwasniak.goldendiff.app.ui.LocalTokens
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.tokens
import com.github.dkwasniak.goldendiff.naming.shortGoldenName

/**
 * The comparison, detached into its own window.
 *
 * A real second window rather than an in-app overlay, matching [SettingsWindow]: the developer can
 * park the diff on a second display, or beside the main window, and keep browsing goldens in the
 * main window — the detached view follows the live selection.
 */
@Composable
fun ApplicationScope.ComparisonWindow(state: AppState, onClose: () -> Unit) {
    val palette = if (state.ui.useDarkTheme) DarkTokens else LightTokens
    val selected = state.selected
    Window(
        onCloseRequest = onClose,
        title = selected?.let { shortGoldenName(it.name) } ?: "Golden Diff — Compare",
        state = rememberWindowState(size = DpSize(1000.dp, 760.dp)),
    ) {
        CompositionLocalProvider(LocalTokens provides palette) {
            MaterialTheme(
                colors = if (palette.dark) {
                    darkColors(background = palette.background, surface = palette.panelHeader, primary = palette.accent)
                } else {
                    lightColors(background = palette.background, surface = palette.panel, primary = palette.accent)
                },
            ) {
                Column(Modifier.fillMaxSize().background(tokens.background)) {
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).background(tokens.panelHeader)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selected?.let { shortGoldenName(it.name) } ?: "Compare",
                            color = tokens.text,
                            fontSize = Type.panelTitle,
                            fontFamily = if (selected != null) Type.Mono else null,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected != null) {
                            IconButton(size = 26.dp, onClick = { copyToClipboard(selected.absolutePath) }) {
                                CopyIcon(back = tokens.textFaint, front = tokens.textDim, fill = tokens.panel)
                            }
                        }
                    }
                    HairLine()
                    CompareContent(state, Modifier.weight(1f), location = "detached_window")
                }
            }
        }
    }
}
