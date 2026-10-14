package dev.nexfy.app.ui.screens

import dev.nexfy.app.ui.design.PillSelector
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.PillButton
import dev.nexfy.app.ui.design.RoundIconButton
import dev.nexfy.app.ui.theme.accentColor
import kotlinx.coroutines.launch
import uniffi.nexfy_core.AudioQuality
import uniffi.nexfy_core.WebApp
import uniffi.nexfy_core.WebStatus

@Composable
fun SettingsScreen(actions: AppActions, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val core = actions.core
    var user by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebStatus?>(null) }
    var clientId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        user = runCatching { core.restoreSession() }.getOrNull()
        web = core.webStatus().also { if (clientId.isEmpty()) clientId = it.personalClientId.orEmpty() }
    }

    /** Run a browser sign-in and report how it went. */
    fun signIn(label: String, begin: suspend () -> String, finish: suspend () -> Unit) {
        scope.launch {
            message = try {
                actions.openBrowser(begin())
                message = "Aguardando o Spotify no navegador…"
                finish()
                "$label conectado"
            } catch (e: Exception) {
                e.message ?: "Erro no login"
            }
            reload++
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(bottom = LocalBottomSpace.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", { actions.back() }, container = c.background)
        }
        Text("Ajustes", fontSize = 34.sp, style = MaterialTheme.typography.headlineLarge)
        message?.let { Text(it, color = accentColor()) }

        Section("Reprodução", "Toca as músicas neste celular. Precisa de Spotify Premium.") {
            StatusRow(if (user != null) "Conectado como $user" else "Não conectado", user != null)
            if (user == null) {
                PillButton("Entrar com Spotify", { signIn("Reprodução", { core.beginLogin() }, { core.finishLogin() }) })
            }
        }

        val status = web
        Section("App pessoal", "Tua própria cota da Web API: biblioteca e busca mais rápidas.") {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it.trim() },
                label = { Text("Client ID") },
                supportingText = { Text("Redirect URI: http://127.0.0.1:8989/login") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.primary, unfocusedBorderColor = c.outline, focusedLabelColor = c.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Salvar", {
                    scope.launch {
                        core.setPersonalClientId(clientId.ifBlank { null })
                        message = "Client ID salvo"
                        reload++
                    }
                }, filled = false)
                AccountButton(
                    signedIn = status?.personalSignedIn == true,
                    enabled = status?.personalClientId != null,
                    onSignIn = { signIn("App pessoal", { core.beginWebLogin(WebApp.PERSONAL) }, { core.finishWebLogin(WebApp.PERSONAL) }) },
                    onSignOut = { scope.launch { core.webSignOut(WebApp.PERSONAL); reload++ } },
                )
            }
        }

        Section("App compartilhado", "Playlists da própria Spotify (Daily Mix, editoriais) e busca de playlists.") {
            StatusRow(if (status?.sharedSignedIn == true) "Conectado" else "Não conectado", status?.sharedSignedIn == true)
            AccountButton(
                signedIn = status?.sharedSignedIn == true,
                enabled = true,
                onSignIn = { signIn("App compartilhado", { core.beginWebLogin(WebApp.SHARED) }, { core.finishWebLogin(WebApp.SHARED) }) },
                onSignOut = { scope.launch { core.webSignOut(WebApp.SHARED); reload++ } },
            )
        }

        SoundSection(actions)

        AppearanceSection(actions)

        StorageSection(actions)
    }
}

