package com.github.dkwasniak.goldendiff.sample.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class SettingRow(val label: String, val enabled: Boolean)

private val settingRows = listOf(
    SettingRow("Dark mode", enabled = false),
    SettingRow("Push notifications", enabled = true),
    SettingRow("Auto-sync goldens", enabled = true),
    SettingRow("Beta features", enabled = false),
)

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            settingRows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = row.label, modifier = Modifier.weight(1f))
                    Switch(checked = row.enabled, onCheckedChange = {})
                }
                HorizontalDivider()
            }
        }
    }
}
