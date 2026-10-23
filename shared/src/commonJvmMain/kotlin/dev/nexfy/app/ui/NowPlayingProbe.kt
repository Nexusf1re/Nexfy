package dev.nexfy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import dev.nexfy.app.ui.design.GlassBackdrop
import dev.nexfy.app.ui.design.GlassLevel
import dev.nexfy.app.ui.design.LocalHaze
import dev.nexfy.app.ui.design.accent
import dev.nexfy.app.ui.design.coverPixels
import dev.nexfy.app.ui.design.glassSource
import dev.nexfy.app.ui.design.liquidGlass
import dev.nexfy.app.ui.design.quantize
import kotlinx.coroutines.flow.MutableStateFlow
import uniffi.nexfy_core.CoreEvent
import uniffi.nexfy_core.CoreListener
import uniffi.nexfy_core.NexfyCore
import uniffi.nexfy_core.TrackInfo

/**
 * What plays on this computer, behind a pane of the app's glass. Stands in
 * for the real player while the screens move over: it exercises the whole
 * stack, from the Rust core's events up through the lens shader.
 */
@Composable
fun NowPlayingProbe(core: NexfyCore, signedInAs: String?) {
    // The core calls its listener from its own threads, never this one, so
    // the events land in a flow rather than straight into composition.
    val tracks = remember { MutableStateFlow<TrackInfo?>(null) }
    val states = remember { MutableStateFlow("waiting for a device to play here") }

    DisposableEffect(core) {
        core.setListener(object : CoreListener {
            override fun onEvent(event: CoreEvent) {
                when (event) {
                    is CoreEvent.TrackChanged -> tracks.value = event.track
                    is CoreEvent.Playing -> states.value = "playing"
                    is CoreEvent.Paused -> states.value = "paused"
                    is CoreEvent.Loading -> states.value = "loading"
                    is CoreEvent.Stopped -> states.value = "stopped"
                    is CoreEvent.Error -> states.value = "error: ${event.message}"
                    else -> Unit
                }
            }
        })
        onDispose { }
    }

    val track by tracks.collectAsState()
    val state by states.collectAsState()
    val platform = LocalPlatformContext.current
    var accent by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(track?.coverUrl) {
        accent = track?.coverUrl?.let { url ->
            coverPixels(platform, url, 96)?.let { quantize(it, 16).accent() }
        }
    }

    val backdrop = remember { GlassBackdrop() }
    CompositionLocalProvider(LocalHaze provides backdrop) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0B0D))) {
            // The backdrop the glass refracts: the cover, filling the window.
            track?.coverUrl?.let { cover ->
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().glassSource(backdrop),
                )
            }

            Column(
                Modifier.align(Alignment.Center).padding(48.dp)
                    .liquidGlass(RoundedCornerShape(28.dp), dark = true, level = GlassLevel.Strong)
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    signedInAs?.let { "signed in as $it" } ?: "no cached session",
                    style = MaterialTheme.typography.labelMedium,
                )

                track?.let { playing ->
                    AsyncImage(
                        model = playing.coverUrl,
                        contentDescription = playing.album,
                        modifier = Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Text(playing.title, style = MaterialTheme.typography.headlineMedium)
                    Text(playing.artists, style = MaterialTheme.typography.bodyLarge)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                                .background(accent ?: MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Text(
                            accent?.let { "accent from the cover" } ?: "no accent: a grey cover",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Text(state, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