/** Stream quality and volume normalisation, for what plays on this phone. */
@Composable
private fun SoundSection(actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val core = actions.core
    var sound by remember { mutableStateOf(core.soundSettings()) }
    Section("Som", "Como as músicas tocam neste celular.") {
        Text("Qualidade de áudio", fontSize = 14.sp)
        PillSelector(listOf(AudioQuality.NORMAL, AudioQuality.HIGH), sound.quality, {
            if (it == AudioQuality.NORMAL) "Normal · 160 kbps" else "Alta · 320 kbps"
        }, {
            core.setAudioQuality(it)
            sound = core.soundSettings()
        }, Modifier.fillMaxWidth())
        if (sound.quality != sound.activeQuality) {
            Text("A nova qualidade vale na próxima vez que o app abrir.", color = c.onSurfaceVariant, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Normalizar volume", fontSize = 14.sp)
                Text("Deixa as músicas num volume parecido.", color = c.onSurfaceVariant, fontSize = 13.sp)
            }
            Switch(sound.normalize, {
                core.setNormalize(it)
                sound = core.soundSettings()
            })
        }
    }
}

/** The accent that follows the playing song's cover. */
@Composable
private fun AppearanceSection(actions: AppActions) {
    val library = actions.app.library
    val coverAccent by library.coverAccent.collectAsState()
    Section("Aparência", "Os destaques do app pegam a cor da capa da música que está tocando.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cor da música", Modifier.weight(1f), fontSize = 14.sp)
            Switch(coverAccent, library::setCoverAccent)
        }
    }
}

@Composable
private fun Section(title: String, description: String, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceVariant).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = c.onSurfaceVariant, fontSize = 13.sp)
        content()
    }
}

@Composable
private fun StatusRow(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier.padding(end = 8.dp).clip(CircleShape)
                .background(if (ok) accentColor() else MaterialTheme.colorScheme.outline)
                .padding(4.dp),
        )
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun AccountButton(signedIn: Boolean, enabled: Boolean, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    if (signedIn) PillButton("Sair", onSignOut, filled = false) else PillButton("Entrar", onSignIn, enabled = enabled)
}

private val Limits = listOf(1L, 2L, 5L, 10L).map { it * 1024 * 1024 * 1024 }

private fun gb(bytes: Long) = "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)).replace('.', ',')

/** The audio cache: what it holds, its size, queue prefetch and clearing it. */
@Composable
private fun StorageSection(actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val core = actions.core
    val library = actions.app.library
    var info by remember { mutableStateOf(core.cacheInfo()) }
    var chosen by remember { mutableStateOf(info.limitBytes.toLong()) }
    var confirmClear by remember { mutableStateOf(false) }
    val prefetch by library.prefetchQueue.collectAsState()
    // Downloads fill it in the background: keep the numbers live.
    LaunchedEffect(Unit) {
        while (true) {
            info = core.cacheInfo()
            delay(3_000)
        }
    }
    Section(
        "Músicas no aparelho",
        "No Wi-Fi, o Nexfy baixa as próximas músicas e as playlists que tu mantiveres no aparelho. Elas começam na hora e tocam com internet fraca; ainda precisam de conexão para tocar.",
    ) {
        val used = info.usedBytes.toLong()
        Text("${gb(used)} de ${gb(chosen)}", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.outline)) {
            Box(
                Modifier.fillMaxWidth((used.toFloat() / chosen).coerceIn(0f, 1f)).fillMaxHeight()
                    .clip(CircleShape).background(accentColor()),
            )
        }
        PillSelector(Limits, chosen, { gb(it).removeSuffix(",0 GB") + " GB" }, {
            chosen = it
            core.setCacheLimit(it.toULong())
        }, Modifier.fillMaxWidth())
        if (chosen != info.limitBytes.toLong()) {
            Text("O novo tamanho vale na próxima vez que o app abrir.", color = c.onSurfaceVariant, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Baixar as próximas da fila no Wi-Fi", Modifier.weight(1f), fontSize = 14.sp)
            Switch(prefetch, library::setPrefetchQueue)
        }
        PillButton("Apagar músicas baixadas", { confirmClear = true }, filled = false)
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Apagar músicas baixadas?") },
            text = { Text("Libera ${gb(info.usedBytes.toLong())}. As playlists mantidas no aparelho voltam a baixar no Wi-Fi.") },
            confirmButton = {
                TextButton({
                    core.clearAudioCache()
                    info = core.cacheInfo()
                    confirmClear = false
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancelar") } },
        )
    }
}
