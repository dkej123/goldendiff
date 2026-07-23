package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.github.dkwasniak.goldendiff.app.ui.Appearance
import com.github.dkwasniak.goldendiff.app.ui.DarkTokens
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.GhostButton
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.LightTokens
import com.github.dkwasniak.goldendiff.app.ui.LocalTokens
import com.github.dkwasniak.goldendiff.app.ui.PrimaryButton
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens
import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig

/** The sections of the settings window, in nav order. */
private enum class SettingsSection(val label: String) {
    DIRECTORIES("Directories"),
    OUTPUT_MATCHING("Test output matching"),
    MATCHING("Golden matching"),
    FILTERING("Filtering"),
    DISPLAY("Display"),
    PRIVACY("Privacy");

    /** The live match preview is meaningless for the appearance/trim controls. */
    val showsPreview: Boolean get() = this != DISPLAY && this != PRIVACY
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
    Window(
        onCloseRequest = onClose,
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
                var section by remember { mutableStateOf(SettingsSection.DIRECTORIES) }
                Column(Modifier.fillMaxSize().background(tokens.background)) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.width(210.dp).fillMaxHeight().padding(vertical = 10.dp)) {
                            SettingsSection.entries.forEach { candidate ->
                                NavItem(candidate.label, section == candidate) { section = candidate }
                            }
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(tokens.border))
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                                .padding(horizontal = 28.dp, vertical = 22.dp),
                        ) {
                            when (section) {
                                SettingsSection.DIRECTORIES -> DirectoriesSection(state)
                                SettingsSection.OUTPUT_MATCHING -> OutputMatchingSection(state)
                                SettingsSection.MATCHING -> MatchingSection(state)
                                SettingsSection.FILTERING -> FilteringSection(state)
                                SettingsSection.DISPLAY -> DisplaySection(state)
                                SettingsSection.PRIVACY -> PrivacySection()
                            }
                        }
                        if (section.showsPreview) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(tokens.border))
                            PreviewPanel(state, Modifier.width(260.dp).fillMaxHeight())
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
                        Spacer(Modifier.weight(1f))
                        GhostButton("Cancel", onClose)
                        PrimaryButton("Done", onClick = onClose)
                    }
                }
            }
        }
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

