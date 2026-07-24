package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dkwasniak.goldendiff.app.ui.BuildBadges
import com.github.dkwasniak.goldendiff.app.ui.CloseIcon
import com.github.dkwasniak.goldendiff.app.ui.DarkTokens
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.EmptyState
import com.github.dkwasniak.goldendiff.app.ui.DropdownButton
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.LegendItem
import com.github.dkwasniak.goldendiff.app.ui.LightTokens
import com.github.dkwasniak.goldendiff.app.ui.LocalTokens
import com.github.dkwasniak.goldendiff.app.ui.ToolButton
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.VerticalHairLine
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens
import com.github.dkwasniak.goldendiff.platform.Os
import com.github.dkwasniak.goldendiff.platform.RevealInFileManager
import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import java.awt.Cursor
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane

/**
 * The main window: a toolbar, a legend, and three floating panes.
 *
 * The window's own title bar is the native macOS one — the design mocks it up because a browser has
 * none, but reproducing it in Compose would mean an undecorated window and hand-rolled traffic
 * lights that behave subtly unlike the real ones.
 */
@Composable
fun GoldenDiffWindow(state: AppState) {
    val palette = if (state.ui.useDarkTheme) DarkTokens else LightTokens
    CompositionLocalProvider(LocalTokens provides palette) {
        val materialColors = if (palette.dark) {
            darkColors(background = palette.background, surface = palette.panelHeader, primary = palette.accent)
        } else {
            lightColors(background = palette.background, surface = palette.panel, primary = palette.accent)
        }
        MaterialTheme(colors = materialColors) {
            Column(Modifier.fillMaxSize().background(palette.background)) {
                if (state.updateBannerVisible) {
                    UpdateBanner(state)
                    HairLine()
                }
                Toolbar(state)
                HairLine()
                LegendRow(state)
                HairLine()
                if (state.openTabs.isNotEmpty()) TabStrip(state)
                Body(state, Modifier.weight(1f))
                HairLine()
                StatusBar(state)
            }
            if (state.quickOpenVisible) QuickOpenDialog(state)
        }
    }
}

@Composable
private fun Toolbar(state: AppState) {
    val hasProject = state.projectRoot != null
    Row(
        Modifier.fillMaxWidth().height(Dimens.toolbarHeight).background(tokens.panelHeader)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!hasProject) {
            ToolButton("Open project…") { chooseDirectory("Select project directory")?.let { state.openProject(it) } }
        }
        ToolButton("Settings", enabled = hasProject) { state.settingsVisible = true }
        ToolButton("Refresh", enabled = hasProject) { state.forceRefresh() }
        VerticalHairLine(20.dp)
        DropdownButton("Scope", state.browse, Browse.entries.toList(), Browse::label, hasProject) { state.selectBrowse(it) }
        if (state.browse == Browse.FILES) {
            ToolButton(state.selectedSourcePath?.let { File(it).name } ?: "Choose file…", hasProject) {
                state.featureUsed("quick_open")
                state.quickOpenQuery = ""
                state.quickOpenVisible = true
            }
        }
        DropdownButton(
            "Compare",
            state.source,
            BuiltInSource.entries.toList(),
            ::sourceLabel,
            hasProject,
        ) { state.selectSource(it) }
        Spacer(Modifier.weight(1f))
    }
}

/** Window-level strip of opened project files, between the legend and the panes. */
@Composable
private fun TabStrip(state: AppState) {
    Row(
        Modifier.fillMaxWidth().background(tokens.panelHeader).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.openTabs.forEach { path ->
            Tab(
                label = File(path).name,
                active = state.selectedSourcePath == path,
                onClick = { state.selectSourceFile(path, "tab") },
                onClose = { state.closeTab(path) },
            )
        }
    }
    HairLine()
}

@Composable
private fun Tab(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val accentColor = tokens.accent
    Row(
        Modifier.height(32.dp)
            .background(if (active) tokens.panel else Color.Transparent)
            .drawBehind {
                // A 2px accent lid on the active tab, matching the New-UI editor tabs.
                if (active) drawRect(accentColor, size = Size(size.width, 2.dp.toPx()))
            }
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = if (active) tokens.text else tokens.textDim,
            fontSize = 11.5.sp,
            fontFamily = Type.Mono,
            maxLines = 1,
        )
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).hoverWash()
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            CloseIcon(tokens.textFaint)
        }
    }
    VerticalHairLine(32.dp)
}

