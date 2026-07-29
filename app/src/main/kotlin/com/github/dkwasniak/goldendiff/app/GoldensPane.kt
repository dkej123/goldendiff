package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
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
import com.github.dkwasniak.goldendiff.app.ui.CheckIcon
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.EmptyState
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.Pane
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens
import com.github.dkwasniak.goldendiff.compare.TransparentBorder
import com.github.dkwasniak.goldendiff.scan.MergedGoldenStatus
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
    // Keep the viewport alive while a background refresh temporarily replaces the grid with its
    // loading/empty state. Recreating this inside GoldenGrid used to snap back to the selected card.
    val gridState = rememberLazyGridState()
    // Scroll bookkeeping belongs next to the viewport, for the same reason: GoldenGrid's effects
    // restart from scratch on every rebuild, and without a record of what they already did they
    // replay their scroll — snapping a manually scrolled grid back.
    val scrollMemory = remember { GridScrollMemory() }
    val contentKey = "${state.selectedSourcePath.orEmpty()}|${state.selectedSnapshotCaseId.orEmpty()}"

    Pane(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Goldens",
                    color = tokens.text,
                    fontSize = Type.panelTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (state.busy) "Scanning…" else state.summaryText,
                    color = tokens.textFaint,
                    fontSize = Type.small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val acceptableCount = state.acceptableCurrentFileGoldens.size
                if (shouldShowAcceptAll(acceptableCount)) {
                    AcceptAllButton(acceptableCount, enabled = !state.commandRunning) {
                        state.requestAcceptAll()
                    }
                }
                Spacer(Modifier.weight(1f))
                ScaleStepper(state)
            }
        }
        HairLine()

        when {
            state.busy && state.items.isEmpty() -> CenteredInPane {
                EmptyState(
                    "Scanning for screenshots…",
                    "Comparing Git HEAD against the working copy and test output.",
                    compact = true,
                )
            }
            state.items.isEmpty() -> CenteredInPane {
                EmptyState(
                    "No screenshots match",
                    state.status.ifBlank { "Nothing changed for the current file and filters." },
                    compact = true,
                )
            }
            else -> GoldenGrid(state, focusRequester, gridState, scrollMemory, contentKey)
        }
    }
}

internal fun shouldShowAcceptAll(count: Int): Boolean = count > 0

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

/** What the grid's scroll effects have already acted on; survives a rebuild of [GoldenGrid]. */
private class GridScrollMemory {
    var contentKey: String? = null
    var handledScrollSequence = 0L
}

@Composable
private fun GoldenGrid(
    state: AppState,
    focusRequester: FocusRequester,
    gridState: LazyGridState,
    scrollMemory: GridScrollMemory,
    contentKey: String,
) {
    val cardWidth = ThumbnailScale.gridWidth(state.ui.thumbnailScale).dp

    // A genuinely different file/case starts at the top. Refreshes preserve the viewport — including
    // the ones that swap the grid for the loading state and back, which is why the last key seen is
    // remembered outside this composable.
    LaunchedEffect(contentKey) {
        if (scrollMemory.contentKey == contentKey) return@LaunchedEffect
        scrollMemory.contentKey = contentKey
        if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
            gridState.scrollToItem(0)
        }
    }
    // Only arrow-key navigation follows the selected card, and only far enough to bring it fully
    // into view. Mouse selection is already visible, and background selection restoration must never
    // fight a user's manual scroll.
    LaunchedEffect(state.goldenScrollRequest?.sequence) {
        val request = state.goldenScrollRequest ?: return@LaunchedEffect
        // A user scroll cancels ours mid-suspend and this effect dies before it can clear the
        // request, so the guard against replaying it is the handled sequence, not the state field.
        if (request.sequence <= scrollMemory.handledScrollSequence) return@LaunchedEffect
        scrollMemory.handledScrollSequence = request.sequence
        val index = state.items.indexOfFirst { it.file.normalize().path == request.path }
        if (index >= 0) gridState.scrollItemFullyIntoView(index)
        state.consumeGoldenScrollRequest(request.sequence)
    }

    // Looking a card's status up in mergedItems means a linear scan with a File.normalize() per
    // entry; done inside the card it ran for every visible card on every frame of a scroll.
    val statusByPath = remember(state.mergedItems) {
        state.mergedItems.associate { it.golden.normalize().path to it.displayStatus }
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
                    GoldenCard(state, item, cardWidth, statusByPath[item.file.normalize().path]) {
                        focusRequester.requestFocus()
                        state.select(item.file, "grid")
                    }
                }
            }
        }
    }
}

/**
 * Scrolls by the smallest amount that shows item [index] whole; does nothing when it already is.
 *
 * The grid pads itself with a plain [Modifier.padding] rather than content padding, so the visible
 * range is exactly the viewport and item offsets share its coordinate space.
 */
