package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dkwasniak.goldendiff.app.ui.BuildBadges
import com.github.dkwasniak.goldendiff.app.ui.CloseIcon
import com.github.dkwasniak.goldendiff.app.ui.Chevron
import com.github.dkwasniak.goldendiff.app.ui.DarkTokens
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.Direction
import com.github.dkwasniak.goldendiff.app.ui.EmptyState
import com.github.dkwasniak.goldendiff.app.ui.DropdownButton
import com.github.dkwasniak.goldendiff.app.ui.CheckIcon
import com.github.dkwasniak.goldendiff.app.ui.FolderIcon
import com.github.dkwasniak.goldendiff.app.ui.GearIcon
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.PlayIcon
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
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import kotlinx.coroutines.delay

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
    val contextMenus = remember(palette) {
        ThemedContextMenuRepresentation(palette)
    }
    CompositionLocalProvider(
        LocalTokens provides palette,
        LocalContextMenuRepresentation provides contextMenus,
    ) {
        val materialColors = if (palette.dark) {
            darkColors(background = palette.background, surface = palette.panelHeader, primary = palette.accent)
        } else {
            lightColors(background = palette.background, surface = palette.panel, primary = palette.accent)
        }
        MaterialTheme(colors = materialColors) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().background(palette.background)) {
                    if (state.updateBannerVisible) {
                        UpdateBanner(state)
                        HairLine()
                    }
                    Toolbar(state)
                    HairLine()
                    LegendRow(state)
                    HairLine()
                    Body(state, Modifier.weight(1f))
                    HairLine()
                    StatusBar(state)
                }
                if (state.quickOpenVisible) QuickOpenDialog(state)
                state.acceptAllState?.let { AcceptAllOverlay(state, it) }
            }
        }
    }
}

@Composable
private fun AcceptAllOverlay(state: AppState, flow: AppState.AcceptAllState) {
    LaunchedEffect(flow) {
        if (flow is AppState.AcceptAllState.Success) {
            delay(2_500)
            state.dismissAcceptAll()
        }
    }
    Box(
        Modifier.fillMaxSize().background(tokens.scrim).clickable { state.dismissAcceptAll() },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(10.dp)
        Column(
            Modifier.widthIn(min = 360.dp, max = 520.dp).clip(shape).background(tokens.panel)
                .border(1.dp, tokens.borderStrong, shape)
                .clickable(enabled = false) {}
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (flow) {
                is AppState.AcceptAllState.Confirm -> {
                    Text("Accept all Test outputs?", color = tokens.text, fontSize = Type.large, fontWeight = FontWeight.Bold)
                    Text(
                        "${flow.candidates.size} snapshot(s) associated with ${state.selectedSourcePath?.let { File(it).name }.orEmpty()} will replace their goldens.",
                        color = tokens.textDim,
                        fontSize = Type.body,
                    )
                    ModalActions(
                        secondary = "Cancel" to state::dismissAcceptAll,
                        primary = "Accept all (${flow.candidates.size})" to { state.confirmAcceptAll(flow.candidates) },
                    )
                }
                is AppState.AcceptAllState.Progress -> {
                    Text("Accepting snapshots…", color = tokens.text, fontSize = Type.large, fontWeight = FontWeight.Bold)
                    Text("Accepting ${flow.completed} of ${flow.total}…", color = tokens.textDim, fontSize = Type.body)
                    CircularProgressIndicator(Modifier.size(18.dp), color = tokens.accent, strokeWidth = 2.dp)
                }
                is AppState.AcceptAllState.Success -> {
                    Text("All ${flow.count} snapshots accepted", color = tokens.new, fontSize = Type.large, fontWeight = FontWeight.Bold)
                    ModalActions(primary = "Done" to state::dismissAcceptAll)
                }
                is AppState.AcceptAllState.Partial -> {
                    val first = flow.failed.first()
                    Text("Some snapshots could not be accepted", color = tokens.changed, fontSize = Type.large, fontWeight = FontWeight.Bold)
                    Text(
                        "${flow.accepted} of ${flow.total} accepted. ${first.golden.name} could not be written.",
                        color = tokens.textDim,
                        fontSize = Type.body,
                    )
                    ModalActions(
                        secondary = "Close" to state::dismissAcceptAll,
                        primary = "Retry remaining" to { state.retryAcceptAll(flow.failed) },
                    )
                }
                AppState.AcceptAllState.NothingToAccept -> {
                    Text("Nothing to accept", color = tokens.text, fontSize = Type.large, fontWeight = FontWeight.Bold)
                    Text("No differing Test outputs remain for this file.", color = tokens.textDim, fontSize = Type.body)
                    ModalActions(primary = "Done" to state::dismissAcceptAll)
                }
            }
        }
    }
}

