package dev.nexfy.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import uniffi.nexfy_core.AudioPulse

/**
 * The cover's colours lit around it in the full player, breathing with the
 * music ([pulse]). Drawn on the phone; the window has none yet.
 */
@Composable
expect fun Ambilight(
    coverUrl: String?,
    playing: Boolean,
    pulse: () -> AudioPulse,
    cover: Rect,
    modifier: Modifier = Modifier,
    recede: () -> Float = { 1f },
)
