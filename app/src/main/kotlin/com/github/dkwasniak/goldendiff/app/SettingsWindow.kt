package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.github.dkwasniak.goldendiff.app.ui.Appearance
import com.github.dkwasniak.goldendiff.app.ui.BuildBadges
import com.github.dkwasniak.goldendiff.app.ui.DarkTokens
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.GhostButton
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.LightTokens
import com.github.dkwasniak.goldendiff.app.ui.LocalTokens
import com.github.dkwasniak.goldendiff.app.ui.PrimaryButton
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.WrappingPaneHeader
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens
import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.settings.GeneratedSource
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import java.awt.Cursor

private val SettingsNavigationWidth = 210.dp
private val SettingsPreviewMinimumWidth = 220.dp
private val SettingsContentMinimumWidth = 320.dp

/** The sections of the settings window, in nav order. */
private enum class SettingsSection(val label: String) {
    PROJECT("Project"),
    DIRECTORIES("Directories"),
    OUTPUT_MATCHING("Test output matching"),
    MATCHING("Golden matching"),
    FILTERING("Filtering"),
    DISPLAY("Display"),
    COMMANDS("Commands"),
    NOTIFICATIONS("Notifications"),
    PRIVACY("Privacy");

    /** The live match preview is meaningless for the appearance/trim/command controls. */
    val showsPreview: Boolean get() =
        this in setOf(OUTPUT_MATCHING, MATCHING, FILTERING)
}

/**
 * Settings, as a separate 900×620 window rather than an overlay.
 *
 * A modal over the main window would hide the very comparison the user is adjusting the settings
 * for — with two windows they can drag a directory in and watch the golden list repopulate.
 */
@Composable
fun ApplicationScope.SettingsWindow(state: AppState, onClose: () -> Unit) {
    val palette = if (state.ui.useDarkTheme) DarkTokens else LightTokens
    var windowVisible by remember { mutableStateOf(true) }
    DeferredWindowCloseEffect(windowVisible, onClose)
    val requestClose = { windowVisible = false }
    Window(
        onCloseRequest = requestClose,
        visible = windowVisible,
        title = "Golden Diff Settings",
        state = rememberWindowState(size = DpSize(1040.dp, 640.dp)),
    ) {
        CompositionLocalProvider(LocalTokens provides palette) {
            MaterialTheme(
                colors = if (palette.dark) {
                    darkColors(background = palette.background, surface = palette.panel, primary = palette.accent)
                } else {
                    lightColors(background = palette.background, surface = palette.panel, primary = palette.accent)
                },
            ) {
                var section by remember { mutableStateOf(SettingsSection.PROJECT) }
                Column(Modifier.fillMaxSize().background(tokens.background)) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val maximumPreviewWidth =
                            (maxWidth - SettingsNavigationWidth - SettingsContentMinimumWidth - 5.dp)
                                .coerceAtLeast(SettingsPreviewMinimumWidth)
                        val previewWidth = remember {
                            mutableStateOf(
                                state.ui.settingsPreviewWidth.dp.coerceIn(
                                    SettingsPreviewMinimumWidth,
                                    maximumPreviewWidth,
                                ),
                            )
                        }
                        val visiblePreviewWidth =
                            previewWidth.value.coerceIn(SettingsPreviewMinimumWidth, maximumPreviewWidth)

                        Row(Modifier.fillMaxSize()) {
                            Column(
                                Modifier.width(SettingsNavigationWidth).fillMaxHeight().padding(vertical = 10.dp),
                            ) {
                                SettingsSection.entries.forEach { candidate ->
                                    NavItem(candidate.label, section == candidate) { section = candidate }
                                }
                            }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(tokens.border))
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                                    .padding(horizontal = 28.dp, vertical = 22.dp),
                            ) {
                                if (state.projectRoot == null && section != SettingsSection.PROJECT) {
                                    Text(
                                        "Open a project to view or change project-dependent settings.",
                                        color = tokens.textDim,
                                        fontSize = Type.body,
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.controlRadius))
                                            .background(tokens.accentBg).padding(10.dp),
                                    )
                                    Spacer(Modifier.height(14.dp))
                                }
                                when (section) {
                                    SettingsSection.PROJECT -> ProjectSection(state)
                                    SettingsSection.DIRECTORIES -> DirectoriesSection(state)
                                    SettingsSection.OUTPUT_MATCHING -> {
                                        XcresultSourceSection(state)
                                        if (state.config.generatedSource == GeneratedSource.DIRECTORY) {
                                            Spacer(Modifier.height(20.dp))
                                            OutputMatchingSection(state)
                                        }
                                    }
                                    SettingsSection.MATCHING -> MatchingSection(state)
                                    SettingsSection.FILTERING -> FilteringSection(state)
                                    SettingsSection.DISPLAY -> DisplaySection(state)
                                    SettingsSection.COMMANDS -> CommandsSection(state)
                                    SettingsSection.NOTIFICATIONS -> NotificationsSection(state)
                                    SettingsSection.PRIVACY -> PrivacySection()
                                }
                            }
                            if (section.showsPreview) {
                                SettingsPreviewSplitter(
                                    previewWidth = previewWidth,
                                    maximumWidth = maximumPreviewWidth,
                                    onDragFinished = { width ->
                                        state.ui.rememberSettingsPreviewWidth(width.value.toInt())
                                    },
                                )
                                PreviewPanel(state, Modifier.width(visiblePreviewWidth).fillMaxHeight())
                            }
                        }
                    }
                    HairLine()
                    Row(
                        Modifier.fillMaxWidth().height(54.dp).background(tokens.panelHeader)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Golden Diff ${AppTelemetry.appVersion}",
                            color = tokens.textFaint,
                            fontSize = Type.small,
                        )
                        BuildBadges(AppTelemetry.metadata)
                        Spacer(Modifier.weight(1f))
                        GhostButton("Run setup walkthrough again") {
                            requestClose()
                            state.onboardingVisible = true
                        }
                        GhostButton("Cancel", requestClose)
                        PrimaryButton("OK", onClick = requestClose)
                    }
                }
            }
        }
    }
}