@Composable
private fun ModalActions(
    secondary: Pair<String, () -> Unit>? = null,
    primary: Pair<String, () -> Unit>,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        secondary?.let { (label, action) ->
            Text(label, color = tokens.textDim, fontSize = Type.body, modifier = Modifier.clickable(onClick = action).padding(9.dp))
        }
        Text(
            primary.first,
            color = Color.White,
            fontSize = Type.body,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(Dimens.controlRadius)).background(tokens.changed)
                .clickable(onClick = primary.second).padding(horizontal = 12.dp, vertical = 8.dp),
        )
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
        ProjectMenuButton(state)
        VerticalHairLine(20.dp)
        ToolButton(state.selectedSourcePath?.let { File(it).name } ?: "Choose file…", hasProject) {
            state.featureUsed("quick_open")
            state.quickOpenQuery = ""
            state.quickOpenVisible = true
        }
        Spacer(Modifier.weight(1f))
        SettingsGearButton(state)
    }
}

/** The gear on the far right of the toolbar; opens Settings. Disabled until a project is open. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsGearButton(state: AppState) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Box(
        Modifier.size(28.dp).clip(shape).hoverWash(true, shape)
            .clickable { state.settingsVisible = true },
        contentAlignment = Alignment.Center,
    ) {
        GearIcon(tokens.textDim)
    }
}

/**
 * The grouped Generate | Verify pair that lives on the right of the legend row and drives the whole
 * project. The two buttons share one bordered pill; only one command runs at a time, so while either
 * runs it shows a spinner and the other dims and goes inert. Each button's caret opens the per-scope
 * menu (*Whole project* / *Current file*); the button body itself runs the whole project.
 */
@Composable
private fun CommandGroup(state: AppState) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Row(
        Modifier.clip(shape).border(1.dp, tokens.border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommandSegment(state, AppState.CommandKind.GENERATE)
        Box(Modifier.width(1.dp).height(26.dp).background(tokens.border))
        CommandSegment(state, AppState.CommandKind.VERIFY)
    }
}

