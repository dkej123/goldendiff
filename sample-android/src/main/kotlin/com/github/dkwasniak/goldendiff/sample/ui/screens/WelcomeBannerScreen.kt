package com.github.dkwasniak.goldendiff.sample.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeBannerScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = Color(0xFF00BFA5)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Welcome aboard",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 96.dp),
            )
            Text(
                text = "This screen just shipped — no baseline golden yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
