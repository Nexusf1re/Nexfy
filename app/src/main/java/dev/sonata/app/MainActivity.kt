package dev.sonata.app

import android.Manifest
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.sonata.app.playback.PlaybackService
import dev.sonata.app.playback.PlaybackSnapshot
import dev.sonata.app.ui.theme.SonataTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.sonata_core.RepeatMode

/**
 * Phase 1 test screen: sign in, queue links, and drive playback through the
 * media session like the notification does. Replaced by the real UI in
 * phase 3.
 */
class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val app = application as SonataApp

        enableEdgeToEdge()
        setContent {
            SonataTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TestScreen(
                        app = app,
                        controller = controller,
                        openBrowser = { url ->
                            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }
}

@Composable
private fun TestScreen(
    app: SonataApp,
    controller: MediaController?,
    openBrowser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snapshot by app.bridge.state.collectAsState()
    var user by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Verificando login salvo…") }
    var links by remember {
        mutableStateOf(
            "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT\n" +
                "https://open.spotify.com/track/0O5DPkLlre5yNwH6Sqm2dD",
        )
    }

    LaunchedEffect(Unit) {
        status = try {
            user = app.core.restoreSession()
            if (user == null) "Não conectado" else "Conectado"
        } catch (e: Exception) {
            "Erro ao restaurar: ${e.message}"
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sonata · Fase 1", style = MaterialTheme.typography.headlineMedium)
        Text(if (user != null) "Conta: $user · $status" else status)

        if (user == null) {
            Button(
                onClick = {
                    scope.launch {
                        try {
                            openBrowser(app.core.beginLogin())
                            status = "Aguardando o Spotify no navegador…"
                            user = app.core.finishLogin()
                            status = "Conectado"
                        } catch (e: Exception) {
                            status = "Erro no login: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Entrar com Spotify") }
        }

        NowPlaying(snapshot, controller)

        OutlinedTextField(
            value = links,
            onValueChange = { links = it },
            label = { Text("Links das faixas (um por linha)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = controller != null && user != null,
            onClick = {
                val items = links.split('\n', ' ', ',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map { MediaItem.Builder().setMediaId(it).build() }
                controller?.run {
                    setMediaItems(items)
                    prepare()
                    play()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Tocar fila") }

        snapshot.error?.let { Text("Erro: $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun NowPlaying(snapshot: PlaybackSnapshot, controller: MediaController?) {
    val track = snapshot.track
    val duration = track?.durationMs?.toLong() ?: 0L

    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(snapshot) {
        while (true) {
            position = snapshot.currentPositionMs().coerceIn(0, duration.coerceAtLeast(0))
            if (!snapshot.playing) break
            delay(500)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(track?.title ?: "Nada tocando", style = MaterialTheme.typography.titleLarge)
        if (track != null) Text("${track.artists} · ${track.album}")
        Text(
            when {
                snapshot.loading -> "Carregando…"
                snapshot.ended -> "Fila terminada"
                snapshot.playing -> "Tocando"
                track != null -> "Pausado"
                else -> ""
            } + "  ·  faixa ${snapshot.queue.position.toInt() + 1} de ${snapshot.queue.uris.size}",
            style = MaterialTheme.typography.bodySmall,
        )

        Slider(
            value = if (dragging) dragValue else position.toFloat(),
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                dragging = false
                controller?.seekTo(dragValue.toLong())
            },
            enabled = track != null,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (dragging) dragValue.toLong() else position))
            Text(formatTime(duration))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = {
                controller?.shuffleModeEnabled = !snapshot.queue.shuffle
            }) { Text(if (snapshot.queue.shuffle) "Aleat. ✓" else "Aleat.") }
            OutlinedButton(onClick = { controller?.seekToPrevious() }) { Text("⏮") }
            FilledTonalButton(onClick = {
                controller?.run { if (playWhenReady && !snapshot.ended) pause() else play() }
            }) { Text(if (snapshot.playing) "⏸" else "▶") }
            OutlinedButton(onClick = { controller?.seekToNext() }) { Text("⏭") }
            OutlinedButton(onClick = {
                controller?.repeatMode = when (snapshot.queue.repeat) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_ALL
                    RepeatMode.ALL -> Player.REPEAT_MODE_ONE
                    RepeatMode.ONE -> Player.REPEAT_MODE_OFF
                }
            }) {
                Text(
                    when (snapshot.queue.repeat) {
                        RepeatMode.OFF -> "Rep."
                        RepeatMode.ALL -> "Rep. ✓"
                        RepeatMode.ONE -> "Rep. 1"
                    },
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
