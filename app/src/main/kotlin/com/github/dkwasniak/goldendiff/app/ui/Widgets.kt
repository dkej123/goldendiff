package com.github.dkwasniak.goldendiff.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The flat hover wash from the handoff: a 4-5% overlay, no motion and no scale.
 *
 * Every interactive surface uses this one helper so hover reads identically in the tree, the grid,
 * the toolbar and the settings nav.
 */
@Composable
fun Modifier.hoverWash(enabled: Boolean = true, shape: RoundedCornerShape = RoundedCornerShape(Dimens.controlRadius)): Modifier {
    if (!enabled) return this
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    return hoverable(interaction).background(if (hovered) tokens.hover else Color.Transparent, shape)
}

/**
 * A floating rounded "tool window" pane — the visual signature of the redesign.
 *
 * The shadow is drawn before the clip so it falls outside the rounded rect; clipping first would
 * swallow it and leave the pane flat against the app background.
 */
@Composable
fun Pane(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .shadow(6.dp, RoundedCornerShape(Dimens.panelRadius), clip = false)
            .clip(RoundedCornerShape(Dimens.panelRadius))
            .background(tokens.panel)
            .border(1.dp, tokens.border, RoundedCornerShape(Dimens.panelRadius)),
    ) {
        content()
    }
}

/** Flat bordered toolbar button, 6px radius. */
@Composable
fun ToolButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Text(
        label,
        color = tokens.text.copy(alpha = if (enabled) 1f else 0.4f),
        fontSize = Type.body,
        maxLines = 1,
        modifier = Modifier.clip(shape)
            .border(1.dp, tokens.border, shape)
            .hoverWash(enabled, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Primary action: solid accent fill, white label. */
@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Text(
        label,
        color = tokens.onAccent,
        fontSize = Type.panelTitle,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(shape)
            .background(tokens.accent.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

/** Borderless secondary action, used for Cancel next to [PrimaryButton]. */
@Composable
fun GhostButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Text(
        label,
        color = tokens.textDim,
        fontSize = Type.panelTitle,
        modifier = Modifier.clip(shape).hoverWash(shape = shape).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

/**
 * Toolbar dropdown: a dim `label:` prefix with the current value in bold, so the eye lands on the
 * value rather than on the four labels that never change.
 */
@Composable
fun <T> DropdownButton(
    label: String,
    selected: T,
    choices: List<T>,
    optionLabel: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Box {
        Row(
            Modifier.clip(shape).border(1.dp, tokens.border, shape).hoverWash(enabled, shape)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$label: ", color = tokens.textDim, fontSize = Type.body)
            Text(optionLabel(selected), color = tokens.text, fontSize = Type.body, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(5.dp))
            Chevron(Direction.DOWN, tokens.textFaint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(onClick = {
                    expanded = false
                    onSelect(choice)
                }) {
                    Text(optionLabel(choice), color = tokens.text, fontSize = Type.body)
                }
            }
        }
    }
}

/** One segment of a segmented control: accent text over an accent tint when active. */
@Composable
fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Text(
        label,
        color = if (selected) tokens.accent else tokens.textDim,
        fontSize = Type.body,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier.clip(shape)
            .background(if (selected) tokens.accentBg else Color.Transparent, shape)
            .hoverWash(!selected, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Compact segment used for the Grid/List and S/M/L toggles. */
@Composable
fun MiniSegment(label: String, selected: Boolean, square: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    val base = Modifier.clip(shape)
        .background(if (selected) tokens.accentBg else Color.Transparent, shape)
        .hoverWash(!selected, shape)
        .clickable(onClick = onClick)
    Box(
        if (square) base.size(22.dp) else base.padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) tokens.accent else tokens.textFaint,
            fontSize = Type.small,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 7px status dot; also used at 8px on grid cards. */
@Composable
fun StatusDot(color: Color, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(50)).background(color))
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(color)
        Text(label, color = tokens.textDim, fontSize = Type.small)
    }
}

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(tokens.border))
}

@Composable
fun VerticalHairLine(height: Dp) {
    Box(Modifier.width(1.dp).height(height).background(tokens.border))
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

/** Geometric chevron — no icon assets ship with the app, everything is drawn. */
@Composable
fun Chevron(direction: Direction, color: Color, size: Dp = 9.dp) {
    Canvas(Modifier.size(size)) {
        val degrees = when (direction) {
            Direction.DOWN -> 0f
            Direction.LEFT -> 90f
            Direction.UP -> 180f
            Direction.RIGHT -> 270f
        }
        rotate(degrees) {
            val path = Path().apply {
                moveTo(this@Canvas.size.width * 0.2f, this@Canvas.size.height * 0.38f)
                lineTo(this@Canvas.size.width * 0.5f, this@Canvas.size.height * 0.68f)
                lineTo(this@Canvas.size.width * 0.8f, this@Canvas.size.height * 0.38f)
            }
            drawPath(path, color, style = Stroke(width = 1.3.dp.toPx()))
        }
    }
}

/** Folder glyph: a rounded rect with the small tab notch on its top-left corner. */
@Composable
fun FolderIcon(color: Color) {
    Canvas(Modifier.size(width = 13.dp, height = 13.dp)) {
        val tabWidth = 6.dp.toPx()
        val tabHeight = 3.dp.toPx()
        val bodyTop = 3.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, bodyTop - tabHeight),
            size = Size(tabWidth, tabHeight * 2),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, bodyTop),
            size = Size(13.dp.toPx(), 10.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
    }
}

/** File glyph: a rect with the top-right corner folded away. */
@Composable
fun FileIcon(color: Color) {
    Canvas(Modifier.size(width = 10.dp, height = 12.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * 0.65f, 0f)
            lineTo(w, h * 0.32f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(path, color.copy(alpha = 0.55f))
    }
}

@Composable
fun SearchIcon(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val radius = 4.2.dp.toPx()
        val center = Offset(radius + 0.7.dp.toPx(), radius + 0.7.dp.toPx())
        drawCircle(color, radius = radius, center = center, style = Stroke(width = 1.4.dp.toPx()))
        drawLine(
            color = color,
            start = Offset(center.x + radius * 0.75f, center.y + radius * 0.75f),
            end = Offset(size.width, size.height),
            strokeWidth = 1.4.dp.toPx(),
        )
    }
}

/** Copy glyph: two overlapping rounded rects, the front one filled with the panel colour. */
@Composable
fun CopyIcon(back: Color, front: Color, fill: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = Stroke(width = 1.3.dp.toPx())
        val corner = CornerRadius(2.dp.toPx())
        drawRoundRect(
            back,
            topLeft = Offset(4.dp.toPx(), 0f),
            size = Size(10.dp.toPx(), 12.dp.toPx()),
            cornerRadius = corner,
            style = stroke,
        )
        drawRoundRect(fill, topLeft = Offset(0f, 3.dp.toPx()), size = Size(10.dp.toPx(), 12.dp.toPx()), cornerRadius = corner)
        drawRoundRect(
            front,
            topLeft = Offset(0f, 3.dp.toPx()),
            size = Size(10.dp.toPx(), 12.dp.toPx()),
            cornerRadius = corner,
            style = stroke,
        )
    }
}

/** Expand glyph: two arrowheads pointing to opposite corners, for "open in a separate window". */
@Composable
fun ExpandIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val stroke = Stroke(width = 1.3.dp.toPx())
        val inset = 2.dp.toPx()
        val len = 4.dp.toPx()
        val w = size.width
        val h = size.height
        // Top-left corner arrowhead.
        drawPath(
            Path().apply {
                moveTo(inset, inset + len)
                lineTo(inset, inset)
                lineTo(inset + len, inset)
            },
            color,
            style = stroke,
        )
        // Bottom-right corner arrowhead.
        drawPath(
            Path().apply {
                moveTo(w - inset, h - inset - len)
                lineTo(w - inset, h - inset)
                lineTo(w - inset - len, h - inset)
            },
            color,
            style = stroke,
        )
        // Diagonal joining the two corners.
        drawLine(color, Offset(inset, inset), Offset(w - inset, h - inset), strokeWidth = 1.3.dp.toPx())
    }
}

