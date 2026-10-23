package dev.nexfy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nexfy.app.Connectivity
import dev.nexfy.app.ui.design.PillButton

/** A value loaded from the core, with its error and loading flags. */
data class Loaded<T>(val value: T? = null, val error: String? = null, val loading: Boolean = true)

class LoadHandle<T>(val state: Loaded<T>, val refresh: () -> Unit)

/** Last value of every load, so a screen that comes back shows it at once. */
private val lastValues = java.util.concurrent.ConcurrentHashMap<String, Any>()

/**
 * Run [load] when [keys] change. The first load may come from the core's
 * cache; [LoadHandle.refresh] reloads from the network. A screen shown
 * again starts from the last value while it reloads. A change of [reloadOn]
 * loads again too, keeping the value shown meanwhile. One that failed
 * without a network loads again by itself when the network returns.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberLoaded(vararg keys: Any?, reloadOn: Any? = null, load: suspend (refresh: Boolean) -> T): LoadHandle<T> {
    val memoryKey = keys.joinToString("|")
    var state by remember(*keys) { mutableStateOf(Loaded(value = lastValues[memoryKey] as T?)) }
    var refreshes by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, refreshes, reloadOn) {
        state = state.copy(loading = true, error = null)
        state = try {
            Loaded(load(refreshes > 0).also { value -> value?.let { lastValues[memoryKey] = it } }, null, false)
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            val offline = !Connectivity.online.value || message.contains("sem conexão")
            state.copy(error = if (offline) OFFLINE else message, loading = false)
        }
    }
    val online by Connectivity.online.collectAsState()
    LaunchedEffect(online) {
        if (online && state.error != null) refreshes++
    }
    return LoadHandle(state) { refreshes++ }
}

private const val OFFLINE = "Sem conexão. Carrega sozinho quando a internet voltar."

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
