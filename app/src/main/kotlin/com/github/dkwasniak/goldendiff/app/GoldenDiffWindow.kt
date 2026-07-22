package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dkwasniak.goldendiff.compare.ImageLayout
import com.github.dkwasniak.goldendiff.naming.shortGoldenName
import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import com.github.dkwasniak.goldendiff.ui.CompareMode
import com.github.dkwasniak.goldendiff.ui.OnionSkinView
import com.github.dkwasniak.goldendiff.ui.SingleImageView
import com.github.dkwasniak.goldendiff.ui.SwipeView
import com.github.dkwasniak.goldendiff.ui.TwoUpView
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import java.io.File
import javax.swing.JFileChooser

private val Background = Color(0xFF2B2D30)
private val Panel = Color(0xFF1E1F22)
private val Border = Color(0xFF393B40)
private val TextPrimary = Color(0xFFDFE1E5)
private val TextMuted = Color(0xFF9DA0A8)
private val Accent = Color(0xFF3574F0)
private val StatusModified = Color(0xFFD36A75)
private val StatusNew = Color(0xFF57A869)

@Composable
fun GoldenDiffWindow(state: AppState) {
    MaterialTheme(colors = darkColors(background = Background, surface = Panel, primary = Accent)) {
        Box(Modifier.fillMaxSize().background(Background)) {
            Column(Modifier.fillMaxSize()) {
                Header(state)
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    LeftPane(state, Modifier.width(320.dp).fillMaxHeight())
                    VerticalDivider()
                    GoldenList(state, Modifier.width(280.dp).fillMaxHeight())
                    VerticalDivider()
                    ComparePane(state, Modifier.weight(1f).fillMaxHeight())
                }
                StatusBar(state)
            }
            if (state.quickOpenVisible) QuickOpen(state)
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Border))
}

@Composable
private fun Header(state: AppState) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button("Open project…") { chooseDirectory()?.let(state::openProject) }
        Text(
            state.projectRoot?.absolutePath ?: "No project open",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Toggle("Working copy", state.source == BuiltInSource.WORKING_COPY) {
            state.source = BuiltInSource.WORKING_COPY
            state.refresh()
        }
        Toggle("Test output", state.source == BuiltInSource.GENERATED) {
            state.source = BuiltInSource.GENERATED
            state.refresh()
        }
        Button("Refresh") { state.refresh() }
    }
}

@Composable
private fun LeftPane(state: AppState, modifier: Modifier) {
    Column(modifier.background(Panel)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Browse.entries.forEach { mode ->
                Toggle(mode.label, state.browse == mode) { state.browse = mode }
            }
        }
        when (state.browse) {
            Browse.CHANGED -> GoldenDirectories(state)
            Browse.FILES -> FileTree(state)
        }
    }
}