/** One half of the [CommandGroup]. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CommandSegment(state: AppState, kind: AppState.CommandKind) {
    var expanded by remember { mutableStateOf(false) }
    val hasProject = state.projectRoot != null
    val hasAll = state.hasCommand(kind, perFile = false)
    val hasFile = state.hasCommand(kind, perFile = true)
    val available = hasProject && (hasAll || hasFile)
    val running = state.commandRunning && state.commandKind == kind && !state.commandPerFile
    val otherRunning = state.commandRunning && !running
    val disabled = !available
    val inert = disabled || otherRunning

    val fg = when {
        running -> tokens.accent
        inert -> tokens.textFaint
        else -> tokens.text
    }
    val alpha = when {
        disabled -> 0.4f
        otherRunning -> 0.45f
        else -> 1f
    }
    val tip = when {
        !hasProject -> "Open a project first."
        !available -> "Set a ${kind.label} command in Settings → Commands."
        running -> "Running — click to view the log."
        otherRunning -> "A screenshot command is already running."
        else -> "Whole project — caret for Current file"
    }

    Box {
        val body = @Composable {
            Row(
                Modifier.height(26.dp)
                    .background(if (running) tokens.accentBg else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.fillMaxHeight()
                        .hoverWash(enabled = !inert, shape = RoundedCornerShape(0.dp))
                        .clickable(enabled = running || !inert) {
                            when {
                                running -> state.commandLogVisible = true
                                hasAll -> state.runCommand(kind, perFile = false)
                                else -> expanded = true
                            }
                        }
                        .padding(start = 10.dp, end = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(11.dp), color = tokens.accent, strokeWidth = 1.6.dp)
                    } else if (kind == AppState.CommandKind.GENERATE) {
                        PlayIcon(fg.copy(alpha = alpha))
                    } else {
                        CheckIcon(if (inert) fg.copy(alpha = alpha) else tokens.new)
                    }
                    Text(kind.label, color = fg.copy(alpha = alpha), fontSize = Type.body, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier.width(28.dp).fillMaxHeight()
                        .hoverWash(enabled = !inert, shape = RoundedCornerShape(0.dp))
                        .clickable(enabled = !inert) { expanded = true }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Chevron(Direction.DOWN, tokens.textFaint.copy(alpha = alpha), size = 10.dp)
                }
            }
        }
        TooltipArea(tooltip = { TipBubble(tip) }) { body() }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                enabled = hasAll && !state.commandRunning,
                onClick = { expanded = false; state.runCommand(kind, perFile = false) },
            ) { Text("Whole project", color = tokens.text, fontSize = Type.body) }
            val fileName = state.selectedSourcePath?.let { File(it).name }
            val fileExists = state.selectedSourcePath
                ?.let { path -> state.projectRoot?.let { root -> File(root, path).isFile } }
                ?: false
            DropdownMenuItem(
                enabled = hasFile && fileExists && !state.commandRunning,
                onClick = { expanded = false; state.runCommand(kind, perFile = true) },
            ) {
                Text(
                    when {
                        fileName == null -> "Current file (open a file)"
                        !fileExists -> "Current file: $fileName (missing)"
                        else -> "Current file: $fileName"
                    },
                    color = tokens.text,
                    fontSize = Type.body,
                )
            }
        }
    }
}

/** Small tooltip bubble used for disabled command buttons. */
@Composable
internal fun TipBubble(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(Dimens.controlRadius)).background(tokens.panel)
            .border(1.dp, tokens.border, RoundedCornerShape(Dimens.controlRadius))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = tokens.textDim, fontSize = Type.small)
    }
}

/**
 * Status-bar chip for the last screenshot-test command: a spinner + label + latest line + Stop while
 * it runs, then the final outcome kept in place with a ✕ to dismiss it. Clicking the chip opens the
 * full log window. Hidden once dismissed (or before any command has run).
 */
@Composable
private fun CommandStatusChip(state: AppState) {
    val label = state.commandLabel ?: return
    if (!state.commandRunning && state.commandStatusDismissed) return
    val running = state.commandRunning
    // Trim the small font's leading so glyphs sit on the row's optical centre, aligned with the
    // spinner/stop icons instead of riding a couple of pixels below them.
    val chipText = TextStyle(
        fontSize = Type.small,
        lineHeight = Type.small,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
    Row(
        Modifier.clip(RoundedCornerShape(Dimens.controlRadius)).hoverWash()
            .clickable { state.commandLogVisible = true }.padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (running) {
            CircularProgressIndicator(Modifier.size(11.dp), color = tokens.accent, strokeWidth = 1.5.dp)
        }
        Text(
            label,
            color = if (running) tokens.accent else tokens.textDim,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            style = chipText,
        )
        state.commandStatus?.let {
            Text(
                it,
                color = tokens.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = chipText,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).hoverWash()
                .clickable { if (running) state.cancelCommand() else state.dismissCommandStatus() },
            contentAlignment = Alignment.Center,
        ) {
            if (running) StopIcon(tokens.changed) else CloseIcon(tokens.textDim)
        }
    }
}

/** A small filled square: the Stop glyph for a running command. */
@Composable
private fun StopIcon(color: Color) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(1.dp)).background(color))
}

