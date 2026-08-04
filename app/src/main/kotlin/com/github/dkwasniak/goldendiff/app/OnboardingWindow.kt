package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.github.dkwasniak.goldendiff.app.generated.resources.Res
import com.github.dkwasniak.goldendiff.app.generated.resources.app_icon
import com.github.dkwasniak.goldendiff.app.ui.DarkTokens
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.GhostButton
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.LightTokens
import com.github.dkwasniak.goldendiff.app.ui.LocalTokens
import com.github.dkwasniak.goldendiff.app.ui.PrimaryButton
import com.github.dkwasniak.goldendiff.app.ui.ToolButton
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens
import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.settings.GeneratedSource
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

/** The ordered walkthrough steps; the four framing steps are required, the middle five optional. */
private enum class OnboardingStep(val label: String, val optional: Boolean) {
    WELCOME("Welcome", false),
    PROJECT("Project", false),
    GOLDEN_DIRS("Golden directories", false),
    TEST_OUTPUT("Test output", true),
    MATCHING("Golden matching", true),
    FILTERING("Filtering", true),
    DISPLAY("Display", true),
    NOTIFICATIONS("Notifications", true),
    PRIVACY("Privacy", true),
    FINISH("Finish", false),
}

private enum class WelcomePage {
    CHOICE,
    AI_AGENT,
}

private val OnboardingWidth = 1040.dp
private val OnboardingHeight = 640.dp
private val StepperWidth = 230.dp

/**
 * First-run setup walkthrough — a guided front-end over the very same settings sections the Settings
 * window shows, so a new user reaches a working configuration without hunting through menus.
 *
 * A separate window (not an overlay) mirroring [SettingsWindow]: every edit runs through the normal
 * `updateConfig`/`openProject` path, so nothing here is a private copy of state. Reaching the end (or
 * dismissing) marks onboarding complete and records the telemetry-consent decision, which is why the
 * standalone consent prompt is suppressed while this is open.
 */
@Composable
fun ApplicationScope.OnboardingWindow(state: AppState, onClose: () -> Unit) {
    val palette = if (state.ui.useDarkTheme) DarkTokens else LightTokens
    var windowVisible by remember { mutableStateOf(true) }
    DeferredWindowCloseEffect(windowVisible) {
        AppConfig.markOnboardingCompleted()
        // Persisting the decision marks the consent prompt as shown, so it never double-prompts after
        // the walkthrough. The toggles themselves were already applied live in the Privacy step.
        val telemetry = AppTelemetry.settings
        AppTelemetry.decideConsent(telemetry.analyticsEnabled, telemetry.diagnosticsEnabled)
        onClose()
    }
    val requestClose = { windowVisible = false }

    Window(
        onCloseRequest = requestClose,
        visible = windowVisible,
        title = "Golden Diff Setup",
        state = rememberWindowState(size = DpSize(OnboardingWidth, OnboardingHeight)),
    ) {
        CompositionLocalProvider(LocalTokens provides palette) {
            MaterialTheme(
                colors = if (palette.dark) {
                    darkColors(background = palette.background, surface = palette.panel, primary = palette.accent)
                } else {
                    lightColors(background = palette.background, surface = palette.panel, primary = palette.accent)
                },
            ) {
                OnboardingContent(state, requestClose)
            }
        }
    }
}