@Composable
private fun GoldenDirectories(state: AppState) {
    Column(Modifier.padding(8.dp)) {
        Text("Golden directories", color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        state.config.goldenPaths.forEach { Text(it, color = TextPrimary, fontSize = 12.sp) }
        Spacer(Modifier.height(6.dp))
        Button("Add golden directory…") {
            val root = state.projectRoot ?: return@Button
            chooseDirectory()?.let { dir ->
                val relative = dir.relativeToOrNull(root)?.path ?: dir.absolutePath
                state.updateConfig(state.config.copy(goldenPaths = state.config.goldenPaths + relative))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Generated output", color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        state.config.generatedPaths.forEach { Text(it, color = TextPrimary, fontSize = 12.sp) }
        Spacer(Modifier.height(6.dp))
        Button("Add generated directory…") {
            val root = state.projectRoot ?: return@Button
            chooseDirectory()?.let { dir ->
                val relative = dir.relativeToOrNull(root)?.path ?: dir.absolutePath
                state.updateConfig(state.config.copy(generatedPaths = state.config.generatedPaths + relative))
            }
        }
    }
}

@Composable
private fun FileTree(state: AppState) {
    val index = state.fileIndex
    if (index == null) {
        Text("Indexing…", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(index.paths) { path ->
            Text(
                path,
                color = TextPrimary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.selectSourceFile(path) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun GoldenList(state: AppState, modifier: Modifier) {
    Column(modifier.background(Panel)) {
        state.blocker?.let { blocker ->
            Text(
                when (blocker) {
                    Blocker.NoGit -> "git was not found on your PATH. Golden Diff needs it to read " +
                        "committed versions. Install git (on macOS: xcode-select --install) and refresh."
                    Blocker.NotARepository -> "This folder is not a git repository, so there is nothing " +
                        "to compare against."
                    Blocker.NoGoldenDirectories -> "Add at least one golden directory to get started."
                },
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(10.dp),
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.items) { item ->
                val isSelected = state.selected == item.file
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) Accent.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { state.select(item.file) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "●",
                        color = when (item.status) {
                            ExtraComparisonItemStatus.MODIFIED -> StatusModified
                            ExtraComparisonItemStatus.NEW -> StatusNew
                            ExtraComparisonItemStatus.UNCHANGED -> TextMuted
                        },
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(shortGoldenName(item.title), color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ComparePane(state: AppState, modifier: Modifier) {
    var mode by remember { mutableStateOf(CompareMode.TWO_UP) }
    var zoom by remember { mutableStateOf(ImageLayout.FIT) }
    var opacity by remember { mutableStateOf(0.5f) }
    val comparison = state.comparison

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompareMode.entries.forEach { m -> Toggle(m.label, mode == m) { mode = m } }
            Spacer(Modifier.weight(1f))
            Toggle("Fit", zoom == ImageLayout.FIT) { zoom = ImageLayout.FIT }
            listOf(1.0, 2.0, 4.0).forEach { z ->
                Toggle("${(z * 100).toInt()}%", zoom == z) { zoom = z }
            }
        }
        Text(
            comparison?.statusText ?: "Select a golden",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(Modifier.fillMaxSize()) {
            when {
                comparison == null -> Unit
                comparison.identical || comparison.old == null || comparison.new == null ->
                    SingleImageView(comparison.new ?: comparison.old, zoom)
                mode == CompareMode.TWO_UP -> TwoUpView(comparison.old, comparison.new, zoom)
                mode == CompareMode.SWIPE -> SwipeView(comparison.old, comparison.new, zoom)
                mode == CompareMode.ONION -> OnionSkinView(comparison.old, comparison.new, zoom, opacity)
                mode == CompareMode.DIFF -> SingleImageView(comparison.diff, zoom)
            }
            if (mode == CompareMode.ONION && comparison?.identical == false) {
                Row(Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
                    listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { value ->
                        Toggle("${(value * 100).toInt()}%", opacity == value) { opacity = value }
                    }
                }
            }
            if (mode == CompareMode.DIFF && comparison?.diff != null) {
                Text(
                    "%.2f%% pixels changed".format(comparison.changedRatio * 100),
                    color = TextPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusBar(state: AppState) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (state.busy) "Working…" else state.status, color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("${Shortcuts.quickOpenLabel} to find a file", color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun QuickOpen(state: AppState) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 120.dp)
                .width(620.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
                .padding(12.dp),
        ) {
            BasicTextField(
                value = state.quickOpenQuery,
                onValueChange = { state.quickOpenQuery = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(TextPrimary),
                modifier = Modifier.fillMaxWidth().padding(6.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.height(320.dp)) {
                itemsIndexed(state.quickOpenResults) { index, path ->
                    Text(
                        path,
                        color = if (index == 0) TextPrimary else TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                state.quickOpenVisible = false
                                state.browse = Browse.FILES
                                state.selectSourceFile(path)
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = TextPrimary,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Border)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun Toggle(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color.White else TextMuted,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) Accent else Border)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/**
 * Swing's directory chooser, used deliberately.
 *
 * Compose Desktop has no native folder picker, and this is the same AWT/Swing interop the toolkit
 * already runs on — it is not a leftover from the plugin's UI.
 */
private fun chooseDirectory(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Select project directory"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
