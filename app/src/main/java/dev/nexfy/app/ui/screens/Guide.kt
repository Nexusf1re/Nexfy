package dev.nexfy.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.PillButton
import dev.nexfy.app.ui.design.RoundIconButton
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.theme.accentColor

private const val DASHBOARD = "https://developer.spotify.com/dashboard"
private const val REDIRECT_URI = "http://127.0.0.1:8989/login"

/** Common snags, each answered in a line or two. */
private val questions = listOf(
    "A página fica carregando para sempre" to
        "O Android pausou o Nexfy enquanto o navegador estava na frente. Em Configurações do Android, deixe a bateria do Nexfy como \"Sem restrição\" e tente de novo.",
    "Aparece \"INVALID_CLIENT: Invalid redirect URI\"" to
        "O Redirect URI do app não bate. Precisa ser exatamente $REDIRECT_URI: sem https, sem localhost e sem barra no final. Depois de editar, toque em Save.",
    "Entrei, mas a biblioteca não carrega" to
        "Usando o app de outra pessoa? Ela precisa adicionar o seu email em User Management, no app dela. Com o seu próprio app isso não é preciso.",
    "Preciso de Spotify Premium?" to
        "Para tocar neste celular, sim. Para ver a biblioteca, buscar e controlar outros aparelhos, não.",
    "Por que criar um app?" to
        "Ele dá ao Nexfy uma cota de acesso só sua: biblioteca e busca respondem mais rápido e não disputam limite com ninguém.",
    "É seguro?" to
        "A senha fica no site do Spotify; o Nexfy só recebe uma permissão de acesso. Dá para revogar quando quiser em spotify.com/account/apps.",
)

/** A short, direct guide to the personal Web API app, with the dashboard a tap away. */
@Composable
fun GuideScreen(actions: AppActions, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    var connected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { connected = runCatching { actions.core.webStatus().personalSignedIn }.getOrNull() }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(bottom = LocalBottomSpace.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", { actions.back() }, container = c.background)
        }
        Text("Seu app no Spotify", fontSize = 34.sp, style = MaterialTheme.typography.headlineLarge)
        Text("Uns 3 minutos, uma vez só. Depois a biblioteca e a busca usam a sua própria cota.", color = c.onSurfaceVariant)

        Section("Passo a passo", "Faça no navegador do celular ou no computador.") {
            Step(1, "Abra o painel de desenvolvedor e entre com a sua conta Spotify.") {
                PillButton("Abrir Spotify Developer", { actions.openBrowser(DASHBOARD) }, icon = Icons.AutoMirrored.Rounded.OpenInNew)
            }
            Step(2, "Toque em Create app. Nome e descrição podem ser qualquer coisa.")
            Step(3, "Em Redirect URIs, cole este endereço e toque em Add:") {
                CopyField(REDIRECT_URI)
            }
            Step(4, "Em APIs used, marque Web API. Aceite os termos e toque em Save.")
            Step(5, "Abra Settings do app criado e copie o Client ID.")
            Step(6, "Volte aos Ajustes do Nexfy, cole em Client ID, toque em Salvar e depois em Entrar.")
        }

        Section("Dúvidas comuns", "Toque para ver a resposta.") {
            questions.forEachIndexed { i, (q, a) ->
                if (i > 0) HorizontalDivider(color = c.outlineVariant)
                Question(q, a)
            }
        }

        Section("Seu app pessoal", "Como está agora.") {
            when (connected) {
                true -> StatusRow("Conectado: tudo certo", true)
                false -> StatusRow("Ainda não conectado", false)
                null -> StatusRow("Verificando…", false)
            }
            if (connected == false) PillButton("Ir para a conta", { actions.back() }, filled = false)
        }
    }
}

/** One numbered step, with whatever it needs (a button, a field) under its text. */
@Composable
private fun Step(n: Int, text: String, extra: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(accentColor().copy(.18f)), contentAlignment = Alignment.Center) {
            Text("$n", color = accentColor(), fontSize = 13.sp, style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, color = c.onSurface, fontSize = 14.sp)
            extra?.invoke()
        }
    }
}

/** A value to paste elsewhere: the whole row copies it. */
@Composable
private fun CopyField(value: String) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var copied by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .clickable {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Redirect URI", value))
                haptics.tick()
                copied = true
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.onSurface)
        Text(if (copied) "Copiado" else "Copiar", color = accentColor(), fontSize = 13.sp)
        Icon(Icons.Rounded.ContentCopy, null, tint = accentColor(), modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

/** A question that opens to its answer. */
@Composable
private fun Question(question: String, answer: String) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.settle(300f), label = "chevron")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable { haptics.tick(); open = !open }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(question, Modifier.weight(1f), color = c.onSurface, fontSize = 14.sp)
            Icon(Icons.Rounded.ExpandMore, null, tint = c.onSurfaceVariant, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(answer, Modifier.padding(top = 6.dp, end = 24.dp), color = c.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}
