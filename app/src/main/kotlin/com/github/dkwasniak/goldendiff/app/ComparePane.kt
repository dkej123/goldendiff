package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dkwasniak.goldendiff.app.ui.CopyIcon
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.EmptyState
import com.github.dkwasniak.goldendiff.app.ui.ExpandIcon
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.IconButton
import com.github.dkwasniak.goldendiff.app.ui.Pane
import com.github.dkwasniak.goldendiff.app.ui.Segment
import com.github.dkwasniak.goldendiff.app.ui.StatusDot
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens
import com.github.dkwasniak.goldendiff.compare.ImageLayout
import com.github.dkwasniak.goldendiff.naming.shortGoldenName
import com.github.dkwasniak.goldendiff.scan.BuiltInSource
import com.github.dkwasniak.goldendiff.ui.CompareColors
import com.github.dkwasniak.goldendiff.ui.CompareMode
import com.github.dkwasniak.goldendiff.ui.OnionSkinView
import com.github.dkwasniak.goldendiff.ui.SingleImageView
import com.github.dkwasniak.goldendiff.ui.SwipeView
import com.github.dkwasniak.goldendiff.ui.TwoUpView
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private val ZoomSteps = listOf(ImageLayout.FIT, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 4.0)
private val ZoomLabels = listOf("Fit", "25%", "50%", "75%", "100%", "150%", "200%", "400%")
private const val FIT_INDEX = 0
private val HUNDRED_INDEX = ZoomSteps.indexOf(1.0)

/** Right pane: the selected golden's HEAD-vs-working-copy comparison. */
@Composable
fun ComparePane(state: AppState, modifier: Modifier) {
    val selected = state.selected
    Pane(modifier) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                selected?.let { shortGoldenName(it.name) } ?: "Compare",
                color = tokens.text,
                fontSize = Type.panelTitle,
                fontFamily = if (selected != null) Type.Mono else null,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected != null) {
                IconButton(size = 26.dp, onClick = {
                    state.featureUsed("detached_comparison")
                    state.compareWindowVisible = true
                }) {
                    ExpandIcon(tokens.textDim)
                }
                IconButton(size = 26.dp, onClick = {
                    state.featureUsed("copy_path")
                    copyToClipboard(selected.absolutePath)
                }) {
                    CopyIcon(back = tokens.textFaint, front = tokens.textDim, fill = tokens.panel)
                }
            }
        }
        HairLine()
        CompareContent(state, Modifier.weight(1f))
    }
}

/**
 * The comparison body — mode toggle, canvas and zoom bar — without the pane header.
 *
 * Extracted so the standalone [ComparisonWindow] can reuse it; each host keeps its own mode/zoom
 * state, so the pane and the detached window can sit at different modes and zoom levels.
 */
