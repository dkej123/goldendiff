package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.EmptyState
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.Pane
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens
import com.github.dkwasniak.goldendiff.compare.TransparentBorder
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItem
import com.github.dkwasniak.goldendiff.variant.ExtraComparisonItemStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.imageio.ImageIO

/**
 * Middle pane: every golden in the current scope, as a wrapping grid of cards.
 *
 * There is no grid/list switch. The grid already collapses to a single column when the cards no
 * longer fit side by side, so widening the card or narrowing the pane produces the list layout on
 * its own — a mode toggle would only let the two disagree.
 */
@Composable
fun GoldensPane(state: AppState, modifier: Modifier) {
    val focusRequester = remember { FocusRequester() }

    Pane(modifier) {
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Goldens", color = tokens.text, fontSize = Type.panelTitle, fontWeight = FontWeight.SemiBold)
            Text(
                if (state.busy) "Scanning…" else state.summaryText,
                color = tokens.textFaint,
                fontSize = Type.small,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            ScaleStepper(state)
        }
        HairLine()

        when {
            state.busy -> CenteredInPane {
                EmptyState(
                    "Scanning for screenshots…",
                    "Comparing Git HEAD against the working copy and test output.",
                    compact = true,
                )
            }
            state.items.isEmpty() -> CenteredInPane {
                EmptyState(
                    "No screenshots match",
                    state.status.ifBlank { "Nothing changed for the current scope and filters." },
                    compact = true,
                )
            }
            else -> GoldenGrid(state, focusRequester)
        }
    }
}

/** `− 130% +`, stepping the preview size in 10% notches between 10% and 200%. */
@Composable
private fun ScaleStepper(state: AppState) {
    val scale = state.ui.thumbnailScale
    StepperButton("−", enabled = scale > ThumbnailScale.MIN) { state.ui.stepThumbnailScale(-ThumbnailScale.STEP) }
    Text(
        "$scale%",
        color = tokens.textDim,
        fontSize = Type.small,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(38.dp),
    )
    StepperButton("+", enabled = scale < ThumbnailScale.MAX) { state.ui.stepThumbnailScale(ThumbnailScale.STEP) }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.size(22.dp).clip(shape).hoverWash(enabled, shape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tokens.textDim.copy(alpha = if (enabled) 1f else 0.35f), fontSize = Type.body)
    }
}

@Composable
private fun CenteredInPane(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun GoldenGrid(state: AppState, focusRequester: FocusRequester) {
    val cardWidth = ThumbnailScale.gridWidth(state.ui.thumbnailScale).dp
    val gridState = rememberLazyGridState()
    val selectedIndex = state.items.indexOfFirst { it.file == state.selected }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) gridState.animateScrollToItem(selectedIndex)
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val spacing = 12.dp
        val columnCount = ((maxWidth - 28.dp + spacing) / (cardWidth + spacing)).toInt().coerceAtLeast(1)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cardWidth),
            state = gridState,
            modifier = Modifier.fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .selectionKeys(state, columnCount)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(state.items, key = { it.file.path }) { item ->
                // The grid columns stretch to fill the pane, so a card that filled its cell would
                // look identical across scale steps that keep the column count the same (60% and
                // 70% both fit N columns → same stretched width). Pinning the card to the exact
                // cardWidth and centering it in the cell makes every 10% step change the size.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GoldenCard(state, item, cardWidth) {
                        focusRequester.requestFocus()
                        state.select(item.file, "grid")
                    }
                }
            }
        }
    }
}

/** Arrow-key navigation over the flat item list; [columnCount] is 1 in list view. */
private fun Modifier.selectionKeys(state: AppState, columnCount: Int): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionRight -> { state.moveSelectionBy(1); true }
        Key.DirectionLeft -> { state.moveSelectionBy(-1); true }
        Key.DirectionDown -> { state.moveSelectionBy(columnCount); true }
        Key.DirectionUp -> { state.moveSelectionBy(-columnCount); true }
        else -> false
    }
}

@Composable
private fun GoldenCard(state: AppState, item: ExtraComparisonItem, cardWidth: Dp, onSelect: () -> Unit) {
    val selected = state.selected == item.file
    val bitmap by rememberThumbnail(state, item)
    val shape = RoundedCornerShape(8.dp)

    FileContextMenu(state, item.file, canDelete = true) {
        Column(
            Modifier.width(cardWidth)
                // The glow ring is a second, wider border outside the accent one; drawn first so
                // the crisp 2px edge stays on top of the soft tint.
                .then(if (selected) Modifier.border(3.dp, tokens.accentBg, RoundedCornerShape(11.dp)) else Modifier)
                .clip(shape)
                .background(tokens.panel)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) tokens.accent else tokens.border,
                    shape,
                )
                .hoverWash(!selected, shape)
                .clickable(onClick = onSelect),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio(bitmap?.let { it.width.toFloat() / it.height } ?: 0.47f)
                    .background(tokens.background),
                contentAlignment = Alignment.Center,
            ) {
                val image = bitmap
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        if (item.isLoading) "loading…" else "missing",
                        color = tokens.textFaint,
                        fontSize = 9.5.sp,
                        fontFamily = Type.Mono,
                    )
                }
            }
            HairLine()
            // Status lives in a slim 4px bar at the card's foot — red for changed, green for new,
            // nothing for unchanged — so a wall of thumbnails stays legible and the screenshot is
            // never covered by a floating dot. The filename shows in the compare header, not here.
            Box(Modifier.fillMaxWidth().height(4.dp).background(statusBarColor(item.status)))
        }
    }
}

/**
 * Decodes a golden off the UI thread, keyed by path, mtime and the trim setting.
 *
 * The mtime is part of the key on purpose: a test run rewrites goldens in place, and without it the
 * grid would keep showing the previous decode after a refresh.
 */
@Composable
private fun rememberThumbnail(state: AppState, item: ExtraComparisonItem) =
    produceState<ImageBitmap?>(
        null,
        item.file.path,
        item.file.lastModified(),
        state.config.trimTransparentPadding,
    ) {
        val trim = state.config.trimTransparentPadding
        val key = "${item.file.path}|${item.file.lastModified()}|$trim"
        // Reuse a decode across tab switches: returning to a tab paints its thumbnails from the cache
        // instead of reading and trimming the PNG again.
        state.thumbnailCache[key]?.let { value = it; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching { ImageIO.read(item.file) }.getOrNull()
                ?.let { TransparentBorder.trim(it, trim) ?: it }
                ?.toComposeImageBitmap()
        }?.also { state.thumbnailCache[key] = it }
    }

/** The 4px foot bar's fill; transparent when the golden did not change. */
@Composable
private fun statusBarColor(status: ExtraComparisonItemStatus): Color = when (status) {
    ExtraComparisonItemStatus.MODIFIED -> tokens.changed
    ExtraComparisonItemStatus.NEW -> tokens.new
    ExtraComparisonItemStatus.UNCHANGED -> Color.Transparent
}
