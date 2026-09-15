package com.github.dkwasniak.goldendiff.sample.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SamplePrimary = Color(0xFF3D5AFE)
private val SampleSecondary = Color(0xFF00BFA5)
private val SampleBackground = Color(0xFFF6F7FB)
private val SampleSurface = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = SamplePrimary,
    secondary = SampleSecondary,
    background = SampleBackground,
    surface = SampleSurface,
)

private val DarkColors = darkColorScheme(
    primary = SamplePrimary,
    secondary = SampleSecondary,
)

@Composable
fun SampleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
