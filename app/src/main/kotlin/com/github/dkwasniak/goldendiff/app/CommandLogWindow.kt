package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.github.dkwasniak.goldendiff.app.ui.DarkTokens
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.LightTokens
import com.github.dkwasniak.goldendiff.app.ui.LocalTokens
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens

/**
 * The full, scrollable output of the most recent Generate/Verify run, as a separate window — the
 * expandable "log window" the toolbar status line links to. Follows the tail as new lines stream in,
 * and offers Stop while a command is still running. Mirrors [DiagnosticsWindow]'s layout.
 */
@Composable
fun ApplicationScope.CommandLogWindow(state: AppState, onClose: () -> Unit) {
    val palette = if (state.ui.useDarkTheme) DarkTokens else LightTokens
    var windowVisible by remember { mutableStateOf(true) }
    DeferredWindowCloseEffect(windowVisible, onClose)
    Window(
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = "Golden Diff — ${state.commandLabel ?: "Command"} output",
        state = rememberWindowState(size = DpSize(820.dp, 560.dp)),
    ) {
        CompositionLocalProvider(LocalTokens provides palette) {
            MaterialTheme(
                colors = if (palette.dark) {
                    darkColors(background = palette.background, surface = palette.panel, primary = palette.accent)
                } else {
                    lightColors(background = palette.background, surface = palette.panel, primary = palette.accent)
                },
            ) {
                Column(Modifier.fillMaxSize().background(tokens.background)) {
                    CommandLogHeader(state)
                    HairLine()
                    CommandLogBody(state)
                }
            }
        }
    }
}

@Composable
private fun CommandLogHeader(state: AppState) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            state.commandLabel ?: "Command",
            color = tokens.text,
            fontSize = Type.panelTitle,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            state.commandStatus.orEmpty(),
            color = tokens.textFaint,
            fontSize = Type.small,
            modifier = Modifier.weight(1f),
        )
        if (state.commandLog.isNotEmpty()) {
            Box(
                Modifier.clip(RoundedCornerShape(5.dp)).hoverWash()
                    .clickable { copyToClipboard(state.commandLog.joinToString("\n")) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("Copy", color = tokens.textDim, fontSize = Type.small)
            }
        }
        if (state.commandRunning) {
            Box(
                Modifier.clip(RoundedCornerShape(5.dp)).hoverWash().clickable(onClick = state::cancelCommand)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("Stop", color = tokens.textDim, fontSize = Type.small)
            }
        }
    }
}

@Composable
private fun CommandLogBody(state: AppState) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.commandLog.size) {
        if (state.commandLog.isNotEmpty()) listState.scrollToItem(state.commandLog.lastIndex)
    }
    if (state.commandLog.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No output yet.", color = tokens.textFaint, fontSize = Type.body)
        }
        return
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(state.commandLog) { line ->
                Text(
                    line.ifEmpty { " " },
                    color = tokens.textDim,
                    fontSize = 11.sp,
                    fontFamily = Type.Mono,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 1.dp),
                )
            }
        }
    }
}