@Composable
private fun LegendRow(state: AppState) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.legendHeight).background(tokens.panelHeader)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        LegendItem(tokens.changed, "Changed vs HEAD")
        LegendItem(tokens.new, "New in working copy")
        Spacer(Modifier.weight(1f))
        state.blockerText()?.let { Text(it, color = tokens.changed, fontSize = Type.small) }
    }
}

/**
 * Persistent footer: the running version and build badges on the left; on the right an update
 * trigger (or, once an update is in progress, the live status) when one is available.
 */
@Composable
private fun StatusBar(state: AppState) {
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(tokens.panelHeader).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Golden Diff ${AppTelemetry.appVersion}", color = tokens.textFaint, fontSize = Type.small)
        Spacer(Modifier.weight(1f))
        when {
            state.updateBusy -> UpdateProgress(state.updateStatus ?: "Updating…")
            state.updateCompleted -> Text(
                "Restart to finish update",
                color = tokens.accent,
                fontSize = Type.small,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickable { state.restartApp() },
            )
            state.update != null && state.updateStatus != null ->
                Text(state.updateStatus.orEmpty(), color = tokens.textDim, fontSize = Type.small, maxLines = 1)
            state.update != null -> Text(
                "Update available: ${state.update?.version}",
                color = tokens.accent,
                fontSize = Type.small,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickable { state.installUpdate() },
            )
        }
        if (AppTelemetry.metadata.isDeveloperBuild) {
            Text(
                "Diagnostics",
                color = tokens.textDim,
                fontSize = Type.small,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickable { state.diagnosticsVisible = true },
            )
        }
        BuildBadges(AppTelemetry.metadata)
    }
}

/** A small spinner beside the latest update status line. */
@Composable
private fun UpdateProgress(status: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(11.dp),
            color = tokens.accent,
            strokeWidth = 1.5.dp,
        )
        Text(status, color = tokens.textDim, fontSize = Type.small, maxLines = 1)
    }
}

/** One-time launch strip announcing a newer build; dismissable per version. */
@Composable
private fun UpdateBanner(state: AppState) {
    val update = state.update ?: return
    val actionLabel = if (state.updateViaHomebrew) "Update with Homebrew" else "Download update"
    Row(
        Modifier.fillMaxWidth().height(36.dp)
            .background(tokens.accent.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.updateCompleted) {
            // After an in-place update the new bundle is re-quarantined (not notarized), so offer to
            // clear it and relaunch in one click, with a plain restart as the alternative.
            Text("Golden Diff ${update.version} installed.", color = tokens.text, fontSize = Type.body)
            UpdateActionButton("Remove quarantine & restart", enabled = !state.updateBusy) {
                state.removeQuarantineAndRestart()
            }
            Text(
                "Just restart",
                color = tokens.accent,
                fontSize = Type.body,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickable { state.restartApp() },
            )
            if (state.updateBusy) {
                UpdateProgress(state.updateStatus ?: "…")
            } else {
                Text(
                    "macOS quarantines updated apps; this clears it so Golden Diff opens.",
                    color = tokens.textDim,
                    fontSize = Type.small,
                    maxLines = 1,
                )
            }
        } else {
            Text("Golden Diff ${update.version} is available.", color = tokens.text, fontSize = Type.body)
            UpdateActionButton(actionLabel, enabled = !state.updateBusy) { state.installUpdate() }
            Text(
                "Details",
                color = tokens.accent,
                fontSize = Type.body,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickable { state.openUpdatePage() },
            )
            if (state.updateBusy) {
                UpdateProgress(state.updateStatus ?: "Updating…")
            } else {
                state.updateStatus?.let { Text(it, color = tokens.textDim, fontSize = Type.body, maxLines = 1) }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).hoverWash()
                .clickable { state.dismissUpdateBanner() },
            contentAlignment = Alignment.Center,
        ) {
            CloseIcon(tokens.textFaint)
        }
    }
}

