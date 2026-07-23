package com.github.dkwasniak.goldendiff.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos

/**
 * Removes a Compose window one frame after its native peer has been hidden.
 *
 * Disposing the window in the same state change that handles the close request can expose the
 * unpainted AWT surface for a frame on macOS, which reads as a bright or dark flash.
 */
@Composable
internal fun DeferredWindowCloseEffect(visible: Boolean, onClosed: () -> Unit) {
    val currentOnClosed = rememberUpdatedState(onClosed)
    LaunchedEffect(visible) {
        if (!visible) {
            withFrameNanos { }
            currentOnClosed.value()
        }
    }
}