/** Close glyph: a crisp X, drawn so it sits dead-centre in its hit area. */
@Composable
fun CloseIcon(color: Color) {
    Canvas(Modifier.size(8.dp)) {
        val width = 1.3.dp.toPx()
        drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = width)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = width)
    }
}

/** Square icon-button wrapper, used for the collapse chevron and the copy-path action. */
@Composable
fun IconButton(size: Dp = 22.dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Box(
        Modifier.size(size).clip(shape).hoverWash(shape = shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Bordered pill input, 6px radius, with a 2px accent ring while focused. */
@Composable
fun FilterField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.5.sp,
    onValueChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Dimens.controlRadius)
    Row(
        modifier.height(28.dp).clip(shape).background(tokens.inputBackground)
            .border(if (focused) 2.dp else 1.dp, if (focused) tokens.accent else tokens.border, shape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SearchIcon(tokens.textFaint)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = tokens.textFaint, fontSize = fontSize, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = tokens.text, fontSize = fontSize),
                cursorBrush = SolidColor(tokens.accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

/** Centred icon/title/body/action block shared by every empty and error state. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.padding(if (compact) 24.dp else 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(if (compact) 34.dp else 40.dp)
                .border(1.5.dp, tokens.textFaint.copy(alpha = 0.55f), RoundedCornerShape(if (compact) 8.dp else 10.dp)),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            title,
            color = tokens.text,
            fontSize = if (compact) Type.header else Type.large,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            body,
            color = tokens.textDim,
            fontSize = if (compact) 11.5.sp else Type.panelTitle,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(if (compact) 240.dp else 320.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(2.dp))
            PrimaryButton(actionLabel, onClick = onAction)
        }
    }
}