/** Compact bordered action used inside the update banner. */
@Composable
private fun UpdateActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Text(
        label,
        color = tokens.onAccent,
        fontSize = Type.body,
        modifier = Modifier.clip(shape)
            .background(tokens.accent.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

private fun AppState.blockerText(): String? = when (blocker) {
    Blocker.NoGit -> "git was not found on PATH — Golden Diff needs it to read committed versions."
    Blocker.NotARepository -> "This folder is not a git repository, so there is nothing to compare against."
    Blocker.NoGoldenDirectories -> "Open Settings and add at least one golden directory."
    null -> null
}

@Composable
private fun Body(state: AppState, modifier: Modifier) {
    when {
        state.projectRoot == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "No project open",
                body = "Open a project to start comparing screenshot goldens against Git HEAD.",
                actionLabel = "Open Project…",
                onAction = { chooseDirectory("Select project directory")?.let(state::openProject) },
            )
        }
        state.blocker != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "Nothing to compare yet",
                body = state.blockerText().orEmpty(),
                actionLabel = "Open Settings",
                onAction = { state.settingsVisible = true },
            )
        }
        else -> PaneRow(state, modifier)
    }
}

/** The three floating panes, with draggable splitters between them. */
@Composable
private fun PaneRow(state: AppState, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().padding(Dimens.panePadding)) {
        val available = maxWidth
        val maxProjectWidth = (available * 0.4f).coerceAtLeast(Dimens.leftPaneWidth)
        var projectWidth by remember(available) {
            mutableStateOf(Dimens.leftPaneWidth.coerceAtMost(maxProjectWidth))
        }
        val collapsed = state.ui.leftPaneCollapsed
        val usedByProject = if (collapsed) 24.dp else projectWidth
        val maxGoldensWidth = (available - usedByProject - 420.dp).coerceAtLeast(340.dp)
        var goldensWidth by remember(available) {
            mutableStateOf((available * 0.28f).coerceIn(340.dp, maxGoldensWidth))
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimens.paneGap)) {
            if (collapsed) {
                CollapsedProjectEdge(state, Modifier.fillMaxHeight())
            } else {
                ProjectPane(state, Modifier.width(projectWidth).fillMaxHeight())
                Splitter { projectWidth = (projectWidth + it).coerceIn(Dimens.leftPaneWidth, maxProjectWidth) }
            }
            GoldensPane(state, Modifier.width(goldensWidth.coerceAtMost(maxGoldensWidth)).fillMaxHeight())
            Splitter { goldensWidth = (goldensWidth + it).coerceIn(340.dp, maxGoldensWidth) }
            ComparePane(state, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** A 4px hit zone in the pane gap; it highlights accent while hovered. */
@Composable
private fun Splitter(onDrag: (Dp) -> Unit) {
    var active by remember { mutableStateOf(false) }
    Box(
        Modifier.width(4.dp).fillMaxHeight()
            .background(if (active) tokens.accent.copy(alpha = 0.4f) else Color.Transparent)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { active = true },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                ) { _, drag -> onDrag(drag.toDp()) }
            },
    )
}

/** Show in Finder / Copy Absolute Path / Delete, on tree rows and golden cards alike. */
@Composable
fun FileContextMenu(state: AppState, file: File, canDelete: Boolean, content: @Composable () -> Unit) {
    ContextMenuArea(
        items = {
            buildList {
                add(ContextMenuItem(showInFileManagerLabel()) {
                    state.featureUsed("reveal_in_file_manager")
                    RevealInFileManager.reveal(file)
                })
                add(ContextMenuItem("Copy Absolute Path") {
                    state.featureUsed("copy_path")
                    copyToClipboard(file.absolutePath)
                })
                if (canDelete && file.isFile) {
                    add(ContextMenuItem("Delete") {
                        val answer = JOptionPane.showConfirmDialog(
                            null,
                            "Delete ${file.name} from disk?",
                            "Delete Golden File",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE,
                        )
                        if (answer == JOptionPane.YES_OPTION) state.deleteFile(file)
                    })
                }
            }
        },
        content = content,
    )
}

private fun showInFileManagerLabel(): String = when (Os.current) {
    Os.MAC -> "Show in Finder"
    Os.WINDOWS -> "Show in Explorer"
    Os.LINUX -> "Show in File Manager"
}

internal fun chooseDirectory(title: String): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
