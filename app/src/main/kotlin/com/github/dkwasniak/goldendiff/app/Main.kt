package com.github.dkwasniak.goldendiff.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.github.dkwasniak.goldendiff.app.generated.resources.Res
import com.github.dkwasniak.goldendiff.app.generated.resources.app_icon
import com.github.dkwasniak.goldendiff.platform.Os
import java.awt.Desktop
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

fun main(args: Array<String>) {
    // Terminal front-end: when launched with arguments the app is a headless CLI, so hand off before
    // any Compose/AWT/telemetry code runs and exit with its status. The GUI path below is unchanged.
    if (args.isNotEmpty()) {
        kotlin.system.exitProcess(GoldenDiffCli.run(args))
    }
    // Read before AWT starts: the native window chrome takes its appearance from this property once,
    // at initialisation, so setting it later would leave a dark title bar over a light window.
    val preferences = UiPreferences.load()
    if (Os.current == Os.MAC) {
        System.setProperty(
            "apple.awt.application.appearance",
            if (preferences.useDarkTheme) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
        )
    }
    AppTelemetry.installUncaughtExceptionBridge()
    AppTelemetry.startSession(projectRestored = AppConfig.lastProject() != null)
    application {
        val coroutineExceptionHandler = remember {
            CoroutineExceptionHandler { _, throwable ->
                AppTelemetry.client.captureException(throwable, "coroutine:${throwable.javaClass.name}")
            }
        }
        val scope = rememberCoroutineScope { coroutineExceptionHandler }
        val state = remember { AppState(scope, preferences, AppTelemetry.client) }
        var mainWindowVisible by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            AppConfig.lastProject()?.let { state.openProject(it, restored = true) }
            // First launch on this machine: guide the user through configuration instead of dropping
            // them on an empty window. The walkthrough itself opens the project and fills in settings.
            if (!AppConfig.onboardingCompleted()) state.onboardingVisible = true
            state.checkForUpdates()
            // Registers with Notification Center, which is also what lists the app in System Settings
            // → Notifications. Done unconditionally: the toggle decides whether we post, not whether
            // the user is allowed to find the switch.
            MacNotifications.register { granted, error ->
                DevLog.record("notification", "authorization granted=$granted${error?.let { " error=$it" }.orEmpty()}")
            }
            // macOS already gives every app a menu named after its bundle. Declaring our own
            // `MenuBar { Menu("Golden Diff") }` produced a second one right next to it; registering the
            // preferences handler instead puts Settings where the platform expects it — in the
            // application menu, with the standard ⌘, shortcut attached by AppKit.
            runCatching {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
                    desktop.setPreferencesHandler { scope.launch { state.settingsVisible = true } }
                }
            }
            MacNotifications.onNotificationClicked {
                // Arrives on an AppKit thread; the window flag belongs to the composition.
                scope.launch { state.commandLogVisible = true }
            }
        }
        CliConfigReloadEffect(state)
        DeferredWindowCloseEffect(mainWindowVisible) {
            state.close()
            exitApplication()
        }

        Window(
            onCloseRequest = { mainWindowVisible = false },
            visible = mainWindowVisible,
            title = "Golden Diff",
            icon = painterResource(Res.drawable.app_icon),
            state = rememberWindowState(size = DpSize(1400.dp, 900.dp)),
            onPreviewKeyEvent = { event ->
                // Previewed at the window rather than bound to a component, so the shortcut works wherever
                // focus happens to be - the tree, the list, or nothing at all.
                when {
                    event.type != KeyEventType.KeyDown -> false
                    Shortcuts.isQuickOpen(event) -> {
                        state.featureUsed("quick_open")
                        state.quickOpenQuery = ""
                        state.quickOpenVisible = true
                        true
                    }
                    event.key == Key.Escape && state.quickOpenVisible -> {
                        state.quickOpenVisible = false
                        true
                    }
                    else -> false
                }
            },
        ) {
            // Coming back to the window is the acknowledgement; nothing is left pending after it.
            DisposableEffect(window) {
                val listener = object : WindowAdapter() {
                    override fun windowGainedFocus(event: WindowEvent) = AppBadge.clear()
                }
                window.addWindowFocusListener(listener)
                if (window.isFocused) AppBadge.clear()
                onDispose { window.removeWindowFocusListener(listener) }
            }
            GoldenDiffWindow(state)
        }

        // A real second window rather than an overlay, so the comparison stays visible while the
        // golden directories are being set up.
        if (state.settingsVisible) SettingsWindow(state) { state.settingsVisible = false }

        // First-run setup walkthrough; while it is open it owns the telemetry-consent decision, so the
        // standalone consent prompt below stays suppressed.
        if (state.onboardingVisible) OnboardingWindow(state) { state.onboardingVisible = false }

        // The compare pane detached into its own window; follows the live selection.
        if (state.compareWindowVisible) ComparisonWindow(state) { state.compareWindowVisible = false }
        if (state.diagnosticsVisible) DiagnosticsWindow(state) { state.diagnosticsVisible = false }
        if (state.commandLogVisible) CommandLogWindow(state) { state.commandLogVisible = false }
        if (AppTelemetry.consentPromptVisible && !state.onboardingVisible) TelemetryConsentWindow()
    }
}

/**
 * Keeps an already-open project in sync with settings written by the headless CLI.
 *
 * Onboarding has its own signal consumer because it also changes wizard steps. This watcher still
 * advances its cursor while onboarding is visible so the same signal is not replayed after the
 * walkthrough closes.
 */
@Composable
private fun CliConfigReloadEffect(state: AppState) {
    val baseline = remember { AppConfig.readCliSignal()?.timestamp ?: 0L }
    LaunchedEffect(state) {
        var handledAt = baseline
        while (true) {
            val signal = AppConfig.readCliSignal()
            if (signal != null && signal.timestamp > handledAt) {
                handledAt = signal.timestamp
                if (!state.onboardingVisible) state.reloadConfigFromDisk(signal.project)
            }
            delay(500)
        }
    }
}

/**
 * Platform-appropriate shortcuts, resolved in one place.
 *
 * Each is IntelliJ's own "Go to File" binding on that platform, so the shortcut a developer already
 * has in muscle memory works here too.
 */
object Shortcuts {

    val quickOpenLabel: String = if (Os.current == Os.MAC) "⇧⌘O" else "Ctrl+Shift+N"

    fun isQuickOpen(event: KeyEvent): Boolean =
        if (Os.current == Os.MAC) {
            event.isMetaPressed && event.isShiftPressed && event.key == Key.O
        } else {
            event.isCtrlPressed && event.isShiftPressed && event.key == Key.N
        }
}
