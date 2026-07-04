package com.example.goldendiffdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A self-contained sample screen used only to render golden images for the Marketplace media.
 * Not part of the plugin build. See demo/README.md.
 *
 * To stage a clean visual diff for the "after" golden, tweak one obvious thing — e.g. change
 * [Accent] to Color(0xFF7C4DFF), or the followers count in [ProfileScreen] — and re-render.
 */

private val Background = Color(0xFF0F1115)
private val Surface = Color(0xFF171A21)
private val Accent = Color(0xFF3D7EFF)
private val OnSurface = Color(0xFFECEFF4)
private val Muted = Color(0xFF9AA4B2)

@Composable
fun ProfileScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Avatar(initials = "AT")
            Spacer(Modifier.height(16.dp))
            Text("Ava Thompson", color = OnSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("@ava.codes · Android Engineer", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            StatsCard()
            Spacer(Modifier.height(20.dp))
            ActionButtons()
            Spacer(Modifier.height(28.dp))
            MenuList()
        }
    }
}

@Composable
private fun Avatar(initials: String) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF3D7EFF), Color(0xFF9C5BFF)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stat("128", "Posts")
            VerticalRule()
            Stat("8,472", "Followers")
            VerticalRule()
            Stat("312", "Following")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun VerticalRule() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 28.dp)
            .background(Color(0xFF272C36)),
    )
}

@Composable
private fun ActionButtons() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = {},
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Text("Follow", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = {},
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Message", color = OnSurface)
        }
    }
}

@Composable
private fun MenuList() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Column {
            MenuItem(Icons.Outlined.Bookmark, "Saved items")
            Divider(color = Color(0xFF20242E), thickness = 1.dp)
            MenuItem(Icons.Outlined.Notifications, "Notifications")
            Divider(color = Color(0xFF20242E), thickness = 1.dp)
            MenuItem(Icons.Outlined.Settings, "Settings")
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF222836)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Accent)
        }
        Spacer(Modifier.size(14.dp))
        Text(label, color = OnSurface, fontSize = 16.sp)
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun ProfileScreenPreview() {
    ProfileScreen()
}