/** Dragging left grows the preview; dragging right gives the form more room. */
@Composable
private fun SettingsPreviewSplitter(
    previewWidth: MutableState<Dp>,
    maximumWidth: Dp,
    onDragFinished: (Dp) -> Unit,
) {
    var active by remember { mutableStateOf(false) }
    Box(
        Modifier.width(5.dp).fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(maximumWidth) {
                detectHorizontalDragGestures(
                    onDragStart = { active = true },
                    onDragEnd = {
                        active = false
                        onDragFinished(previewWidth.value)
                    },
                    onDragCancel = { active = false },
                ) { _, drag ->
                    previewWidth.value =
                        (previewWidth.value - drag.toDp())
                            .coerceIn(SettingsPreviewMinimumWidth, maximumWidth)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(if (active) 2.dp else 1.dp).fillMaxHeight()
                .background(if (active) tokens.accent.copy(alpha = 0.55f) else tokens.border),
        )
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Text(
        label,
        color = if (selected) tokens.text else tokens.textDim,
        fontSize = Type.panelTitle,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(shape)
            .background(if (selected) tokens.accentBg else Color.Transparent, shape)
            .hoverWash(!selected, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * Live match preview: the goldens the current directories and matching rules resolve to right now.
 *
 * No dedicated match call — editing a field runs `updateConfig → refresh`, which recomputes
 * [AppState.items], so this panel simply reflects that list as it settles.
 */
@Composable
private fun PreviewPanel(state: AppState, modifier: Modifier) {
    Column(modifier.background(tokens.panelHeader)) {
        WrappingPaneHeader(
            minHeight = 38.dp,
            horizontalPadding = 14.dp,
            verticalPadding = 6.dp,
            spacing = 8.dp,
            leading = {
                Text(
                    "Preview (live)",
                    color = tokens.text,
                    fontSize = Type.panelTitle,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            actions = {
                Text(
                    if (state.busy) "…" else "${state.items.size}",
                    color = tokens.textFaint,
                    fontSize = Type.small,
                )
            },
        )
        HairLine()
        when {
            state.projectRoot == null ->
                PreviewHint("Open a project to preview matches.")
            !state.config.isConfigured ->
                PreviewHint("Add a golden directory to see matches.")
            state.busy ->
                PreviewHint("Scanning…")
            state.items.isEmpty() ->
                PreviewHint("No goldens match the current settings.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.items, key = { it.file.path }) { item ->
                    Text(
                        item.file.name,
                        color = tokens.textDim,
                        fontSize = Type.small,
                        fontFamily = Type.Mono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewHint(text: String) {
    Text(text, color = tokens.textFaint, fontSize = Type.small, modifier = Modifier.padding(14.dp))
}