/**
 * Always-visible project control at the far left of the toolbar: the current project name, a menu of
 * recently opened projects for a one-click switch, and a picker for any other directory. Replaces the
 * old "Open project…" button that vanished once a project was open, stranding the user on one project.
 */
@Composable
private fun ProjectMenuButton(state: AppState) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Dimens.controlRadius)
    val current = state.projectRoot
    // Read from disk only while the menu is open; the list is short and rarely changes.
    val recents = remember(expanded, current) { if (expanded) AppConfig.recentProjects() else emptyList() }
    Box {
        Row(
            Modifier.clip(shape).border(1.dp, tokens.border, shape).hoverWash(shape = shape)
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderIcon(tokens.textFaint)
            Spacer(Modifier.width(7.dp))
            Text(
                current?.name ?: "Open project…",
                color = tokens.text,
                fontSize = Type.body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.width(5.dp))
            Chevron(Direction.DOWN, tokens.textFaint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            recents.forEach { project ->
                val isCurrent = project.absolutePath == current?.absolutePath
                DropdownMenuItem(onClick = {
                    expanded = false
                    if (!isCurrent) state.openProject(project)
                }) {
                    Column {
                        Text(
                            project.name,
                            color = if (isCurrent) tokens.accent else tokens.text,
                            fontSize = Type.body,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            project.absolutePath,
                            color = tokens.textFaint,
                            fontSize = Type.small,
                            fontFamily = Type.Mono,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (recents.isNotEmpty()) Divider(color = tokens.border)
            DropdownMenuItem(onClick = {
                expanded = false
                chooseDirectory("Select project directory")?.let(state::openProject)
            }) {
                Text("Open other project…", color = tokens.text, fontSize = Type.body)
            }
        }
    }
}

@Composable
private fun LegendRow(state: AppState) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.legendHeight).background(tokens.panelHeader)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        LegendItem(tokens.changed, "Failed — Test output")
        LegendItem(tokens.modified, "Modified — Working copy")
        LegendItem(tokens.new, "New — Working copy")
        Spacer(Modifier.weight(1f))
        state.blockerText()?.let {
            Text(it, color = tokens.changed, fontSize = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        if (state.projectRoot != null) CommandGroup(state)
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
        CommandStatusChip(state)
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

private val MinResizablePaneWidth = 120.dp
private val SplitterWidth = 4.dp

/** The three floating panes, with draggable splitters between them. */
@Composable
private fun PaneRow(state: AppState, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().padding(Dimens.panePadding)) {
        val available = maxWidth
        val collapsed = state.ui.leftPaneCollapsed
        val expandedChromeWidth = SplitterWidth * 2 + Dimens.paneGap * 4
        val availableProjectWidth = (
            available - MinResizablePaneWidth * 2 - expandedChromeWidth
            ).coerceAtLeast(MinResizablePaneWidth)
        var projectWidth by remember { mutableStateOf(state.ui.projectPaneWidth.dp) }
        val visibleProjectWidth = projectWidth.coerceIn(MinResizablePaneWidth, availableProjectWidth)
        val usedByProject = if (collapsed) 24.dp else visibleProjectWidth
        val chromeWidth = if (collapsed) {
            SplitterWidth + Dimens.paneGap * 3
        } else {
            expandedChromeWidth
        }
        val availableGoldensWidth = (
            available - usedByProject - MinResizablePaneWidth - chromeWidth
            ).coerceAtLeast(MinResizablePaneWidth)
        var goldensWidth by remember {
            mutableStateOf(state.ui.goldensPaneWidth.dp)
        }
        val visibleGoldensWidth = goldensWidth.coerceIn(MinResizablePaneWidth, availableGoldensWidth)

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimens.paneGap)) {
            if (collapsed) {
                CollapsedProjectEdge(state, Modifier.fillMaxHeight())
            } else {
                ProjectPane(state, Modifier.width(visibleProjectWidth).fillMaxHeight())
                Splitter(
                    onDrag = {
                        projectWidth =
                            (visibleProjectWidth + it).coerceIn(MinResizablePaneWidth, availableProjectWidth)
                    },
                    onDragFinished = {
                        state.ui.rememberProjectPaneWidth(
                            projectWidth.coerceIn(MinResizablePaneWidth, availableProjectWidth).value.toInt(),
                        )
                    },
                )
            }
            GoldensPane(state, Modifier.width(visibleGoldensWidth).fillMaxHeight())
            Splitter(
                onDrag = {
                    goldensWidth =
                        (visibleGoldensWidth + it).coerceIn(MinResizablePaneWidth, availableGoldensWidth)
                },
                onDragFinished = {
                    state.ui.rememberGoldensPaneWidth(
                        goldensWidth.coerceIn(MinResizablePaneWidth, availableGoldensWidth).value.toInt(),
                    )
                },
            )
            ComparePane(state, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** A 4px hit zone in the pane gap; it highlights accent while hovered. */
@Composable
private fun Splitter(onDrag: (Dp) -> Unit, onDragFinished: () -> Unit) {
    var active by remember { mutableStateOf(false) }
    val currentOnDrag = rememberUpdatedState(onDrag)
    val currentOnDragFinished = rememberUpdatedState(onDragFinished)
    Box(
        Modifier.width(SplitterWidth).fillMaxHeight()
            .background(if (active) tokens.accent.copy(alpha = 0.4f) else Color.Transparent)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { active = true },
                    onDragEnd = {
                        active = false
                        currentOnDragFinished.value()
                    },
                    onDragCancel = { active = false },
                ) { _, drag -> currentOnDrag.value(drag.toDp()) }
            },
    )
}

/** Host-specific actions plus Show in Finder / Copy Absolute Path / Delete. */
@Composable
fun FileContextMenu(
    state: AppState,
    file: File,
    canDelete: Boolean,
    title: String = file.name,
    additionalItems: () -> List<ContextMenuItem> = { emptyList() },
    content: @Composable () -> Unit,
) {
    ContextMenuArea(
        items = {
            buildList {
                add(HeaderContextMenuItem(title))
                val custom = additionalItems()
                addAll(custom)
                if (custom.isNotEmpty()) add(SeparatorContextMenuItem())
                add(ActionContextMenuItem(showInFileManagerLabel(), ContextActionIcon.FINDER) {
                    state.featureUsed("reveal_in_file_manager")
                    RevealInFileManager.reveal(file)
                })
                add(ActionContextMenuItem("Copy Absolute Path", ContextActionIcon.COPY) {
                    state.featureUsed("copy_path")
                    copyToClipboard(file.absolutePath)
                })
                if (canDelete && file.isFile) {
                    add(SeparatorContextMenuItem())
                    add(ActionContextMenuItem("Delete", ContextActionIcon.DELETE) {
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

internal fun chooseDirectory(title: String): File? = when (Os.current) {
    // The native NSOpenPanel in folder-only mode — no "File Format" row, no greyed-out files, just a
    // clean directory picker. AWT exposes it through FileDialog once this Apple-only flag is set.
    Os.MAC -> chooseDirectoryNativeMac(title)
    // FileDialog cannot select directories on Windows/Linux, so fall back to Swing's chooser there.
    else -> chooseDirectorySwing(title)
}

private fun chooseDirectoryNativeMac(title: String): File? {
    val key = "apple.awt.fileDialogForDirectories"
    val previous = System.getProperty(key)
    System.setProperty(key, "true")
    return try {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val name = dialog.file ?: return null
        File(directory, name)
    } finally {
        // Leave the process-wide flag as we found it, so ordinary file dialogs keep picking files.
        if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
    }
}

private fun chooseDirectorySwing(title: String): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
