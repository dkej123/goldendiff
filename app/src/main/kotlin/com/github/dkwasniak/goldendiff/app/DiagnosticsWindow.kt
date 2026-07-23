package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
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
 * Developer-only diagnostics log, as a separate window. Shows what the app is doing — analytics
 * events (even when suppressed offline), update checks, install steps and uncaught exceptions — so a
 * developer can watch behaviour live. Only offered in developer builds; nothing here leaves the machine.
 */
@Composable
fun ApplicationScope.DiagnosticsWindow(state: AppState, onClose: () -> Unit) {
    val palette = if (state.ui.useDarkTheme) DarkTokens else LightTokens
    var windowVisible by remember { mutableStateOf(true) }
    DeferredWindowCloseEffect(windowVisible, onClose)
    Window(
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = "Golden Diff Diagnostics",
        state = rememberWindowState(size = DpSize(760.dp, 560.dp)),
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
                    DiagnosticsHeader()
                    HairLine()
                    DiagnosticsList()
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsHeader() {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Diagnostics", color = tokens.text, fontSize = Type.panelTitle, fontWeight = FontWeight.SemiBold)
        Text(
            "${DevLog.entries.size} entries",
            color = tokens.textFaint,
            fontSize = Type.small,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.clip(RoundedCornerShape(5.dp)).hoverWash().clickable(onClick = DevLog::clear)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text("Clear", color = tokens.textDim, fontSize = Type.small)
        }
    }
}

@Composable
private fun DiagnosticsList() {
    val listState = rememberLazyListState()
    // Follow the tail as new lines arrive, like a console.
    LaunchedEffect(DevLog.entries.size) {
        if (DevLog.entries.isNotEmpty()) listState.scrollToItem(DevLog.entries.lastIndex)
    }
    if (DevLog.entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No activity yet.", color = tokens.textFaint, fontSize = Type.body)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(DevLog.entries) { entry -> DiagnosticsRow(entry) }
    }
}

@Composable
private fun DiagnosticsRow(entry: DevLogEntry) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(entry.time, color = tokens.textFaint, fontSize = 11.sp, fontFamily = Type.Mono)
        CategoryChip(entry.category)
        Text(
            entry.message,
            color = tokens.textDim,
            fontSize = 11.sp,
            fontFamily = Type.Mono,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CategoryChip(category: String) {
    val color = when (category) {
        "update" -> tokens.accent
        "exception" -> Color(0xFFE05252)
        "analytics" -> Color(0xFF4FAF6D)
        else -> tokens.textFaint
    }
    Box(
        Modifier.width(66.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
    ) {
        Text(category, color = color, fontSize = 10.sp, fontFamily = Type.Mono, maxLines = 1)
    }
}