@Composable
fun CompareContent(state: AppState, modifier: Modifier, location: String = "main_pane") {
    var mode by remember { mutableStateOf(CompareMode.TWO_UP) }
    var zoomIndex by remember { mutableIntStateOf(FIT_INDEX) }
    var opacity by remember { mutableStateOf(0.5f) }
    var pendingZoomAction by remember { mutableStateOf<String?>(null) }
    val comparison = state.comparison
    val selected = state.selected

    // A new selection restarts at Fit: carrying a 400% zoom across to a differently sized golden
    // drops the viewer into an arbitrary corner of it.
    LaunchedEffect(selected) { zoomIndex = FIT_INDEX }
    LaunchedEffect(zoomIndex, pendingZoomAction) {
        val action = pendingZoomAction ?: return@LaunchedEffect
        delay(500)
        val zoom = when {
            zoomIndex == FIT_INDEX -> "fit"
            ZoomSteps[zoomIndex] < 1.0 -> "lt_100"
            ZoomSteps[zoomIndex] == 1.0 -> "equal_100"
            else -> "gt_100"
        }
        state.zoomSelected(zoom, action, location)
        pendingZoomAction = null
    }

    Column(modifier.fillMaxWidth()) {
        val showModes = comparison?.hasDiff == true
        if (showModes) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).background(tokens.panelHeader)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompareMode.entries.forEach { candidate ->
                    Segment(candidate.label, mode == candidate) {
                        if (mode != candidate) {
                            mode = candidate
                            state.compareModeSelected(
                                when (candidate) {
                                    CompareMode.TWO_UP -> "side_by_side"
                                    CompareMode.SWIPE -> "swipe"
                                    CompareMode.ONION -> "onion"
                                    CompareMode.DIFF -> "diff"
                                },
                                location,
                            )
                        }
                    }
                }
            }
            HairLine()
        } else if (comparison?.identical == true) {
            UnchangedBanner()
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(tokens.background)) {
            // Each branch is exclusive and keyed on the current comparison, so switching selection or
            // mode never leaves the previous mode's image behind.
            when {
                selected == null -> Centered {
                    EmptyState("No golden selected", "Pick a screenshot to compare it against Git HEAD.", compact = true)
                }
                comparison == null -> Centered {
                    EmptyState("Loading…", "Reading ${selected.name} from Git HEAD.", compact = true)
                }
                comparison.missingCounterpart -> Centered {
                    EmptyState(
                        "No working copy match",
                        "This golden has no corresponding ${sourceLabel(state.source).lowercase()} screenshot yet.",
                        compact = true,
                    )
                }
                comparison.hasDiff -> DiffBody(state, comparison, mode, ZoomSteps[zoomIndex], opacity)
                else -> ScrollableCanvas(ZoomSteps[zoomIndex], CompareMode.TWO_UP, comparison.new ?: comparison.old, null) { canvas ->
                    SingleImageView(comparison.new ?: comparison.old, ZoomSteps[zoomIndex], canvas, compareColors())
                }
            }

            if (selected != null) {
                ZoomBar(
                    label = ZoomLabels[zoomIndex],
                    onFit = {
                        zoomIndex = FIT_INDEX
                        pendingZoomAction = "fit"
                    },
                    onOut = {
                        zoomIndex = (zoomIndex - 1).coerceAtLeast(0)
                        pendingZoomAction = "zoom_out"
                    },
                    onHundred = {
                        zoomIndex = HUNDRED_INDEX
                        pendingZoomAction = "hundred"
                    },
                    onIn = {
                        zoomIndex = (zoomIndex + 1).coerceAtMost(ZoomSteps.lastIndex)
                        pendingZoomAction = "zoom_in"
                    },
                    canZoomOut = zoomIndex > 0,
                    canZoomIn = zoomIndex < ZoomSteps.lastIndex,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        if (comparison?.hasDiff == true && mode == CompareMode.ONION) {
            HairLine()
            Box(Modifier.fillMaxWidth().background(tokens.panelHeader), contentAlignment = Alignment.Center) {
                OpacitySlider(
                    state,
                    opacity,
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                ) { opacity = it }
            }
        }
        HairLine()
        Text(
            comparison?.statusText.orEmpty(),
            color = tokens.textFaint,
            fontSize = Type.small,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DiffBody(
    state: AppState,
    comparison: AppState.Comparison,
    mode: CompareMode,
    zoom: Double,
    opacity: Float,
) {
    val colors = compareColors()
    val labelTop = 26.dp
    ScrollableCanvas(
        zoom,
        mode,
        if (mode == CompareMode.DIFF) comparison.diff else comparison.old,
        if (mode == CompareMode.DIFF) null else comparison.new,
    ) { canvas ->
        when (mode) {
            CompareMode.TWO_UP -> TwoUpView(comparison.old, comparison.new, zoom, canvas.padding(top = labelTop), colors)
            CompareMode.SWIPE -> SwipeView(comparison.old, comparison.new, zoom, canvas.padding(top = labelTop), colors)
            CompareMode.ONION ->
                OnionSkinView(comparison.old, comparison.new, zoom, opacity, canvas.padding(top = labelTop), colors)
            CompareMode.DIFF -> SingleImageView(comparison.diff, zoom, canvas.padding(top = labelTop), colors)
        }
        SideLabels(comparison, mode, sourceLabel(state.source))
    }
}

/** The "HEAD" / "Working copy" captions that sit above whichever canvas is showing. */
@Composable
private fun SideLabels(comparison: AppState.Comparison, mode: CompareMode, newLabel: String) {
    val style: @Composable (String, Modifier) -> Unit = { text, modifier ->
        Text(
            text,
            color = tokens.textFaint,
            fontSize = Type.small,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            modifier = modifier,
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
        when (mode) {
            CompareMode.DIFF -> style("%.2f%% of pixels changed".format(comparison.changedRatio * 100), Modifier)
            CompareMode.ONION -> style("HEAD over $newLabel", Modifier)
            else -> {
                style("HEAD", Modifier.weight(1f))
                style(newLabel, Modifier)
            }
        }
    }
}

@Composable
private fun UnchangedBanner() {
    Row(
        Modifier.fillMaxWidth().background(tokens.panelHeader).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(tokens.textFaint)
        Text(
            "Unchanged — identical to Git HEAD, no working copy diff to show",
            color = tokens.textDim,
            fontSize = 11.5.sp,
        )
    }
    HairLine()
}

@Composable
private fun OpacitySlider(state: AppState, opacity: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    Row(
        modifier.shadow(6.dp, RoundedCornerShape(Dimens.barRadius))
            .clip(RoundedCornerShape(Dimens.barRadius)).background(tokens.panel)
            .border(1.dp, tokens.border, RoundedCornerShape(Dimens.barRadius))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("HEAD", color = tokens.textDim, fontSize = Type.small)
        Slider(
            value = opacity,
            onValueChange = onChange,
            colors = SliderDefaults.colors(
                thumbColor = tokens.accent,
                activeTrackColor = tokens.accent,
                inactiveTrackColor = tokens.borderStrong,
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(sourceLabel(state.source), color = tokens.textDim, fontSize = Type.small)
    }
}

/** The floating Fit / – / 100% / + pill in the bottom-right corner of the compare area. */
@Composable
private fun ZoomBar(
    label: String,
    onFit: () -> Unit,
    onOut: () -> Unit,
    onHundred: () -> Unit,
    onIn: () -> Unit,
    canZoomOut: Boolean,
    canZoomIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.barRadius)
    Row(
        modifier.padding(12.dp).shadow(8.dp, shape).clip(shape).background(tokens.panel)
            .border(1.dp, tokens.border, shape).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZoomAction("Fit", enabled = true, onClick = onFit)
        ZoomAction("−", enabled = canZoomOut, onClick = onOut)
        Text(
            label,
            color = tokens.textFaint,
            fontSize = Type.small,
            modifier = Modifier.width(34.dp)
                .hoverWash()
                .clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onHundred),
            textAlign = TextAlign.Center,
        )
        ZoomAction("+", enabled = canZoomIn, onClick = onIn)
    }
}

@Composable
private fun ZoomAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.height(22.dp).clip(shape).hoverWash(enabled, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tokens.textDim.copy(alpha = if (enabled) 1f else 0.4f), fontSize = Type.body)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun compareColors(): CompareColors = if (tokens.dark) {
    CompareColors(
        checkerLight = Color(0xFF3A3C40),
        checkerDark = Color(0xFF303236),
        divider = tokens.accent,
        splitter = tokens.borderStrong,
    )
} else {
    CompareColors(
        checkerLight = Color(0xFFEDEEF1),
        checkerDark = Color(0xFFDDDFE4),
        divider = tokens.accent,
        splitter = tokens.borderStrong,
    )
}

@Composable
private fun ScrollableCanvas(
    zoom: Double,
    mode: CompareMode,
    old: ImageBitmap?,
    new: ImageBitmap?,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val canvasModifier = canvasSizeModifier(zoom, mode, old, new, maxWidth, maxHeight)
        val horizontalScroll = rememberScrollState()
        val verticalScroll = rememberScrollState()
        LaunchedEffect(zoom, mode, old, new) {
            if (zoom == ImageLayout.FIT) {
                horizontalScroll.scrollTo(0)
                verticalScroll.scrollTo(0)
            }
        }
        // Keep the two scroll axes on separate layout nodes. Putting horizontalScroll and
        // verticalScroll on one Box makes Compose measure its child with unbounded constraints in
        // both directions, which can shrink/crop the Fit canvas and leave no usable scroll range.
        Box(Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            Box(Modifier.fillMaxWidth().horizontalScroll(horizontalScroll)) {
                Box(canvasModifier) { content(Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun canvasSizeModifier(
    zoom: Double,
    mode: CompareMode,
    old: ImageBitmap?,
    new: ImageBitmap?,
    viewportWidth: Dp,
    viewportHeight: Dp,
): Modifier {
    if (zoom == ImageLayout.FIT) return Modifier.requiredSize(viewportWidth, viewportHeight)
    val density = LocalDensity.current
    val boundWidth = maxOf(old?.width ?: 0, new?.width ?: 0)
    val boundHeight = maxOf(old?.height ?: 0, new?.height ?: 0)
    val widthPx = if (mode == CompareMode.TWO_UP) boundWidth * 2 else boundWidth
    val requiredWidth = with(density) { (widthPx * zoom).toFloat().toDp() }.coerceAtLeast(viewportWidth)
    val requiredHeight = with(density) { (boundHeight * zoom).toFloat().toDp() }.coerceAtLeast(viewportHeight)
    return Modifier.requiredSize(requiredWidth, requiredHeight)
}

internal fun sourceLabel(source: BuiltInSource): String =
    if (source == BuiltInSource.WORKING_COPY) "Working copy" else "Test output"

internal fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
