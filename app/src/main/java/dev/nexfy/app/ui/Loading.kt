package dev.nexfy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nexfy.app.ui.design.PillButton

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

/** Why a load failed, with a retry button. */
@Composable
fun LoadError(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message ?: "Algo deu errado",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PillButton("Tentar de novo", onRetry, filled = false)
    }
}