@Composable
private fun OnboardingContent(state: AppState, requestClose: () -> Unit) {
    val steps = OnboardingStep.entries
    var index by remember { mutableStateOf(0) }
    var welcomePage by remember { mutableStateOf(WelcomePage.CHOICE) }
    val step = steps[index]
    val isLast = index == steps.lastIndex

    val blockingHint = validationHint(step, state)
    val nextEnabled = blockingHint == null

    val advance = {
        if (isLast) requestClose() else index += 1
    }

    // Hand-off from the CLI: while the walkthrough is open we watch for the completion signal an agent
    // writes at the end of `configure --signal`. A baseline captured now means a stale signal from an
    // earlier run can never auto-skip the wizard; only a fresh one opens the project and jumps ahead to
    // the Display step (everything before it — project, golden dirs, matching — the agent just set).
    val signalBaseline = remember { System.currentTimeMillis() }
    var handledSignalAt by remember { mutableStateOf(signalBaseline) }
    LaunchedEffect(Unit) {
        while (true) {
            val signal = AppConfig.readCliSignal()
            if (signal != null && signal.timestamp > handledSignalAt && signal.project.isDirectory) {
                handledSignalAt = signal.timestamp
                state.openProject(signal.project)
                index = OnboardingStep.DISPLAY.ordinal
            }
            delay(700)
        }
    }

    Column(Modifier.fillMaxSize().background(tokens.background)) {
        if (step == OnboardingStep.WELCOME) {
            when (welcomePage) {
                WelcomePage.CHOICE -> WelcomeChoice(
                    onAgent = { welcomePage = WelcomePage.AI_AGENT },
                    onManual = { index = OnboardingStep.PROJECT.ordinal },
                )
                WelcomePage.AI_AGENT -> AgentSetupStep(
                    state = state,
                    onBack = { welcomePage = WelcomePage.CHOICE },
                )
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Stepper(steps, index) { target ->
                    if (target <= index) {
                        index = target
                        if (target == OnboardingStep.WELCOME.ordinal) welcomePage = WelcomePage.CHOICE
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 44.dp, vertical = 36.dp),
                    ) {
                        StepContent(step, state)
                        if (blockingHint != null) {
                            Text(
                                blockingHint,
                                color = tokens.changed,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                    }
                }
            }
            HairLine()
            Footer(
                step = step,
                isLast = isLast,
                showBack = true,
                nextEnabled = nextEnabled,
                onBack = {
                    if (index > 0) index -= 1
                    if (index == OnboardingStep.WELCOME.ordinal) welcomePage = WelcomePage.CHOICE
                },
                onSkip = { if (index < steps.lastIndex) index += 1 },
                onNext = { if (nextEnabled) advance() },
            )
        }
    }
}

/** A blocking hint means Next is disabled; null means the step is satisfied. */
private fun validationHint(step: OnboardingStep, state: AppState): String? = when (step) {
    OnboardingStep.PROJECT -> when {
        state.projectRoot == null -> "Choose a project directory to continue."
        state.blocker == Blocker.NoGit || state.blocker == Blocker.NotARepository ->
            "Fix the project directory to continue."
        else -> null
    }
    OnboardingStep.GOLDEN_DIRS ->
        if (state.config.goldenPaths.isEmpty()) "Add at least one directory to continue." else null
    // Required only where an empty value would actually match nothing: file/class mode needs at least
    // one pattern, and a generated-output regex is only needed once test-output directories are set.
    // (Annotated mode needs nothing extra — the app derives candidates from the edited file itself.)
    OnboardingStep.MATCHING ->
        if (state.config.matchMode == MatchMode.FILE_CLASS_REGEX && state.config.goldenFilePatterns.isEmpty()) {
            "Add at least one golden filename pattern to continue."
        } else {
            null
        }
    OnboardingStep.TEST_OUTPUT ->
        if (state.config.generatedPaths.isNotEmpty() && state.config.generatedFileRegex.isBlank()) {
            "Set the generated output filename regex to continue."
        } else {
            null
        }
    else -> null
}

@Composable
private fun Stepper(steps: List<OnboardingStep>, activeIndex: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier.width(StepperWidth).fillMaxHeight()
            .background(tokens.panelHeader)
            .padding(vertical = 18.dp),
    ) {
        steps.forEachIndexed { i, step ->
            val stepState = when {
                i < activeIndex -> StepState.DONE
                i == activeIndex -> StepState.ACTIVE
                else -> StepState.UPCOMING
            }
            StepperRow(number = i + 1, step = step, stepState = stepState) { onSelect(i) }
        }
    }
}

private enum class StepState { DONE, ACTIVE, UPCOMING }

