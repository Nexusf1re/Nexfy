package dev.nexfy.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.Release
import dev.nexfy.app.Updates
import dev.nexfy.app.ui.design.GlassSheet
import dev.nexfy.app.ui.design.PillButton
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.theme.accentColor

/** "Nexfy 0.2.6 disponível" over the dock; closed, it waits for the next start (the settings keep it). */
@Composable
fun UpdateBanner(updates: Updates, onOpen: () -> Unit, modifier: Modifier) {
    val offer by updates.offer.collectAsState()
    val hidden by updates.bannerHidden.collectAsState()
    val haptics = rememberHaptics()
    AnimatedVisibility(
        offer != null && !hidden,
        modifier,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        val release = offer ?: return@AnimatedVisibility
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.shadow(12.dp, CircleShape).clip(CircleShape).background(Color(0xFF1B1B20))
                    .border(1.dp, Color.White.copy(.08f), CircleShape)
                    .clickable { haptics.tick(); onOpen() }
                    .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Nexfy ${release.version} disponível", color = Color.White, fontSize = 14.sp)
                Text(
                    "Atualizar", Modifier.padding(start = 12.dp),
                    color = accentColor(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.Rounded.Close, "Agora não", tint = Color.White.copy(.6f),
                    modifier = Modifier.padding(start = 6.dp).clip(CircleShape)
                        .clickable { haptics.tick(); updates.hideBanner() }.padding(8.dp).size(18.dp),
                )
            }
        }
    }
}

/** What a release brings, and the way to get it. */
@Composable
fun UpdateSheet(release: Release, installed: String, onDownload: () -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    GlassSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text("Nexfy ${release.version}", style = MaterialTheme.typography.headlineSmall)
                Text("Você tem a $installed", color = c.onSurfaceVariant, fontSize = 13.sp)
            }
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val lines = notesLines(release.notes)
                if (lines.isEmpty()) {
                    Text("Melhorias e correções.", color = c.onSurfaceVariant, fontSize = 14.sp)
                }
                lines.forEach { line ->
                    Row {
                        Box(Modifier.padding(top = 8.dp, end = 10.dp).size(5.dp).clip(CircleShape).background(accentColor()))
                        Text(line, color = c.onSurface, fontSize = 14.sp)
                    }
                }
            }
            Text(
                "O download abre no navegador; instale por cima, sem desinstalar.",
                color = c.onSurfaceVariant, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Baixar", onDownload, icon = Icons.Rounded.Download)
                PillButton("Agora não", onDismiss, filled = false)
            }
        }
    }
}

/**
 * The release notes as plain lines: list markers and markdown emphasis
 * dropped, headings and links kept as their text.
 */
private fun notesLines(notes: String): List<String> = notes.lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("**Full Changelog") }
    .map { line ->
        line.removePrefix("- ").removePrefix("* ").trimStart('#', ' ')
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
    }
