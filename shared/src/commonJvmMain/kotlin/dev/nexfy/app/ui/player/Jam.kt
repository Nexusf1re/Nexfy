package dev.nexfy.app.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import dev.nexfy.app.LocalServices
import dev.nexfy.app.NexfyServices
import dev.nexfy.app.ui.copyText
import dev.nexfy.app.ui.design.GlassSheet
import dev.nexfy.app.ui.design.Bone
import dev.nexfy.app.ui.design.PillButton
import dev.nexfy.app.ui.design.TextBone
import dev.nexfy.app.ui.design.UserAvatar
import dev.nexfy.app.ui.design.boneWidth
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.pasteText
import dev.nexfy.app.ui.shareText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.nexfy_core.JamSession
import uniffi.nexfy_core.UserFace

/**
 * The Jam this account is in, as last read. Song menus offer "Adicionar à
 * Jam" while it is set; the Jam sheet keeps it current.
 */
object JamState {
    val current = mutableStateOf<JamSession?>(null)

    /** Queue a song in the Jam, wherever it plays. */
    fun queue(services: NexfyServices, uri: String, title: String) {
        services.commands.run { services.core.jamQueue(uri) }
        services.toast("Adicionada à Jam: $title")
    }
}

/** Jam failures read as one calm line; the details are in the core's log. */
private fun unavailable(e: Exception) =
    "Jam não disponível neste app por enquanto." + (e.message?.let { "\n$it" } ?: "")

/**
 * Spotify's Jam: start one on this phone and share its link, or join a
 * friend's from a pasted link. Inside a Jam: who is in it, the link, and
 * leaving (ending it, for the host).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamSheet(onDismiss: () -> Unit) {
    val context = LocalPlatformContext.current
    val core = LocalServices.current.core
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var jam by JamState.current
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            jam = withContext(Dispatchers.IO) { core.jamCurrent() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = unavailable(e)
        }
        loading = false
    }

    /** Run a Jam call, keeping the sheet's state in step with its answer. */
    fun act(call: suspend () -> JamSession?) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                jam = withContext(Dispatchers.IO) { call() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = unavailable(e)
            }
            busy = false
        }
    }

    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
            Text("Jam", style = MaterialTheme.typography.titleLarge)
            Crossfade(if (loading) null else jam, animationSpec = tween(dev.nexfy.app.ui.design.Motion.Short), label = "jam") { current ->
                when {
                    loading -> Column(Modifier.padding(top = 16.dp)) { repeat(2) { MemberSkeleton(it) } }
                    current != null -> Column(Modifier.padding(top = 4.dp)) {
                        // Where the Jam plays, by the host device's name.
                        val where by androidx.compose.runtime.produceState<String?>(null, current.hostDeviceId) {
                            value = runCatching { withContext(Dispatchers.IO) { core.devices() } }.getOrNull()
                                ?.firstOrNull { it.id == current.hostDeviceId }
                                ?.let { if (it.isSelf) "neste celular" else "em ${it.name}" }
                        }
                        Text(
                            if (current.isHost) "Tocando ${where ?: "na tua conta"}. Quem entrar pode pôr músicas na fila."
                            else "Tu estás na Jam. As músicas que tu puseres na fila tocam para todo mundo.",
                            color = c.onSurfaceVariant, fontSize = 14.sp,
                        )
                        Spacer(Modifier.size(12.dp))
                        current.members.forEach { m ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                UserAvatar(UserFace(m.id, m.name, m.imageUrl), Modifier.size(36.dp), description = m.name)
                                Spacer(Modifier.width(12.dp))
                                Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val role = when {
                                    m.isHost -> "Anfitrião"
                                    m.isMe -> "Você"
                                    else -> null
                                }
                                role?.let { Text(it, color = c.onSurfaceVariant, fontSize = 13.sp) }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("Convidar", {
                                haptics.tick()
                                val invite = "Entra na minha Jam no Spotify\n${current.shareUrl}"
                                if (!shareText(context, invite)) copyText(context, invite)
                            }, Modifier.weight(1f), icon = Icons.Rounded.Share)
                            PillButton(
                                if (current.isHost) "Encerrar" else "Sair",
                                { haptics.press(); act { core.jamLeave(); null } },
                                Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.Logout, filled = false, enabled = !busy,
                            )
                        }
                    }
                    else -> Column(Modifier.padding(top = 4.dp)) {
                        Text(
                            "Ouça junto com amigos: todo mundo põe músicas na mesma fila.",
                            color = c.onSurfaceVariant, fontSize = 14.sp,
                        )
                        PillButton(
                            "Começar uma Jam",
                            { haptics.tick(); act { core.jamStart() } },
                            Modifier.fillMaxWidth().padding(top = 16.dp), icon = Icons.Rounded.Groups, enabled = !busy,
                        )
                        OutlinedTextField(
                            link, { link = it },
                            Modifier.fillMaxWidth().padding(top = 20.dp),
                            label = { Text("Link de uma Jam") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton({
                                    pasteText(context)?.let { link = it.trim() }
                                }) { Icon(Icons.Rounded.ContentPaste, "Colar") }
                            },
                        )
                        PillButton(
                            "Entrar",
                            { haptics.tick(); act { core.jamJoin(link.trim()) } },
                            Modifier.fillMaxWidth().padding(top = 10.dp), filled = false, enabled = !busy && link.isNotBlank(),
                        )
                    }
                }
            }
            error?.let {
                Text(it, Modifier.padding(top = 14.dp), color = c.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MemberSkeleton(seed: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Bone(Modifier.size(36.dp), CircleShape)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { TextBone(boneWidth(seed) * .6f, 22.dp, 13.dp) }
    }
}
