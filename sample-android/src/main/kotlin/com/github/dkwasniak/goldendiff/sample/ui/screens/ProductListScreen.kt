package com.github.dkwasniak.goldendiff.sample.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class Product(val name: String, val price: String, val swatch: Color)

private val sampleProducts = listOf(
    Product("Analytical desk lamp", "$42", Color(0xFF3D5AFE)),
    Product("Punched card notebook", "$18", Color(0xFF00BFA5)),
    Product("Engine gear pin", "$9", Color(0xFFFF6D00)),
    Product("Difference engine mug", "$15", Color(0xFF6200EA)),
)

@Composable
fun ProductListScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "Shop",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            sampleProducts.forEach { product ->
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        ) {
                            Surface(color = product.swatch, modifier = Modifier.fillMaxSize()) {}
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = product.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = product.price,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