@Composable
private fun DirectorySection(
    title: String,
    description: String,
    paths: List<String>,
    chooserTitle: String,
    state: AppState,
    withPaths: (List<String>) -> GoldenDiffConfig,
) {
    SectionHeader(title, description)
    paths.forEach { path ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .border(1.dp, tokens.border, RoundedCornerShape(Dimens.controlRadius))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                path,
                color = tokens.text,
                fontSize = Type.body,
                fontFamily = Type.Mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Remove",
                color = tokens.textFaint,
                fontSize = 11.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).hoverWash()
                    .clickable { state.updateConfig(withPaths(paths - path)) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    if (paths.isEmpty()) {
        Text("None configured yet.", color = tokens.textFaint, fontSize = Type.body, modifier = Modifier.padding(bottom = 8.dp))
    }
    Text(
        "+ Add directory",
        color = tokens.accent,
        fontSize = Type.body,
        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(4.dp)).hoverWash()
            .clickable {
                val root = state.projectRoot ?: return@clickable
                chooseDirectory(chooserTitle)?.let { directory ->
                    // Stored relative to the project so the same config survives a moved checkout.
                    val relative = directory.relativeToOrNull(root)?.path ?: directory.absolutePath
                    if (relative !in paths) state.updateConfig(withPaths(paths + relative))
                }
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun DirectoriesSection(state: AppState) {
    DirectorySection(
        title = "Golden directories",
        description = "Directories holding the committed screenshots Golden Diff compares against. " +
            "Paths are stored relative to the project root.",
        paths = state.config.goldenPaths,
        chooserTitle = "Select golden directory",
        state = state,
    ) { paths -> state.config.copy(goldenPaths = paths) }

    Spacer(Modifier.height(20.dp))

    DirectorySection(
        title = "Generated output directories",
        description = "Where verification tests write their screenshots. Only needed when comparing " +
            "against test output instead of the working copy.",
        paths = state.config.generatedPaths,
        chooserTitle = "Select generated output directory",
        state = state,
    ) { paths -> state.config.copy(generatedPaths = paths) }
}

@Composable
private fun OutputMatchingSection(state: AppState) {
    SectionHeader(
        "Test output matching",
        "Used only after you select a golden and choose Compare: Test output. The first capture " +
            "group must be the matching golden's base name.",
    )
    FieldLabel("Generated output filename regex")
    MonoField(value = state.config.generatedFileRegex, enabled = true, singleLine = true) { text ->
        // A blank regex would match nothing and silently empty the list, so it is simply not saved.
        if (text.isNotBlank()) state.updateConfig(state.config.copy(generatedFileRegex = text))
    }
}

@Composable
private fun MatchingSection(state: AppState) {
    SectionHeader(
        "Golden matching",
        "Match goldens either by the name of annotated screenshot functions, or by a file/class regex.",
    )
    val annotated = state.config.matchMode == MatchMode.ANNOTATED_METHOD
    RadioRow("Match by annotated method", annotated) {
        state.updateConfig(state.config.copy(matchMode = MatchMode.ANNOTATED_METHOD))
    }
    RadioRow("Match by file/class regex", !annotated) {
        state.updateConfig(state.config.copy(matchMode = MatchMode.FILE_CLASS_REGEX))
    }

    if (annotated) {
        FieldLabel("Screenshot annotation name regex")
        Text(
            "Annotated functions whose annotation name matches become golden candidates; the golden " +
                "must contain the function name.",
            color = tokens.textDim,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        MonoField(value = state.config.annotatedFunctionRegex, enabled = true, singleLine = true) { text ->
            if (text.isNotBlank()) state.updateConfig(state.config.copy(annotatedFunctionRegex = text))
        }
    } else {
        FieldLabel("Golden filename regex (one per line)")
        Text(
            "Matched against each golden's path. {file_name} and {class_name} are substituted first.",
            color = tokens.textDim,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        MonoField(
            value = state.config.goldenFilePatterns.joinToString("\n"),
            enabled = true,
            singleLine = false,
        ) { text ->
            val patterns = text.split('\n').map(String::trim).filter(String::isNotEmpty)
            state.updateConfig(state.config.copy(goldenFilePatterns = patterns))
        }
    }
}

@Composable
private fun FilteringSection(state: AppState) {
    SectionHeader(
        "Filtering",
        "Files ending with these suffixes are hidden from the golden list — for example Roborazzi " +
            "_actual and _compare outputs.",
    )
    FieldLabel("Excluded golden suffixes")
    Text(
        "Comma-separated.",
        color = tokens.textDim,
        fontSize = 11.5.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    MonoField(value = state.config.excludedSuffixes.joinToString(", "), enabled = true, singleLine = true) { text ->
        val suffixes = text.split(',').map(String::trim).filter(String::isNotEmpty)
        state.updateConfig(state.config.copy(excludedSuffixes = suffixes))
    }
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
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Preview (live)", color = tokens.text, fontSize = Type.panelTitle, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (state.busy) "…" else "${state.items.size}",
                color = tokens.textFaint,
                fontSize = Type.small,
            )
        }
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

@Composable
private fun DisplaySection(state: AppState) {
    SectionHeader("Display", "How images and the application itself are presented.")
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Toggle(state.config.trimTransparentPadding) {
            state.updateConfig(state.config.copy(trimTransparentPadding = !state.config.trimTransparentPadding))
        }
        Column {
            Text("Trim transparent padding around image content", color = tokens.text, fontSize = Type.panelTitle)
            Text(
                "Crops fully transparent borders. Off by default; images are otherwise shown exactly as stored.",
                color = tokens.textDim,
                fontSize = 11.5.sp,
            )
        }
    }

    FieldLabel("Appearance")
    Appearance.entries.forEach { candidate ->
        RadioRow(candidate.label, state.ui.appearance == candidate) { state.ui.selectAppearance(candidate) }
    }
}

@Composable
private fun PrivacySection() {
    val settings = AppTelemetry.settings
    SectionHeader(
        "Privacy",
        "Golden Diff sends nothing unless you opt in. Product analytics and diagnostic error reports " +
            "are independent and never include project names, file names, paths, source code or images.",
    )
    ConsentRow(
        title = "Share anonymous product analytics",
        description = "Feature adoption and coarse operation-duration buckets.",
        checked = settings.analyticsEnabled,
    ) {
        settings.setAnalytics(!settings.analyticsEnabled)
        AppTelemetry.updateConsent()
    }
    Spacer(Modifier.height(12.dp))
    ConsentRow(
        title = "Share diagnostic error reports",
        description = "Sanitized crashes and unexpected Golden Diff errors.",
        checked = settings.diagnosticsEnabled,
    ) {
        settings.setDiagnostics(!settings.diagnosticsEnabled)
        AppTelemetry.updateConsent()
    }
    Spacer(Modifier.height(20.dp))
    Text(
        "Privacy policy",
        color = tokens.accent,
        fontSize = Type.body,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).hoverWash().clickable {
            runCatching {
                java.awt.Desktop.getDesktop().browse(
                    java.net.URI("https://github.com/dkej123/goldendiff/blob/main/docs/privacy.md"),
                )
            }
        }.padding(horizontal = 4.dp, vertical = 3.dp),
    )
}

@Composable
private fun ConsentRow(title: String, description: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.controlRadius)).hoverWash()
            .clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Toggle(checked, onClick)
        Column {
            Text(title, color = tokens.text, fontSize = Type.panelTitle)
            Text(description, color = tokens.textDim, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun SectionHeader(title: String, description: String) {
    Text(title, color = tokens.text, fontSize = Type.large, fontWeight = FontWeight.Bold)
    Text(
        description,
        color = tokens.textDim,
        fontSize = 11.5.sp,
        lineHeight = 17.sp,
        modifier = Modifier.widthIn(max = 480.dp).padding(top = 6.dp, bottom = 16.dp),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        color = tokens.text,
        fontSize = Type.body,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(Dimens.controlRadius)).hoverWash().clickable(onClick = onSelect)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(14.dp).clip(RoundedCornerShape(50))
                .background(tokens.background)
                .border(
                    if (selected) 4.dp else 1.5.dp,
                    if (selected) tokens.accent else tokens.textFaint,
                    RoundedCornerShape(50),
                ),
        )
        Text(label, color = tokens.text, fontSize = Type.panelTitle)
    }
}

@Composable
private fun Toggle(checked: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.padding(top = 2.dp).size(width = 32.dp, height = 18.dp).clip(RoundedCornerShape(9.dp))
            .background(if (checked) tokens.accent else tokens.borderStrong)
            .clickable(onClick = onToggle),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(horizontal = 2.dp).size(14.dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

@Composable
private fun MonoField(value: String, enabled: Boolean, singleLine: Boolean, onValueChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val shape = RoundedCornerShape(Dimens.controlRadius)
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        enabled = enabled,
        singleLine = singleLine,
        textStyle = TextStyle(
            color = tokens.text.copy(alpha = if (enabled) 1f else 0.4f),
            fontSize = Type.body,
            fontFamily = Type.Mono,
        ),
        cursorBrush = SolidColor(tokens.accent),
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(shape)
            .background(tokens.inputBackground).border(1.dp, tokens.border, shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}
