package com.github.dkwasniak.goldendiff.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTests {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loginScreen() = capture { LoginScreen() }

    @Test
    fun profileScreen() = capture { ProfileScreen() }

    @Test
    fun productListScreen() = capture { ProductListScreen() }

    @Test
    fun productDetailScreen() = capture { ProductDetailScreen() }

    @Test
    fun settingsScreen() = capture { SettingsScreen() }

    @Test
    fun chatScreen() = capture { ChatScreen() }

    @Test
    fun greetingCardScreen() = capture { GreetingCardScreen() }

    @Test
    fun welcomeBannerScreen() = capture { WelcomeBannerScreen() }

    @Test
    fun statusBadgeScreen() = capture { StatusBadgeScreen() }

    private fun capture(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            SampleTheme {
                Box(modifier = Modifier.size(360.dp, 640.dp)) {
                    content()
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage()
    }
}
