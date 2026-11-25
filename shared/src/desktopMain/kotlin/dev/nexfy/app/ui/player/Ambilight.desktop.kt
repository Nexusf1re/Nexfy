package dev.nexfy.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import uniffi.nexfy_core.AudioPulse

/** The window has no ambient light around the cover yet: the phone's draws on its own surface. */
@Composable
actual fun Ambilight(
    coverUrl: String?,
    playing: Boolean,
    pulse: () -> AudioPulse,
    cover: Rect,
    modifier: Modifier,
    recede: () -> Float,
) = Unit
