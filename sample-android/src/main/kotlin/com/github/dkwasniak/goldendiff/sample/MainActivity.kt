package com.github.dkwasniak.goldendiff.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.dkwasniak.goldendiff.sample.ui.screens.ChatScreen
import com.github.dkwasniak.goldendiff.sample.ui.screens.GreetingCardScreen
import com.github.dkwasniak.goldendiff.sample.ui.screens.LoginScreen
import com.github.dkwasniak.goldendiff.sample.ui.screens.ProductDetailScreen
import com.github.dkwasniak.goldendiff.sample.ui.screens.ProductListScreen
import com.github.dkwasniak.goldendiff.sample.ui.screens.ProfileScreen
import com.github.dkwasniak.goldendiff.sample.ui.screens.SettingsScreen
import com.github.dkwasniak.goldendiff.sample.ui.screens.StatusBadgeScreen
import com.github.dkwasniak.goldendiff.sample.ui.screens.WelcomeBannerScreen
import com.github.dkwasniak.goldendiff.sample.ui.theme.SampleTheme

private val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
    "Login" to { LoginScreen() },
    "Profile" to { ProfileScreen() },
    "Product list" to { ProductListScreen() },
    "Product detail" to { ProductDetailScreen() },
    "Settings" to { SettingsScreen() },
    "Chat" to { ChatScreen() },
    "Greeting card" to { GreetingCardScreen() },
    "Welcome banner" to { WelcomeBannerScreen() },
    "Status badge" to { StatusBadgeScreen() },
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SampleTheme {
                ScreenGallery()
            }
        }
    }
}

@Composable
private fun ScreenGallery() {
    var selected by remember { mutableStateOf<Int?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val current = selected
        if (current == null) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                items(screens.size) { index ->
                    Button(
                        onClick = { selected = index },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(screens[index].first)
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Button(onClick = { selected = null }, modifier = Modifier.padding(8.dp)) {
                    Text("Back")
                }
                Surface(modifier = Modifier.weight(1f)) {
                    screens[current].second()
                }
            }
        }
    }
}
