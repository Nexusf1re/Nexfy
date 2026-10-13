package dev.nexfy.app.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.playback.PlaybackSnapshot
import dev.nexfy.app.ui.design.Bone
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.TextBone
import dev.nexfy.app.ui.design.ThinSlider
import dev.nexfy.app.ui.design.boneWidth
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.theme.accentColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import uniffi.nexfy_core.Device

fun deviceIcon(kind: String): ImageVector = when (kind.lowercase()) {
    "computer" -> Icons.Rounded.Computer
    "smartphone", "tablet" -> Icons.Rounded.PhoneAndroid
    "tv", "stb", "castvideo" -> Icons.Rounded.Tv
    "speaker", "avr", "audiodongle", "castaudio" -> Icons.Rounded.Speaker
    "automobile" -> Icons.Rounded.DirectionsCar
    "gameconsole" -> Icons.Rounded.SportsEsports
    "smartwatch" -> Icons.Rounded.Watch
    else -> Icons.Rounded.Devices
}

private fun kindLabel(kind: String) = when (kind.lowercase()) {
    "computer" -> "Computador"
    "smartphone" -> "Celular"
    "tablet" -> "Tablet"
    "tv", "stb", "castvideo" -> "TV"
    "speaker", "avr", "audiodongle", "castaudio" -> "Caixa de som"
    "automobile" -> "Carro"
    "gameconsole" -> "Console"
    "smartwatch" -> "Relógio"
    else -> "Dispositivo"
}

/**
 * Spotify Connect: every device of the account, the one playing on top.
 * Tapping one moves playback there; the device playing elsewhere gets a
 * volume slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(s: PlaybackSnapshot, controls: PlayerControls, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val haptics = rememberHaptics()
    var devices by remember { mutableStateOf<List<Device>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Devices come and go (a PC closing Spotify): keep the list fresh while open.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                devices = controls.devices()
                    .sortedWith(compareByDescending<Device> { it.active }.thenByDescending { it.isSelf }.thenBy { it.name })
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (devices == null) error = e.message
            }
            delay(4_000)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Text("Tocar em", Modifier.padding(start = 4.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
            Crossfade(devices, animationSpec = tween(Motion.Short), label = "devices") { list ->
                when {
                    list == null && error != null -> Text(error.orEmpty(), Modifier.padding(16.dp), color = c.onSurfaceVariant)
                    list == null -> Column { repeat(3) { DeviceSkeleton(it) } }
                    list.isEmpty() -> Text(
                        "Nenhum dispositivo agora. Abra o Spotify no PC, na TV ou na caixa de som com a mesma conta.",
                        Modifier.padding(16.dp),
                        color = c.onSurfaceVariant,
                    )
                    else -> Column {
                        list.forEach { device ->
                            // This phone counts as playing when nothing else is.
                            val current = if (s.remote != null) device.id == s.remote.id else device.isSelf
                            DeviceRow(device, current, accent) {
                                haptics.press()
                                if (!current) controls.transfer(device)
                                onDismiss()
                            }
                            val volume = device.volume
                            if (current && !device.isSelf && !device.restricted && volume != null) {
                                VolumeRow(volume.toInt(), controls::setVolume)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Device, current: Boolean, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (current) accent.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (current) accent else c.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                deviceIcon(device.kind), null,
                tint = if (current) c.background else c.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = if (current) accent else c.onSurface,
            )
            Text(
                when {
                    current && device.isSelf -> "Este celular · tocando aqui"
                    device.isSelf -> "Este celular"
                    current -> "Tocando agora · ${kindLabel(device.kind)}"
                    else -> kindLabel(device.kind)
                },
                fontSize = 13.sp, color = c.onSurfaceVariant, maxLines = 1,
            )
        }
        if (current) Icon(Icons.Rounded.GraphicEq, "Tocando", tint = accent)
    }
}

@Composable
private fun VolumeRow(volume: Int, onVolume: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    var drag by remember(volume) { mutableStateOf<Float?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        ThinSlider(
            drag ?: (volume / 100f),
            { drag = it },
            { drag?.let { onVolume((it * 100).toInt()) } },
            Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DeviceSkeleton(seed: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Bone(Modifier.size(44.dp), CircleShape)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            TextBone(boneWidth(seed), 24.dp, 14.dp)
            TextBone(boneWidth(seed, 1) * .6f, 24.dp, 11.dp)
        }
    }
}
