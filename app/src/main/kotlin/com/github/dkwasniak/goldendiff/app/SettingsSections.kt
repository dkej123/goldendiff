package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dkwasniak.goldendiff.app.ui.Appearance
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.ToolButton
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens
import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.match.MatchingDefaults
import com.github.dkwasniak.goldendiff.settings.GoldenDiffConfig
import com.github.dkwasniak.goldendiff.settings.GoldenDiffDefaults

/**
 * The settings form, decomposed into one composable per section plus the small controls they share.
 *
 * These are `internal` (not `private`) on purpose: both the Settings window and the first-run
 * onboarding walkthrough render the exact same sections, so the two hosts stay pixel-identical and
 * a change to a control only has to be made once.
 */

// region Directory sections

@Composable
internal fun GoldenDirectoriesSection(state: AppState) {
    DirectorySection(
        title = "Golden directories",
        description = "Directories holding the committed screenshots Golden Diff compares against. " +
            "Paths are stored relative to the project root. CLI directory regexes are shown with a Regex badge.",
        paths = state.config.goldenPaths,
        chooserTitle = "Select golden directory",
        state = state,
        allowRegex = true,
    ) { paths -> state.config.copy(goldenPaths = paths) }
}

@Composable
internal fun GeneratedDirectoriesSection(state: AppState) {
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
internal fun DirectoriesSection(state: AppState) {
    GoldenDirectoriesSection(state)
    Spacer(Modifier.height(20.dp))
    GeneratedDirectoriesSection(state)
}

@Composable
internal fun OutputMatchingSection(state: AppState) {
    SectionHeader(
        "Test output matching",
        "Used only after you select a golden and choose Compare: Test output. The first capture " +
            "group must be the matching golden's base name.",
    )
    FieldLabel("Generated output filename regex")
    MonoField(
        value = state.config.generatedFileRegex,
        enabled = true,
        singleLine = true,
    ) { text ->
        state.updateConfig(
            state.config.copy(
                generatedFileRegex = text.ifBlank { GoldenDiffDefaults.GENERATED_FILE_REGEX },
            ),
        )
    }
}

@Composable
internal fun MatchingSection(state: AppState) {
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
        MonoField(
            value = state.config.annotatedFunctionRegex,
            enabled = true,
            singleLine = true,
        ) { text ->
            state.updateConfig(
                state.config.copy(
                    annotatedFunctionRegex = text.ifBlank { MatchingDefaults.ANNOTATION_NAME_REGEX },
                ),
            )
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
            state.updateConfig(
                state.config.copy(
                    goldenFilePatterns = patterns.ifEmpty { MatchingDefaults.DEFAULT_FILE_CLASS_PATTERNS },
                ),
            )
        }
    }
}

@Composable
internal fun FilteringSection(state: AppState) {
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
    MonoField(
        value = state.config.excludedSuffixes.joinToString(", "),
        enabled = true,
        singleLine = true,
    ) { text ->
        val suffixes = text.split(',').map(String::trim).filter(String::isNotEmpty)
        state.updateConfig(state.config.copy(excludedSuffixes = suffixes))
    }
}

@Composable
internal fun DisplaySection(state: AppState) {
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
internal fun CommandsSection(state: AppState) {
    val placeholders = com.github.dkwasniak.goldendiff.command.CommandTemplate.PLACEHOLDERS
        .joinToString("   ") { (token, meaning) -> "$token = $meaning" }
    SectionHeader(
        "Commands",
        "Shell commands run from the toolbar to (re)generate or verify screenshot goldens. Free-form " +
            "and framework-agnostic — leave blank to disable a button. The \"current file\" commands may " +
            "use placeholders: $placeholders.",
    )
    CommandField("Generate all goldens", "Run for the whole project.", state.config.generateAllCommand) {
        state.updateConfig(state.config.copy(generateAllCommand = it))
    }
    CommandField(
        "Generate for current file",
        "Regenerate only the open file's goldens, e.g. ./gradlew record --tests '*{file_name}Kt*'.",
        state.config.generateFileCommand,
    ) { state.updateConfig(state.config.copy(generateFileCommand = it)) }
    CommandField("Verify all goldens", "Run for the whole project.", state.config.verifyAllCommand) {
        state.updateConfig(state.config.copy(verifyAllCommand = it))
    }
    CommandField(
        "Verify current file",
        "Verify only the open file's goldens, e.g. -only-testing:SnapshotTests/{class_name}.",
        state.config.verifyFileCommand,
    ) { state.updateConfig(state.config.copy(verifyFileCommand = it)) }
}

@Composable
private fun CommandField(label: String, description: String, value: String, onValueChange: (String) -> Unit) {
    FieldLabel(label)
    Text(description, color = tokens.textDim, fontSize = 11.5.sp, modifier = Modifier.padding(bottom = 8.dp))
    MonoField(value = value, enabled = true, singleLine = true, onValueChange = onValueChange)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun PrivacySection() {
    val settings = AppTelemetry.settings
    SectionHeader(
        "Privacy",
        "Product analytics and diagnostic error reports are on by default and independent — turn either " +
            "off any time. Neither ever includes project names, file names, paths, source code or images.",
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

// endregion

// region Shared controls

@Composable
private fun DirectorySection(
    title: String,
    description: String,
    paths: List<String>,
    chooserTitle: String,
    state: AppState,
    allowRegex: Boolean = false,
    withPaths: (List<String>) -> GoldenDiffConfig,
) {
    var regexDraft by remember { mutableStateOf("") }
    SectionHeader(title, description)
    paths.forEach { path ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .border(1.dp, tokens.border, RoundedCornerShape(Dimens.controlRadius))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val directoryRegex = GoldenDiffConfig.goldenDirectoryRegex(path)
            if (directoryRegex != null) {
                Text(
                    "Regex",
                    color = tokens.accent,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                directoryRegex ?: path,
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
    if (allowRegex) {
        Spacer(Modifier.height(12.dp))
        FieldLabel("Directory regex")
        Text(
            "Matched against the complete project-relative directory path using / separators.",
            color = tokens.textDim,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) {
                MonoField(
                    value = regexDraft,
                    enabled = true,
                    singleLine = true,
                    placeholder = ".*/src/test/snapshots",
                ) { regexDraft = it }
            }
            val validRegex = regexDraft.isNotBlank() && runCatching { Regex(regexDraft) }.isSuccess
            ToolButton("Add regex", enabled = validRegex) {
                val entry = GoldenDiffConfig.goldenDirectoryRegexEntry(regexDraft)
                if (entry !in paths) state.updateConfig(withPaths(paths + entry))
                regexDraft = ""
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String, description: String) {
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
internal fun FieldLabel(text: String) {
    Text(
        text,
        color = tokens.text,
        fontSize = Type.body,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
internal fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
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
internal fun Toggle(checked: Boolean, onToggle: () -> Unit) {
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
internal fun ConsentRow(title: String, description: String, checked: Boolean, onClick: () -> Unit) {
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
internal fun MonoField(
    value: String,
    enabled: Boolean,
    singleLine: Boolean,
    placeholder: String = "",
    onValueChange: (String) -> Unit,
) {
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
        // Placeholders are examples only. Effective defaults are passed as actual values so the UI
        // never suggests that a greyed value is active when the stored configuration is empty.
        decorationBox = { inner ->
            if (text.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = tokens.textFaint, fontSize = Type.body, fontFamily = Type.Mono)
            }
            inner()
        },
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(shape)
            .background(tokens.inputBackground).border(1.dp, tokens.border, shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

// endregion