@Composable
private fun StepperRow(number: Int, step: OnboardingStep, stepState: StepState, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(50))
                .background(if (stepState == StepState.DONE) tokens.accent else Color.Transparent)
                .then(
                    when (stepState) {
                        StepState.ACTIVE -> Modifier.border(2.dp, tokens.accent, RoundedCornerShape(50))
                        StepState.UPCOMING -> Modifier.border(1.5.dp, tokens.textFaint, RoundedCornerShape(50))
                        StepState.DONE -> Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (stepState == StepState.DONE) "✓" else number.toString(),
                color = when (stepState) {
                    StepState.DONE -> Color.White
                    StepState.ACTIVE -> tokens.accent
                    StepState.UPCOMING -> tokens.textFaint
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column {
            Text(
                step.label,
                color = if (stepState == StepState.UPCOMING) tokens.textFaint else tokens.text,
                fontSize = Type.panelTitle,
                fontWeight = if (stepState == StepState.ACTIVE) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (step.optional) {
                Text("Optional", color = tokens.textFaint, fontSize = 9.5.sp, letterSpacing = 0.3.sp)
            }
        }
    }
}

@Composable
private fun StepContent(step: OnboardingStep, state: AppState) {
    when (step) {
        OnboardingStep.WELCOME -> Unit
        OnboardingStep.PROJECT -> ProjectSection(state)
        OnboardingStep.GOLDEN_DIRS -> GoldenDirectoriesSection(state)
        OnboardingStep.TEST_OUTPUT -> {
            XcresultSourceSection(state)
            if (state.config.generatedSource == GeneratedSource.DIRECTORY) {
                Spacer(Modifier.height(20.dp))
                GeneratedDirectoriesSection(state)
                Spacer(Modifier.height(20.dp))
                OutputMatchingSection(state)
            }
        }
        OnboardingStep.MATCHING -> MatchingSection(state)
        OnboardingStep.FILTERING -> FilteringSection(state)
        OnboardingStep.DISPLAY -> DisplaySection(state)
        OnboardingStep.NOTIFICATIONS -> NotificationsSection(state)
        OnboardingStep.PRIVACY -> PrivacySection()
        OnboardingStep.FINISH -> FinishStep(state)
    }
}

@Composable
private fun WelcomeChoice(onAgent: () -> Unit, onManual: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(Color.White)
                .border(1.dp, tokens.border, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(Res.drawable.app_icon), contentDescription = null, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Golden Diff", color = tokens.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Compare your committed golden screenshots against Git HEAD and your working copy — " +
                "in one focused desktop workspace.",
            color = tokens.textDim,
            fontSize = 14.sp,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(44.dp))
        Row(
            Modifier.widthIn(max = 660.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SetupChoiceCard(
                title = "Configure with AI agent",
                description = "Pick a directory and copy a ready-made prompt into Claude Code " +
                    "(or another CLI agent) to set everything up for you.",
                emphasized = true,
                modifier = Modifier.weight(1f),
                onClick = onAgent,
            )
            SetupChoiceCard(
                title = "Configure manually",
                description = "Walk through the setup wizard yourself, step by step.",
                emphasized = false,
                modifier = Modifier.weight(1f),
                onClick = onManual,
            )
        }
    }
}

@Composable
private fun SetupChoiceCard(
    title: String,
    description: String,
    emphasized: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.heightIn(min = 150.dp).clip(shape)
            .background(if (emphasized) tokens.accentBg else tokens.panel)
            .border(1.dp, if (emphasized) tokens.accentBorder else tokens.border, shape)
            .hoverWash(true, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = tokens.text, fontSize = Type.large, fontWeight = FontWeight.Bold)
        Text(
            description,
            color = tokens.textDim,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun AgentSetupStep(state: AppState, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 44.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.widthIn(max = 660.dp).fillMaxWidth()) {
            GhostButton("Back", onBack)
        }
        Spacer(Modifier.height(16.dp))
        Text("Configure with AI agent", color = tokens.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose your project, then copy the prepared prompt into your CLI agent.",
            color = tokens.textDim,
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        CliAgentCard(state)
    }
}

/**
 * "Set up with a CLI agent": pick the project, copy a ready-made prompt into an AI CLI agent, then
 * wait. When the agent finishes it runs `configure --signal`, and the walkthrough (which polls for
 * that signal) opens the project and jumps ahead — so this card's waiting state is dismissed for it.
 */
@Composable
private fun CliAgentCard(state: AppState) {
    val cliPath = remember { resolveCliBinaryPath() }
    val cliAvailable = remember(cliPath) { isInstalledCliAvailable(cliPath) }
    var awaitingAgent by remember { mutableStateOf(false) }
    val root = state.projectRoot
    val projectPath = root?.absolutePath ?: CLI_AGENT_PROMPT_PATH_PLACEHOLDER
    val prompt = buildCliAgentPrompt(projectPath)

    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(tokens.panel).border(1.dp, tokens.border, RoundedCornerShape(10.dp))
            .padding(20.dp),
    ) {
        if (awaitingAgent) {
            AgentWaitingState(
                onCopyAgain = { copyToClipboard(prompt) },
                onCancel = { awaitingAgent = false },
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Set up with a CLI agent",
                color = tokens.text,
                fontSize = Type.large,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            AccentOutlineButton("Copy prompt", enabled = root != null && cliAvailable) {
                copyToClipboard(prompt)
                awaitingAgent = true
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick your project directory, then copy this prompt into Claude Code (or another CLI agent) " +
                "to configure Golden Diff for you.",
            color = tokens.textDim,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(14.dp))
        ToolButton("Choose Directory…") {
            chooseDirectory("Select project directory")?.let(state::openProject)
        }
        if (root != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                root.absolutePath,
                color = tokens.textDim,
                fontSize = Type.body,
                fontFamily = Type.Mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(14.dp))
        PromptBox(prompt)
        if (root == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Choose a project directory to fill in the prompt.",
                color = tokens.textFaint,
                fontSize = 11.sp,
            )
        } else if (!cliAvailable) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Install Golden Diff in /Applications before copying the agent prompt.",
                color = tokens.changed,
                fontSize = 11.sp,
            )
        }
    }
}

/** Read-only, selectable, scrollable rendering of the copy-ready prompt. */
@Composable
private fun PromptBox(prompt: String) {
    Box(
        Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(Dimens.controlRadius))
            .background(tokens.inputBackground).border(1.dp, tokens.border, RoundedCornerShape(Dimens.controlRadius))
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        SelectionContainer {
            Text(prompt, color = tokens.textDim, fontSize = Type.body, fontFamily = Type.Mono, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun AgentWaitingState(onCopyAgain: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(color = tokens.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
        Text(
            "Waiting for the CLI agent to finish configuring Golden Diff…",
            color = tokens.text,
            fontSize = Type.panelTitle,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Text(
            "This continues automatically once the agent applies the configuration.",
            color = tokens.textDim,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Copy prompt again", onCopyAgain)
            GhostButton("Cancel", onCancel)
        }
    }
}

/** Outlined accent button — the "Copy prompt" affordance from the mockup. */
@Composable
private fun AccentOutlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    val alpha = if (enabled) 1f else 0.4f
    Text(
        label,
        color = tokens.accent.copy(alpha = alpha),
        fontSize = Type.body,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(shape).border(1.dp, tokens.accent.copy(alpha = alpha), shape)
            .hoverWash(enabled, shape).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun FinishStep(state: AppState) {
    SectionHeader("You're all set", "Review your setup — you can change any of this later in Settings.")
    val matchLabel = if (state.config.matchMode == MatchMode.ANNOTATED_METHOD) {
        "Match by annotated method"
    } else {
        "Match by file/class regex"
    }
    Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(tokens.panel).border(1.dp, tokens.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SummaryRow("Project", state.projectRoot?.name ?: "—")
        SummaryRow("Golden directories", "${state.config.goldenPaths.size} configured")
        SummaryRow("Matching mode", matchLabel)
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = tokens.textDim, fontSize = Type.panelTitle)
        Text(value, color = tokens.text, fontSize = Type.panelTitle, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Footer(
    step: OnboardingStep,
    isLast: Boolean,
    showBack: Boolean,
    nextEnabled: Boolean,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    val nextLabel = when {
        isLast -> "Start comparing"
        else -> "Next"
    }
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(tokens.panelHeader).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showBack) GhostButton("Back", onBack)
        Spacer(Modifier.weight(1f))
        if (step.optional && !isLast) GhostButton("Skip", onSkip)
        PrimaryButton(nextLabel, enabled = nextEnabled, onClick = onNext)
    }
}
