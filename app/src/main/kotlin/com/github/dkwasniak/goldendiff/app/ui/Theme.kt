package com.github.dkwasniak.goldendiff.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which palette the window uses. [SYSTEM] follows the OS appearance at startup. */
enum class Appearance(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System");

    companion object {
        fun fromName(name: String?): Appearance = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * The design-token palette, as a value rather than a Material theme.
 *
 * Material's colour roles do not map onto a developer tool's vocabulary (panel vs panel header vs
 * app background, three text weights, two diff-status colours), so the tokens are carried verbatim
 * from the design handoff and read through [LocalTokens].
 */
data class Tokens(
    val dark: Boolean,
    /** Sits behind the floating panes. */
    val background: Color,
    /** The floating "tool window" panes. */
    val panel: Color,
    /** Toolbar, legend, mode row — the strips that frame content. */
    val panelHeader: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentBg: Color,
    val accentBorder: Color,
    val changed: Color,
    val changedBg: Color,
    val new: Color,
    val newBg: Color,
    val hover: Color,
    val inputBackground: Color,
    val scrim: Color,
) {
    /** Accent-on-accent foreground; white in both themes because the accent is dark enough. */
    val onAccent: Color get() = Color.White
}

val DarkTokens = Tokens(
    dark = true,
    background = Color(0xFF1E1F22),
    panel = Color(0xFF242629),
    panelHeader = Color(0xFF2B2D30),
    border = Color(0xFFFFFFFF).copy(alpha = 0.08f),
    borderStrong = Color(0xFFFFFFFF).copy(alpha = 0.15f),
    text = Color(0xFFDFE1E5),
    textDim = Color(0xFF8A8D93),
    textFaint = Color(0xFF6B6E74),
    accent = Color(0xFF548AF7),
    accentBg = Color(0xFF548AF7).copy(alpha = 0.16f),
    accentBorder = Color(0xFF548AF7).copy(alpha = 0.50f),
    changed = Color(0xFFE0656B),
    changedBg = Color(0xFFE0656B).copy(alpha = 0.16f),
    new = Color(0xFF66B578),
    newBg = Color(0xFF66B578).copy(alpha = 0.16f),
    hover = Color(0xFFFFFFFF).copy(alpha = 0.055f),
    inputBackground = Color(0xFF1A1B1E),
    scrim = Color(0xFF08090B).copy(alpha = 0.60f),
)

val LightTokens = Tokens(
    dark = false,
    background = Color(0xFFF7F8FA),
    panel = Color(0xFFFFFFFF),
    panelHeader = Color(0xFFF2F3F5),
    border = Color(0xFFE1E3E8),
    borderStrong = Color(0xFFCFD2D8),
    text = Color(0xFF1E1F22),
    textDim = Color(0xFF63666B),
    textFaint = Color(0xFF93969B),
    accent = Color(0xFF3574F0),
    accentBg = Color(0xFF3574F0).copy(alpha = 0.10f),
    accentBorder = Color(0xFF3574F0).copy(alpha = 0.55f),
    changed = Color(0xFFC9424A),
    changedBg = Color(0xFFC9424A).copy(alpha = 0.10f),
    new = Color(0xFF2F8F52),
    newBg = Color(0xFF2F8F52).copy(alpha = 0.10f),
    hover = Color(0xFF000000).copy(alpha = 0.045f),
    inputBackground = Color(0xFFFFFFFF),
    scrim = Color(0xFF1E2024).copy(alpha = 0.32f),
)

val LocalTokens = staticCompositionLocalOf { DarkTokens }

/** Shorthand so call sites read `tokens.accent` instead of `LocalTokens.current.accent`. */
val tokens: Tokens
    @Composable @ReadOnlyComposable get() = LocalTokens.current

/** Type scale from the handoff. Sizes are fixed, not scaled — this is a dense developer tool. */
object Type {
    val Mono = FontFamily.Monospace

    val thumbLabel = 10.5.sp
    val small = 11.sp
    val body = 12.sp
    val panelTitle = 12.5.sp
    val header = 13.sp
    val title = 13.5.sp
    val large = 15.sp
}

/** The 4px grid, named where a value repeats across panes. */
object Dimens {
    // Half the handoff's 10px: at the app's real window size those gaps read as wasted space.
    val paneGap = 5.dp
    val panePadding = 5.dp
    val panelRadius = 10.dp
    val controlRadius = 6.dp
    val barRadius = 8.dp
    val rowHeight = 26.dp
    val toolbarHeight = 44.dp
    val legendHeight = 30.dp
    val treeIndent = 16.dp
    val leftPaneWidth = 240.dp
}
