package dev.sonata.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonata.app.ui.AppActions
import dev.sonata.app.ui.Route
import dev.sonata.app.ui.design.CollectionBackdrop
import dev.sonata.app.ui.design.Cover
import dev.sonata.app.ui.design.DetailTopBar
import dev.sonata.app.ui.design.GlassBox
import dev.sonata.app.ui.design.GlassIcon
import dev.sonata.app.ui.design.LocalBottomSpace
import dev.sonata.app.ui.design.RoundIconButton
import dev.sonata.app.ui.design.TopBarHeight
import dev.sonata.app.ui.design.TrackRow
import dev.sonata.app.ui.design.collapseOf
import dev.sonata.app.ui.design.dragToDismiss
import dev.sonata.app.ui.design.rememberHaptics
import uniffi.sonata_core.Track

/**
 * Root tab header: a small wordmark and the settings button; a centred
 * small title appears once the large title scrolls away.
 */
@Composable
fun RootHeader(title: String, collapse: State<Float>, actions: AppActions) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "sonata",
                Modifier.weight(1f).graphicsLayer { alpha = (1f - collapse.value * 1.4f).coerceIn(0f, 1f) },
                style = MaterialTheme.typography.titleLarge,
                letterSpacing = (-1).sp,
            )
            RoundIconButton(Icons.Rounded.Settings, "Ajustes", { actions.open(Route.Settings) }, container = c.background)
        }
        Text(
            title,
            Modifier.align(Alignment.Center).clearAndSetSemantics { }.graphicsLayer {
                val v = ((collapse.value - .55f) / .45f).coerceIn(0f, 1f)
                alpha = v
                translationY = (1f - v) * 12.dp.toPx()
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * Album, playlist or liked songs: a collapsing hero header with glass
 * actions and the track list, over the cover's living backdrop. Pull down at
 * the top to close.
 */
@Composable
fun CollectionScreen(
    actions: AppActions,
    list: LazyListState,
    title: String,
    subtitle: String?,
    meta: String,
    imageUrl: String?,
    tracks: List<Track>,
    modifier: Modifier = Modifier,
    numbered: Boolean = false,
    round: Boolean = false,
    onSubtitle: (() -> Unit)? = null,
    /** Library key counted when playing from here (`pl:<id>`, `al:<id>`, `liked`). */
    source: String? = null,
    footer: LazyListScope.() -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val collapse = collapseOf(list)
    val dismiss = remember { Animatable(0f) }
    val back = {
        haptics.tick()
        actions.back()
        Unit
    }
    val nowPlaying by actions.app.bridge.state.collectAsState()

    Box(modifier) {
        CollectionBackdrop(imageUrl, collapse, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().dragToDismiss(dismiss, back)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(bottom = LocalBottomSpace.current),
            ) {
                item(key = "header") {
                    Column(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(top = TopBarHeight)
                            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                            .graphicsLayer { alpha = (1f - collapse.value * 1.2f).coerceIn(0f, 1f) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Cover(
                            imageUrl,
                            Modifier.size(208.dp).graphicsLayer {
                                val sc = 1f - .15f * collapse.value.coerceIn(0f, 1f)
                                scaleX = sc
                                scaleY = sc
                            },
                            radius = if (round) 104.dp else 10.dp,
                            motion = true,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            title, color = Color.White, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                Modifier.clip(CircleShape)
                                    .then(if (onSubtitle != null) Modifier.clickable { haptics.tick(); onSubtitle() } else Modifier)
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            meta.uppercase(), color = Color.White.copy(.7f), fontSize = 12.sp, letterSpacing = 1.sp,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            GlassIcon(Icons.Rounded.Shuffle, "Aleatório", {
                                haptics.press()
                                actions.play(tracks, tracks.indices.random(), shuffle = true, source = source)
                            }, enabled = tracks.isNotEmpty())
                            GlassBox(Modifier.height(48.dp).width(144.dp), CircleShape) {
                                Row(
                                    Modifier.matchParentSize().clickable(enabled = tracks.isNotEmpty()) {
                                        haptics.press()
                                        actions.play(tracks, source = source)
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Tocar", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
                itemsIndexed(tracks) { i, track ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        TrackRow(
                            track,
                            { actions.play(tracks, i, source = source) },
                            number = if (numbered) i + 1 else null,
                            playing = nowPlaying.track?.uri == track.uri,
                            // Long press opens the track's album.
                            onLongClick = track.albumId?.let { id -> { actions.open(Route.Album(id)) } },
                        )
                    }
                    if (i < tracks.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = if (numbered) 66.dp else 86.dp, end = 16.dp), color = c.outlineVariant)
                    }
                }
                footer()
            }
        }
        Box(Modifier.statusBarsPadding()) {
            DetailTopBar(title, collapse, back) { haptics.press(); actions.play(tracks, source = source) }
        }
    }
}
