package com.github.dkwasniak.goldendiff.app

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import com.github.dkwasniak.goldendiff.app.ui.CheckIcon
import com.github.dkwasniak.goldendiff.app.ui.CloseIcon
import com.github.dkwasniak.goldendiff.app.ui.CopyIcon
import com.github.dkwasniak.goldendiff.app.ui.FolderIcon
import com.github.dkwasniak.goldendiff.app.ui.PlayIcon
import com.github.dkwasniak.goldendiff.app.ui.Tokens
import com.github.dkwasniak.goldendiff.app.ui.Type
import com.github.dkwasniak.goldendiff.app.ui.hoverWash

internal enum class ContextActionIcon {
    GENERATE,
    VERIFY,
    FINDER,
    COPY,
    DELETE,
    LOG,
}

internal class ActionContextMenuItem(
    label: String,
    val icon: ContextActionIcon,
    onClick: () -> Unit,
) : ContextMenuItem(label, onClick)

internal class HeaderContextMenuItem(label: String) : ContextMenuItem(label, {})

internal class SeparatorContextMenuItem : ContextMenuItem("", {})

internal class ThemedContextMenuRepresentation(
    private val palette: Tokens,
) : ContextMenuRepresentation {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status as? ContextMenuState.Status.Open ?: return
        var focusManager: FocusManager? by mutableStateOf(null)
        var inputModeManager: InputModeManager? by mutableStateOf(null)

        Popup(
            properties = PopupProperties(focusable = true),
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
            onKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key.nativeKeyCode) {
                        java.awt.event.KeyEvent.VK_DOWN -> {
                            inputModeManager?.requestInputMode(InputMode.Keyboard)
                            focusManager?.moveFocus(FocusDirection.Next)
                            true
                        }
                        java.awt.event.KeyEvent.VK_UP -> {
                            inputModeManager?.requestInputMode(InputMode.Keyboard)
                            focusManager?.moveFocus(FocusDirection.Previous)
                            true
                        }
                        else -> false
                    }
                }
            },
        ) {
            focusManager = LocalFocusManager.current
            inputModeManager = LocalInputModeManager.current
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier.shadow(12.dp, shape)
                    .clip(shape)
                    .background(palette.panel)
                    .border(1.dp, palette.borderStrong, shape)
                    .padding(vertical = 6.dp)
                    .width(IntrinsicSize.Max)
                    .sizeIn(minWidth = 250.dp, maxWidth = 360.dp),
            ) {
                items().forEach { item ->
                    when (item) {
                        is HeaderContextMenuItem -> ContextMenuHeader(item.label)
                        is SeparatorContextMenuItem -> Box(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp).height(1.dp)
                                .background(palette.border),
                        )
                        is ActionContextMenuItem -> ContextMenuAction(item) {
                            state.status = ContextMenuState.Status.Closed
                            item.onClick()
                        }
                        else -> ContextMenuAction(
                            ActionContextMenuItem(item.label, ContextActionIcon.LOG, item.onClick),
                        ) {
                            state.status = ContextMenuState.Status.Closed
                            item.onClick()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ContextMenuHeader(label: String) {
        Text(
            label,
            color = palette.textFaint,
            fontSize = Type.small,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }

    @Composable
    private fun ContextMenuAction(item: ActionContextMenuItem, onClick: () -> Unit) {
        val shape = RoundedCornerShape(5.dp)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp).height(34.dp)
                .clip(shape)
                .hoverWash(shape = shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                when (item.icon) {
                    ContextActionIcon.GENERATE -> PlayIcon(palette.textDim)
                    ContextActionIcon.VERIFY -> CheckIcon(palette.new, size = 12.dp, strokeWidth = 1.8.dp)
                    ContextActionIcon.FINDER -> FolderIcon(palette.textDim)
                    ContextActionIcon.COPY ->
                        CopyIcon(back = palette.textFaint, front = palette.textDim, fill = palette.panel)
                    ContextActionIcon.DELETE -> CloseIcon(palette.changed)
                    ContextActionIcon.LOG -> Box(
                        Modifier.size(9.dp).border(1.dp, palette.textDim, RoundedCornerShape(2.dp)),
                    )
                }
            }
            Text(
                item.label,
                color = if (item.icon == ContextActionIcon.DELETE) palette.changed else palette.text,
                fontSize = Type.header,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 9.dp),
            )
        }
    }
}
