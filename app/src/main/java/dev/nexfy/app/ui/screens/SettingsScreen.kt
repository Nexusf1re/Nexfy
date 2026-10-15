package dev.nexfy.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.LoginService
import dev.nexfy.app.LyricsSize
import dev.nexfy.app.ThemeMode
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.springPress
import dev.nexfy.app.ui.design.PillButton
import dev.nexfy.app.ui.design.PillSelector
import dev.nexfy.app.ui.design.RoundIconButton
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.systemAnimationsOff
import dev.nexfy.app.ui.theme.accentColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.nexfy_core.AudioQuality
import uniffi.nexfy_core.WebApp
import uniffi.nexfy_core.WebStatus

@Composable
fun SettingsScreen(actions: AppActions, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    var message by remember { mutableStateOf<String?>(null) }

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

        EqualizerCard(actions)
        AccountSection(actions) { message = it }
        SoundSection(actions)
        AppearanceSection(actions)
        BehaviourSection(actions)
        StorageSection(actions)
        AboutSection()
    }
}

/** Spotify sign-in for playback, and the two Web API apps. [onMessage] reports how a sign-in went. */
@Composable
private fun AccountSection(actions: AppActions, onMessage: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val core = actions.core
    var user by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebStatus?>(null) }
    var clientId by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        user = runCatching { core.restoreSession() }.getOrNull()
        web = core.webStatus().also { if (clientId.isEmpty()) clientId = it.personalClientId.orEmpty() }
    }

    /** Run a browser sign-in and report how it went. */
    fun signIn(label: String, begin: suspend () -> String, finish: suspend () -> Unit) {
        scope.launch {
            onMessage(
                try {
                    val url = begin()
                    LoginService.during(context) {
                        actions.openBrowser(url)
                        onMessage("Aguardando o Spotify no navegador…")
                        finish()
                    }
                    "$label conectado"
                } catch (e: Exception) {
                    e.message ?: "Erro no login"
                },
            )
            reload++
        }
    }

    val status = web
    Section("Conta", "Spotify para tocar; a Web API para biblioteca e busca.") {
        Group("Spotify", "Toca as músicas neste celular. Precisa de Spotify Premium.") {
            StatusRow(if (user != null) "Conectado como $user" else "Não conectado", user != null)
            if (user == null) {
                PillButton("Entrar com Spotify", { signIn("Spotify", { core.beginLogin() }, { core.finishLogin() }) })
            }
        }
        HorizontalDivider(color = c.outlineVariant)
        Group("App pessoal", "Tua própria cota da Web API: biblioteca e busca mais rápidas.") {
            // How to make the app: the dashboard link, the redirect to copy and common snags.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accentColor().copy(.12f))
                    .clickable { actions.open(Route.Guide) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, tint = accentColor(), modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Como criar o seu app", color = c.onSurface, fontSize = 14.sp)
                    Text("Passo a passo com o link e dúvidas comuns", color = c.onSurfaceVariant, fontSize = 12.sp)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.onSurfaceVariant)
            }
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
                        onMessage("Client ID salvo")
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
        HorizontalDivider(color = c.outlineVariant)
        Group("App compartilhado", "Playlists da própria Spotify (Daily Mix, editoriais) e busca de playlists.") {
            StatusRow(if (status?.sharedSignedIn == true) "Conectado" else "Não conectado", status?.sharedSignedIn == true)
            AccountButton(
                signedIn = status?.sharedSignedIn == true,
                enabled = true,
                onSignIn = { signIn("App compartilhado", { core.beginWebLogin(WebApp.SHARED) }, { core.finishWebLogin(WebApp.SHARED) }) },
                onSignOut = { scope.launch { core.webSignOut(WebApp.SHARED); reload++ } },
            )
        }
    }
}

/**
 * The equalizer, up top where it is seen: its live curve, what it is set
 * to and a switch. Tapping the card opens the full screen.
 */
@Composable
private fun EqualizerCard(actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val core = actions.core
    val accent = accentColor()
    val haptics = rememberHaptics()
    // Read again on each visit: the equalizer screen replaces this one.
    var eq by remember { mutableStateOf(core.equalizer()) }
    val playback by actions.app.bridge.local.collectAsState()
    val glow by animateFloatAsState(if (eq.enabled) 1f else 0f, Motion.standard(), label = "glow")
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().springPress(source).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(lerp(c.surfaceVariant, accent, .3f * glow), c.surfaceVariant)))
            .clickable(source, null) { actions.open(Route.Equalizer) }
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Equalizador", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (!eq.enabled) "Desligado" else eq.preset ?: "Personalizado",
                    color = lerp(c.onSurfaceVariant, accent, glow), fontSize = 14.sp,
                )
            }
            Switch(
                eq.enabled,
                onCheckedChange = { on ->
                    haptics.tick()
                    core.setEqualizer(on, eq.preampDb, eq.gainsDb)
                    eq = core.equalizer()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = c.background),
            )
        }
        EqualizerPreview(eq.gainsDb, eq.enabled, playback.playing, Modifier.fillMaxWidth().height(104.dp).padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("10 faixas, presets e os seus ajustes", color = c.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.onSurfaceVariant)
        }
    }
}