private suspend fun LazyGridState.scrollItemFullyIntoView(index: Int) {
    val info = layoutInfo
    // Not composed at all — too far away to nudge, so jump and put it at the top edge.
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: run {
        scrollToItem(index)
        return
    }
    val delta = when {
        item.offset.y < info.viewportStartOffset -> item.offset.y - info.viewportStartOffset
        item.offset.y + item.size.height > info.viewportEndOffset ->
            item.offset.y + item.size.height - info.viewportEndOffset
        else -> return
    }
    scrollBy(delta.toFloat())
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
private fun GoldenCard(
    state: AppState,
    item: ExtraComparisonItem,
    cardWidth: Dp,
    mergedStatus: MergedGoldenStatus?,
    onSelect: () -> Unit,
) {
    val selected = state.selected == item.file
    val accepted = item.file.absolutePath in state.acceptedGoldens
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
                // The grid owns keyboard focus. A focused lazy child is pinned/kept visible by
                // Compose and otherwise pulls manual scrolling back when the clicked card leaves
                // the viewport.
                .focusProperties { canFocus = false }
                .clickable(onClick = onSelect),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    // Falling straight back to the placeholder ratio while a thumbnail is missing
                    // would resize the card — and with it the grid's total height — every time one
                    // scrolls back into view, which is what made a scroll near the end of the list
                    // jump backwards. The last known ratio keeps the cell the size it just had.
                    .aspectRatio(
                        bitmap?.let { it.width.toFloat() / it.height }
                            ?: state.thumbnailRatios[item.file.absolutePath]
                            ?: 0.47f,
                    )
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
                if (accepted) {
                    AcceptedBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
                }
            }
            HairLine()
            // Status lives in a slim 4px bar at the card's foot — red for changed, green for new,
            // nothing for unchanged — so a wall of thumbnails stays legible and the screenshot is
            // never covered by a floating dot. The filename shows in the compare header, not here.
            Box(
                Modifier.fillMaxWidth().height(4.dp)
                    .background(if (accepted) tokens.new else statusBarColor(mergedStatus, item.status)),
            )
        }
    }
}

/** A small green pill overlaid on a just-accepted golden's thumbnail. */
@Composable
private fun AcceptedBadge(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier.clip(shape).background(tokens.new).padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        CheckIcon(Color.White, size = 7.dp, strokeWidth = 1.8.dp)
        Text("Accepted", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Decodes a golden off the UI thread, keyed by path, mtime and the trim setting.
 *
 * The mtime is part of the key on purpose: a test run rewrites goldens in place, and without it the
 * grid would keep showing the previous decode after a refresh.
 */
@Composable
private fun rememberThumbnail(state: AppState, item: ExtraComparisonItem): State<ImageBitmap?> {
    val trim = state.config.trimTransparentPadding
    val key = "${item.file.path}|${item.file.lastModified()}|$trim"
    // Reuse a decode across tab switches and re-entries into the viewport: returning to a card paints
    // it from the cache instead of reading and trimming the PNG again. The cached value has to be the
    // *initial* one — delivering it from the coroutine a frame later would paint one frame at the
    // placeholder size first, resizing the card and the scrollable content under the user's scroll.
    val cached = state.thumbnailCache[key]
    return produceState(cached, key, state.pngRevision) {
        if (cached != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { ImageIO.read(item.file) }.getOrNull()
                ?.let { TransparentBorder.trim(it, trim) ?: it }
                ?.toComposeImageBitmap()
        }?.also {
            state.thumbnailCache[key] = it
            state.thumbnailRatios[item.file.absolutePath] = it.width.toFloat() / it.height
        }
    }
}

/** The 4px foot bar's fill; transparent when the golden did not change. */
@Composable
private fun statusBarColor(
    merged: MergedGoldenStatus?,
    fallback: ExtraComparisonItemStatus,
): Color = when (merged) {
    MergedGoldenStatus.FAILED -> tokens.changed
    MergedGoldenStatus.MODIFIED -> tokens.modified
    MergedGoldenStatus.NEW -> tokens.new
    MergedGoldenStatus.UNCHANGED -> Color.Transparent
    null -> when (fallback) {
        ExtraComparisonItemStatus.MODIFIED -> tokens.modified
        ExtraComparisonItemStatus.NEW -> tokens.new
        ExtraComparisonItemStatus.UNCHANGED -> Color.Transparent
    }
}

@Composable
private fun AcceptAllButton(count: Int, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Text(
        "Accept all ($count)",
        color = Color.White.copy(alpha = if (enabled) 1f else 0.55f),
        fontSize = Type.small,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(shape)
            .background(tokens.changed.copy(alpha = if (enabled) 1f else 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}
