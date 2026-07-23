package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dkwasniak.goldendiff.app.ui.Dimens
import com.github.dkwasniak.goldendiff.app.ui.HairLine
import com.github.dkwasniak.goldendiff.app.ui.SearchIcon
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.hoverWash
import com.github.dkwasniak.goldendiff.app.ui.tokens

/** ⇧⌘O: fuzzy file search over the project index, with the matched characters called out. */
@Composable
fun QuickOpenDialog(state: AppState) {
    val results = state.quickOpenResults
    var selectedIndex by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(results, selectedIndex) {
        if (results.isEmpty()) {
            selectedIndex = 0
        } else {
            selectedIndex = selectedIndex.coerceIn(0, results.lastIndex)
            listState.animateScrollToItem(selectedIndex)
        }
    }

    fun choose(index: Int) {
        results.getOrNull(index)?.let { path ->
            state.quickOpenVisible = false
            state.selectSourceFile(path, "quick_open")
        }
    }

    Box(
        Modifier.fillMaxSize().background(tokens.scrim).clickable { state.quickOpenVisible = false },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 140.dp).width(560.dp)
                .shadow(24.dp, RoundedCornerShape(Dimens.panelRadius))
                .clip(RoundedCornerShape(Dimens.panelRadius))
                .background(tokens.panelHeader)
                .border(1.dp, tokens.border, RoundedCornerShape(Dimens.panelRadius))
                // Swallow clicks so they do not reach the scrim's dismiss handler.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            if (results.isNotEmpty()) selectedIndex = (selectedIndex + 1).coerceAtMost(results.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            if (results.isNotEmpty()) selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            choose(selectedIndex)
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchIcon(tokens.textFaint)
                Box(Modifier.weight(1f)) {
                    if (state.quickOpenQuery.isEmpty()) {
                        Text("Search files…", color = tokens.textFaint, fontSize = Type.large)
                    }
                    BasicTextField(
                        value = state.quickOpenQuery,
                        onValueChange = {
                            state.quickOpenQuery = it
                            selectedIndex = 0
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = tokens.text, fontSize = Type.large, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(tokens.accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                }
            }
            HairLine()

            if (results.isEmpty()) {
                Text(
                    if (state.quickOpenQuery.isBlank()) "Type to search the project." else "No files match.",
                    color = tokens.textFaint,
                    fontSize = Type.body,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 280.dp).padding(vertical = 8.dp), state = listState) {
                    itemsIndexed(results, key = { _, path -> path }) { index, path ->
                        ResultRow(
                            path = path,
                            query = state.quickOpenQuery,
                            selected = index == selectedIndex,
                            onClick = { choose(index) },
                        )
                    }
                }
            }
            HairLine()
            Text(
                "↑↓ navigate · ↵ open · esc close",
                color = tokens.textFaint,
                fontSize = Type.thumbLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ResultRow(path: String, query: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    val name = path.substringAfterLast('/')
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(shape)
            .background(if (selected) tokens.accentBg else Color.Transparent, shape)
            .hoverWash(!selected, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(highlighted(name, query), fontSize = Type.header, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            path,
            color = tokens.textFaint,
            fontSize = Type.thumbLabel,
            fontFamily = Type.Mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Bolds the characters [query] matched, in accent.
 *
 * Recomputed here as a plain greedy subsequence rather than returned by [FuzzyFileMatcher]: the
 * matcher ranks candidates and has several scoring paths, and threading positions back out of all
 * of them would complicate the matcher to decorate a label.
 */
@Composable
private fun highlighted(name: String, query: String): AnnotatedString {
    val needle = query.trim()
    val matchStyle = SpanStyle(color = tokens.accent, fontWeight = FontWeight.Bold)
    val restStyle = SpanStyle(color = tokens.text)
    if (needle.isEmpty()) return AnnotatedString(name, restStyle)

    val hits = mutableSetOf<Int>()
    var cursor = 0
    for (character in needle) {
        val index = name.indexOf(character, cursor, ignoreCase = true)
        if (index < 0) {
            hits.clear()
            break
        }
        hits += index
        cursor = index + 1
    }

    return buildAnnotatedString {
        name.forEachIndexed { index, character ->
            withStyle(if (index in hits) matchStyle else restStyle) { append(character) }
        }
    }
}