/** Stream quality and volume normalisation, for what plays on this phone. */
@Composable
private fun SoundSection(actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val core = actions.core
    var sound by remember { mutableStateOf(core.soundSettings()) }
    Section("Som", "Como as músicas tocam neste celular.") {
        Choice("Qualidade de áudio", listOf(AudioQuality.NORMAL, AudioQuality.HIGH), sound.quality, {
            if (it == AudioQuality.NORMAL) "Normal · 160 kbps" else "Alta · 320 kbps"
        }) {
            core.setAudioQuality(it)
            sound = core.soundSettings()
        }
        if (sound.quality != sound.activeQuality) {
            Text("A nova qualidade vale na próxima vez que o app abrir.", color = c.onSurfaceVariant, fontSize = 13.sp)
        }
        Toggle("Normalizar volume", "Deixa as músicas num volume parecido.", sound.normalize, {
            core.setNormalize(it)
            sound = core.soundSettings()
        })
    }
}

/** Theme, the accent, the backdrop, motion and lyrics size. All apply at once. */
@Composable
private fun AppearanceSection(actions: AppActions) {
    val library = actions.app.library
    val context = LocalContext.current
    val theme by library.themeMode.collectAsState()
    val coverAccent by library.coverAccent.collectAsState()
    val backdrop by library.animatedBackdrop.collectAsState()
    val reduce by library.reduceMotion.collectAsState()
    val lyrics by library.lyricsSize.collectAsState()
    // With the system's animations removed, motion is reduced either way.
    val systemStill = remember { systemAnimationsOff(context) }
    Section("Aparência", "Tema, cores e movimento.") {
        Choice("Tema", ThemeMode.entries, theme, ThemeMode::label, library::setThemeMode)
        Toggle("Cor da música", "Os destaques pegam a cor da capa que está tocando.", coverAccent, library::setCoverAccent)
        Toggle("Fundo animado", "As cores da capa se movem no player e nas páginas. Desligado, poupa bateria.", backdrop, library::setAnimatedBackdrop)
        Toggle(
            "Reduzir animações",
            if (systemStill) "As animações do sistema estão desligadas." else "Transições mais curtas e simples.",
            reduce || systemStill,
            enabled = !systemStill,
            onChange = library::setReduceMotion,
        )
        Choice("Tamanho da letra", LyricsSize.entries, lyrics, LyricsSize::label, library::setLyricsSize)
    }
}

/** How the app behaves around playback and touch. */
@Composable
private fun BehaviourSection(actions: AppActions) {
    val library = actions.app.library
    val addedBy by library.showAddedBy.collectAsState()
    val haptics by library.haptics.collectAsState()
    val swipeBack by library.swipeBack.collectAsState()
    Section("Reprodução e comportamento", "O que as listas mostram e como o app responde ao toque.") {
        Toggle("Mostrar quem adicionou", "A foto de quem pôs cada música, em playlists compartilhadas e na fila.", addedBy, library::setShowAddedBy)
        Toggle("Vibração", "Uma vibração leve nos toques e gestos.", haptics, library::setHaptics)
        Toggle("Voltar arrastando", "Arraste uma página para o lado, de qualquer ponto, para voltar.", swipeBack, library::setSwipeBack)
    }
}

/** App name and version. */
@Composable
private fun AboutSection() {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            ?.let { "Versão ${it.versionName} (${it.longVersionCode})" }
    }
    Section("Sobre", "Um app pessoal para ouvir o Spotify no Android.") {
        Column {
            Text("Nexfy", style = MaterialTheme.typography.titleMedium)
            version?.let { Text(it, color = c.onSurfaceVariant, fontSize = 13.sp) }
        }
    }
}

@Composable
internal fun Section(title: String, description: String, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceVariant).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = c.onSurfaceVariant, fontSize = 13.sp)
        }
        content()
    }
}

/** A titled part of a section, for sections that hold several things (the accounts). */
@Composable
private fun Group(title: String, description: String, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(title, fontSize = 14.sp)
            Text(description, color = c.onSurfaceVariant, fontSize = 13.sp)
        }
        content()
    }
}

/** An on/off setting: the whole row toggles it. */
@Composable
private fun Toggle(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .toggleable(checked, enabled = enabled, role = Role.Switch) {
                haptics.tick()
                onChange(it)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp)
            description?.let { Text(it, color = c.onSurfaceVariant, fontSize = 13.sp) }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A choice among a few options, as a segmented selector under its title. */
@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 14.sp)
        PillSelector(options, selected, label, onSelect, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun StatusRow(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
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
        "Armazenamento",
        "As próximas da fila e as playlists mantidas no aparelho, baixadas no Wi-Fi. Começam na hora; ainda precisam de internet.",
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
        Toggle("Baixar as próximas da fila no Wi-Fi", null, prefetch, library::setPrefetchQueue)
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
