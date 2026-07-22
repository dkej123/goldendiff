package com.github.dkwasniak.goldendiff.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.github.dkwasniak.goldendiff.compare.ImageLayout
import com.github.dkwasniak.goldendiff.compare.IntRect

/** How the two sides of a comparison are presented. */
enum class CompareMode(val label: String) {
    TWO_UP("Side by side"),
    SWIPE("Swipe"),
    ONION("Onion skin"),
    DIFF("Pixel diff"),
}

private val CHECKER_LIGHT = Color(0xFF5A5A5A)
private val CHECKER_DARK = Color(0xFF4A4A4A)

/**
 * Draws the checkerboard that marks transparent areas.
 *
 * Without it a transparent golden is indistinguishable from a white one, which matters constantly:
 * screenshot tools pad images with transparency, and "did the padding change" is a real question.
 */
fun DrawScope.drawCheckerboard(rect: IntRect) {
    if (rect.isEmpty) return
    val tile = ImageLayout.CHECKER_TILE
    clipRect(
        left = rect.x.toFloat(),
        top = rect.y.toFloat(),
        right = (rect.x + rect.width).toFloat(),
        bottom = (rect.y + rect.height).toFloat(),
    ) {
        var row = 0
        var y = rect.y
        while (y < rect.y + rect.height) {
            var col = 0
            var x = rect.x
            while (x < rect.x + rect.width) {
                drawRect(
                    color = if ((row + col) % 2 == 0) CHECKER_LIGHT else CHECKER_DARK,
                    topLeft = Offset(x.toFloat(), y.toFloat()),
                    size = Size(tile.toFloat(), tile.toFloat()),
                )
                x += tile
                col++
            }
            y += tile
            row++
        }
    }
}

/**
 * Draws [image] to fill [rect] exactly.
 *
 * The rect already carries the image's aspect ratio (it comes from [ImageLayout]), so no second fit
 * is applied. A second, independently rounded fit used to leave the image a pixel short of the rect
 * and let the checkerboard painted underneath show as a thin sliver along one edge.
 */
fun DrawScope.drawImageIn(image: ImageBitmap, rect: IntRect) {
    if (rect.isEmpty) return
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(rect.x, rect.y),
        dstSize = IntSize(rect.width, rect.height),
    )
}

/**
 * Shared placement for a pair of images on one canvas.
 *
 * Both are scaled by the SAME factor derived from their bounding box, then bottom-anchored. Scaling
 * each to fit its own area independently would make a taller image look the same size as a shorter
 * one and hide exactly the regression the tool exists to show.
 */
fun pairLayout(
    zoom: Double,
    old: ImageBitmap?,
    new: ImageBitmap?,
    areaW: Int,
    areaH: Int,
): Pair<IntRect, IntRect> {
    val (boundW, boundH) = ImageLayout.boundingSize(
        old?.width ?: 0, old?.height ?: 0,
        new?.width ?: 0, new?.height ?: 0,
    )
    if (boundW <= 0 || boundH <= 0) return IntRect.EMPTY to IntRect.EMPTY
    val scale = ImageLayout.scaleForBounding(zoom, boundW, boundH, areaW, areaH)
    val area = IntRect(0, 0, areaW, areaH)
    val oldRect = old?.let { ImageLayout.bottomRect(it.width, it.height, scale, area) } ?: IntRect.EMPTY
    val newRect = new?.let { ImageLayout.bottomRect(it.width, it.height, scale, area) } ?: IntRect.EMPTY
    return oldRect to newRect
}

/** Onion skin: the two images stacked, with [opacity] fading between them. */
@Composable
fun OnionSkinView(old: ImageBitmap?, new: ImageBitmap?, zoom: Double, opacity: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val (oldRect, newRect) = pairLayout(zoom, old, new, size.width.toInt(), size.height.toInt())
        if (old != null) {
            drawCheckerboard(oldRect)
            drawImageIn(old, oldRect)
        }
        if (new != null) {
            drawContext.canvas.saveLayer(
                androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                androidx.compose.ui.graphics.Paint().apply { alpha = opacity },
            )
            drawImageIn(new, newRect)
            drawContext.canvas.restore()
        }
    }
}

/**
 * Swipe: one image revealed over the other by a draggable divider.
 *
 * The divider position is content-relative rather than window-relative, so it stays on the same part
 * of the screenshot when the window is resized.
 */
@Composable
fun SwipeView(old: ImageBitmap?, new: ImageBitmap?, zoom: Double, modifier: Modifier = Modifier) {
    var fraction by remember { mutableStateOf(0.5f) }
    var width by remember { mutableStateOf(1) }

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                }
            },
        ) {
            width = size.width.toInt()
            val (oldRect, newRect) = pairLayout(zoom, old, new, size.width.toInt(), size.height.toInt())
            if (old != null) {
                drawCheckerboard(oldRect)
                drawImageIn(old, oldRect)
            }
            if (new != null) {
                clipRect(left = 0f, top = 0f, right = size.width * fraction, bottom = size.height) {
                    drawCheckerboard(newRect)
                    drawImageIn(new, newRect)
                }
            }
            val x = size.width * fraction
            drawLine(
                color = Color(0xFFFF00FF),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
        }
    }
}

/**
 * Side by side: the two images in half the width each, sharing one scale.
 *
 * The shared scale is the whole point — fitting each half independently would render a 1080x2400
 * golden and a 1080x1200 one at the same on-screen height and hide the size change entirely.
 */
@Composable
fun TwoUpView(old: ImageBitmap?, new: ImageBitmap?, zoom: Double, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val halfWidth = (size.width / 2).toInt()
        val height = size.height.toInt()
        val (boundW, boundH) = ImageLayout.boundingSize(
            old?.width ?: 0, old?.height ?: 0,
            new?.width ?: 0, new?.height ?: 0,
        )
        if (boundW <= 0 || boundH <= 0) return@Canvas
        val scale = ImageLayout.scaleForBounding(zoom, boundW, boundH, halfWidth, height)

        old?.let {
            val rect = ImageLayout.bottomRect(it.width, it.height, scale, IntRect(0, 0, halfWidth, height))
            drawCheckerboard(rect)
            drawImageIn(it, rect)
        }
        new?.let {
            val rect = ImageLayout.bottomRect(it.width, it.height, scale, IntRect(halfWidth, 0, halfWidth, height))
            drawCheckerboard(rect)
            drawImageIn(it, rect)
        }
        drawLine(
            color = Color(0x40FFFFFF),
            start = Offset(halfWidth.toFloat(), 0f),
            end = Offset(halfWidth.toFloat(), size.height),
            strokeWidth = 1f,
        )
    }
}

/** A single image, centered and zoom-aware. Used for "no changes vs HEAD" and for new files. */
@Composable
fun SingleImageView(image: ImageBitmap?, zoom: Double, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        if (image == null) return@Canvas
        val rect = ImageLayout.renderRect(zoom, image.width, image.height, size.width.toInt(), size.height.toInt())
        drawCheckerboard(rect)
        drawImageIn(image, rect)
    }
}
