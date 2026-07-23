package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.github.dkwasniak.goldendiff.app.ui.DarkTokens
import com.github.dkwasniak.goldendiff.app.ui.LightTokens

/** One-time consent prompt based on the supplied product design. */
@Composable
fun TelemetryConsentWindow() {
    var analytics by remember { mutableStateOf(true) }
    var diagnostics by remember { mutableStateOf(true) }
    val palette = if (AppTelemetry.settings.analyticsEnabled || AppTelemetry.settings.diagnosticsEnabled) {
        DarkTokens
    } else {
        if (UiPreferences.load().useDarkTheme) DarkTokens else LightTokens
    }

    Window(
        onCloseRequest = { AppTelemetry.decideConsent(false, false) },
        title = "Help make Golden Diff better",
        state = rememberWindowState(size = DpSize(760.dp, 610.dp)),
        resizable = false,
    ) {
        MaterialTheme {
            Column(
                Modifier.fillMaxSize().background(palette.panel)
                    .padding(horizontal = 52.dp, vertical = 42.dp),
            ) {
                Text("✣", color = palette.accent, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))
                Text(
                    "Help make Golden Diff better",
                    color = palette.text,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Sharing anonymous usage data and crash reports helps us find bugs faster, " +
                        "prioritize the features teams actually use, and ship fixes before you notice " +
                        "the problem. It's optional, and you can change your mind anytime in Settings.",
                    color = palette.textDim,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                )
                Spacer(Modifier.height(28.dp))
                ConsentChoice(
                    title = "Anonymous usage analytics",
                    description = "Which features you use (Quick Open, compare modes, grid size) — " +
                        "never filenames, paths, source code, or image content.",
                    checked = analytics,
                    onChecked = { analytics = it },
                    palette = palette,
                )
                Spacer(Modifier.height(20.dp))
                ConsentChoice(
                    title = "Crash reporting",
                    description = "Sanitized stack traces when Golden Diff fails — local paths and " +
                        "personal data are removed before sending.",
                    checked = diagnostics,
                    onChecked = { diagnostics = it },
                    palette = palette,
                )
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth().padding(top = 22.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Not now",
                        color = palette.textDim,
                        fontSize = 16.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            AppTelemetry.decideConsent(false, false)
                        }.padding(horizontal = 24.dp, vertical = 13.dp),
                    )
                    Button(
                        onClick = { AppTelemetry.decideConsent(analytics, diagnostics) },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = palette.accent,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "Enable and continue",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentChoice(
    title: String,
    description: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    palette: com.github.dkwasniak.goldendiff.app.ui.Tokens,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = palette.accent),
            modifier = Modifier.size(48.dp, 28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = palette.textDim, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}
