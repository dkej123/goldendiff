package com.github.dkwasniak.goldendiff.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dkwasniak.goldendiff.telemetry.BuildBadge
import com.github.dkwasniak.goldendiff.telemetry.BuildMetadata

// Amber for the developer marker; the accent blue is reused for the beta marker so it reads as
// "same product, next channel" rather than a warning.
private val DevBadgeColorDark = Color(0xFFD9A441)
private val DevBadgeColorLight = Color(0xFFA9761A)

/**
 * The build's footer badges (`DEV BUILD`, then `BETA`), rendered at the trailing edge of the status
 * bar. A stable release has no badges and this emits nothing.
 */
@Composable
fun BuildBadges(metadata: BuildMetadata, modifier: Modifier = Modifier) {
    if (metadata.badges.isEmpty()) return
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        metadata.badges.forEach { BuildBadgeChip(it) }
    }
}

@Composable
private fun BuildBadgeChip(badge: BuildBadge) {
    val color = when (badge) {
        BuildBadge.DEV -> if (tokens.dark) DevBadgeColorDark else DevBadgeColorLight
        BuildBadge.BETA -> tokens.accent
    }
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier.height(15.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.55f), shape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            badge.label,
            color = color,
            // Trim the font's built-in line spacing and centre the glyphs, so the all-caps label sits
            // in the visual middle of the chip instead of riding low on the baseline.
            style = TextStyle(
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                lineHeight = 8.sp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}
