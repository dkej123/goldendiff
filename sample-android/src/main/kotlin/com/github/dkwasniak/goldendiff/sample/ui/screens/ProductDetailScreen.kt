package com.github.dkwasniak.goldendiff.sample.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ProductDetailScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = Color(0xFF3D5AFE),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            ) {}
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Text(
                    text = "Analytical desk lamp",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "$42.00",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                Text(
                    text = "A brass-finished lamp inspired by Babbage's engines, with a warm " +
                        "adjustable glow for late-night debugging sessions.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        Text("Add to cart")
                    }
                }
            }
        }
    }
}
