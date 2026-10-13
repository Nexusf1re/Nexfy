package dev.sonata.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sonata.app.ui.theme.SonataTheme
import dev.sonata.core.NativeInit
import kotlinx.coroutines.launch
import uniffi.sonata_core.SonataCore

/**
 * Phase 0 spike: sign in through the browser and play one track with the
 * Rust core. Replaced by the real UI in phase 3.
 */
class MainActivity : ComponentActivity() {
    private lateinit var core: SonataCore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeInit.init(applicationContext)
        core = SonataCore(filesDir.resolve("spotify").path)

        enableEdgeToEdge()
        setContent {
            SonataTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SpikeScreen(
                        core = core,
                        openBrowser = { url ->
                            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpikeScreen(core: SonataCore, openBrowser: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Verificando login salvo…") }
    var user by remember { mutableStateOf<String?>(null) }
    var track by remember { mutableStateOf("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT") }

    LaunchedEffect(Unit) {
        status = try {
            user = core.restoreSession()
            if (user == null) "Não conectado" else "Conectado"
        } catch (e: Exception) {
            "Erro ao restaurar: ${e.message}"
        }
    }

    fun run(label: String, action: () -> Unit) {
        status = try {
            action(); label
        } catch (e: Exception) {
            "Erro: ${e.message}"
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sonata · Fase 0", style = MaterialTheme.typography.headlineMedium)
        Text(if (user != null) "Conta: $user" else "Sem conta conectada")
        Text(status, style = MaterialTheme.typography.bodyMedium)

        Button(
            onClick = {
                scope.launch {
                    try {
                        openBrowser(core.beginLogin())
                        status = "Aguardando o Spotify no navegador…"
                        user = core.finishLogin()
                        status = "Conectado"
                    } catch (e: Exception) {
                        status = "Erro no login: ${e.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (user == null) "Entrar com Spotify" else "Entrar de novo") }

        OutlinedTextField(
            value = track,
            onValueChange = { track = it },
            label = { Text("Link ou URI da faixa") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { run("Tocando") { core.playTrack(track) } }) { Text("Tocar") }
            OutlinedButton(onClick = { run("Pausado") { core.pause() } }) { Text("Pausar") }
            OutlinedButton(onClick = { run("Tocando") { core.resume() } }) { Text("Continuar") }
        }
    }
}
