package com.github.dkwasniak.goldendiff.sample.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun StatusBadgeScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                color = Color(0xFFD50000),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.padding(top = 120.dp),
            ) {
                Text(
                    text = "2 checks failing",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                )
            }
        }
    }
}
