package com.github.dkwasniak.goldendiff.sample.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class Bubble(val text: String, val time: String, val fromMe: Boolean)

private val bubbles = listOf(
    Bubble("Did the difference engine finish the run?", "09:41", fromMe = false),
    Bubble("Yes — 47 rows, zero mismatches.", "09:42", fromMe = true),
    Bubble("Nice. Punch a new card for tomorrow's batch.", "09:44", fromMe = false),
)

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Surface(shape = CircleShape, color = Color(0xFF7C4DFF), modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "CB", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text(text = "Charles Babbage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(text = "online", style = MaterialTheme.typography.bodySmall, color = Color(0xFF00BFA5))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                bubbles.forEach { bubble ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = if (bubble.fromMe) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Column(horizontalAlignment = if (bubble.fromMe) Alignment.End else Alignment.Start) {
                            Surface(
                                color = if (bubble.fromMe) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (bubble.fromMe) 16.dp else 4.dp,
                                    bottomEnd = if (bubble.fromMe) 4.dp else 16.dp,
                                ),
                            ) {
                                Text(
                                    text = bubble.text,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    color = if (bubble.fromMe) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Text(
                                text = bubble.time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
