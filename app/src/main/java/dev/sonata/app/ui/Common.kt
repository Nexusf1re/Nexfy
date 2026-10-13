package dev.sonata.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import uniffi.sonata_core.Track

/** A value loaded from the core, with its error and loading flags. */
data class Loaded<T>(val value: T? = null, val error: String? = null, val loading: Boolean = true)

class LoadHandle<T>(val state: Loaded<T>, val refresh: () -> Unit)

/**
 * Run [load] when [keys] change. The first load may come from the core's
 * cache; [LoadHandle.refresh] reloads from the network.
 */
@Composable
fun <T> rememberLoaded(vararg keys: Any?, load: suspend (refresh: Boolean) -> T): LoadHandle<T> {
    var state by remember(*keys) { mutableStateOf(Loaded<T>()) }
    var refreshes by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, refreshes) {
        state = state.copy(loading = true, error = null)
        state = try {
            Loaded(load(refreshes > 0), null, false)
        } catch (e: Exception) {
            state.copy(error = e.message ?: e.toString(), loading = false)
        }
    }
    return LoadHandle(state) { refreshes++ }
}

/** Spinner, error with retry, or [content] once a value exists. */
@Composable
fun <T> LoadedContent(handle: LoadHandle<T>, content: @Composable (T) -> Unit) {
    val state = handle.state
    when {
        state.value != null -> content(state.value)
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.error ?: "Erro", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = handle.refresh) { Text("Tentar de novo") }
        }
    }
}

@Composable
fun Cover(url: String?, size: Dp, round: Boolean = false, modifier: Modifier = Modifier) {
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun MediaRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
    round: Boolean = false,
    dimmed: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (dimmed) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(imageUrl, 52.dp, round)
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TrackRow(track: Track, onClick: () -> Unit, showCover: Boolean = true) {
    MediaRow(
        title = (if (track.explicit) "🅴 " else "") + track.title,
        subtitle = if (showCover) "${track.artists} · ${track.album}" else track.artists,
        imageUrl = track.imageUrl.takeIf { showCover },
        onClick = onClick,
        dimmed = !track.playable,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

fun formatTime(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
