package com.github.dkwasniak.goldendiff.sample.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class Bubble(val text: String, val fromMe: Boolean)

private val bubbles = listOf(
    Bubble("Did the difference engine finish the run?", fromMe = false),
    Bubble("Yes — 47 rows, zero mismatches.", fromMe = true),
    Bubble("Nice. Punch a new card for tomorrow's batch.", fromMe = false),
)

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            bubbles.forEach { bubble ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (bubble.fromMe) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Surface(
                        color = if (bubble.fromMe) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(16.dp),
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
                }
            }
        }
    }
}
