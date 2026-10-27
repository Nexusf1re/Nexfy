package dev.nexfy.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.playback.PlaybackSnapshot
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.GlassBackdrop
import dev.nexfy.app.ui.design.GlassLevel
import dev.nexfy.app.ui.design.LivingArtwork
import dev.nexfy.app.ui.design.LocalHaze
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.artInk
import dev.nexfy.app.ui.design.artWash
import dev.nexfy.app.ui.design.glassSource
import dev.nexfy.app.ui.design.liquidGlass
import dev.nexfy.app.ui.player.LikeButton
import dev.nexfy.app.ui.player.PlayButton
import dev.nexfy.app.ui.player.SideButton
import dev.nexfy.app.ui.player.ToggleIcon
import dev.nexfy.app.ui.player.ToggleMotion
import dev.nexfy.app.ui.player.TrackActionsSheet
import dev.nexfy.app.ui.theme.isDark
import uniffi.nexfy_core.RepeatMode

/**
 * The song, given the whole window: "Tocando agora".
 *
 * This is the player the phone already has, taken apart and laid out for a
 * screen that is wider than it is tall — the same living artwork behind it,
 * the same glass transport, the same seek line, the same words. What does
 * not come with it is the phone's hands: nothing here is dragged, flipped or
 * thrown, because a window is worked with a pointer. The way out is a button
 * at the top left and the Esc key, as every other layer of this shell is.
 */
@Composable
internal fun NowPlaying(
    s: PlaybackSnapshot,
    actions: AppActions,
    modifier: Modifier,
    /** Opened from the panel's "Letra cheia": the words are up from the start. */
    startWithLyrics: Boolean = false,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val ink = artInk()
    val wash = artWash()
    val track = s.track
    // The player's own glass source: the buttons standing over the art refract
    // the living backdrop behind them, not the page they are covering.
    val backdrop = remember { GlassBackdrop() }
    var lyrics by remember { mutableStateOf(startWithLyrics) }
    var menu by remember { mutableStateOf(false) }
    // Nothing follows: the skip forward goes quiet rather than acting as if a
    // song would come. Another device keeps a queue of its own.
    val hasNext = s.remote != null || s.queue.next.isNotEmpty()
    if (menu && track != null) {
        TrackActionsSheet(track, actions, onNavigate = { menu = false; onClose() }) { menu = false }
    }
    Box(modifier) {
        Box(Modifier.fillMaxSize().glassSource(backdrop)) {
            LivingArtwork(track?.coverUrl, Modifier.fillMaxSize(), dim = .2f, pale = !c.isDark)
            // The wash gathers at the foot, under the words.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, wash.copy(alpha = .15f), wash.copy(alpha = .55f)),
                    ),
                ),
            )
        }
        // Every pane of glass in here refracts the living backdrop behind it,
        // the way the phone's player glass does, rather than the page it covered.
        CompositionLocalProvider(LocalHaze provides backdrop) {
        Column(
            Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The way out on the left and the song's own menu on the right,
            // with where it is playing from centred on the window between them.
            Box(Modifier.fillMaxWidth().height(54.dp)) {
                Row(
                    Modifier.align(Alignment.CenterStart)
                        .liquidGlass(CircleShape, c.isDark, GlassLevel.Regular),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToggleIcon(Icons.Rounded.CloseFullscreen, "Sair de tocando agora", false, onClick = onClose)
                    if (track != null) {
                        ToggleIcon(Icons.Rounded.Lyrics, "Letra", lyrics) { lyrics = !lyrics }
                    }
                }
                playingFrom(s, track?.album, actions)?.let { from ->
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Tocando de", color = ink.copy(alpha = .55f), fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp,
                        )
                        Text(
                            from, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (track != null) {
                    Box(Modifier.align(Alignment.CenterEnd).liquidGlass(CircleShape, c.isDark, GlassLevel.Regular)) {
                        ToggleIcon(Icons.Rounded.MoreHoriz, "Mais opções", false) { menu = true }
                    }
                }
            }
            // The art takes whatever the strip above and the controls below
            // leave it, square and centred: a tall window gives it its full
            // width, a short one shrinks it rather than pushing the song's name
            // off the bottom. With nothing playing the arrangement stands as it
            // is, on the empty sleeve, the way the bar along the bottom does.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp), Alignment.Center) {
                val side = minOf(maxWidth, maxHeight, 440.dp).coerceAtLeast(96.dp)
                val shape = RoundedCornerShape(28.dp)
                // The cover breathes as the phone's does: full while the music
                // plays, receding a touch when it stops.
                val breath by animateFloatAsState(
                    if (s.playing || lyrics) 1f else .92f,
                    Motion.settle(169f),
                    label = "breath",
                )
                // Under the words the art sinks out of focus, so the lines land
                // on something quiet instead of on the picture.
                val soft by animateDpAsState(if (lyrics) 18.dp else 0.dp, Motion.standard(), label = "soft")
                Box(Modifier.size(side).graphicsLayer { scaleX = breath; scaleY = breath }.shadow(26.dp, shape)) {
                    Cover(track?.coverUrl, Modifier.fillMaxSize().blur(soft, BlurredEdgeTreatment(shape)), radius = 28.dp)
                    if (lyrics) {
                        Box(Modifier.fillMaxSize().background(wash.copy(alpha = .42f)))
                        LyricsPane(s, actions, Modifier.fillMaxSize().padding(vertical = 12.dp))
                    }
                }
            }
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(18.dp))
                // The phone's own transport, in the order a window reads it: how
                // the queue runs either side of the three that move through it.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToggleIcon(Icons.Rounded.Shuffle, "Aleatório", s.queue.shuffle, ToggleMotion.Wiggle) {
                        actions.setShuffle(!s.queue.shuffle)
                    }
                    SideButton(Icons.Rounded.SkipPrevious, "Anterior", Modifier.weight(1f), true, -1, actions::previous)
                    PlayButton(s, actions, 84.dp, ink, ink, Modifier.weight(2f), pill = true, glass = true)
                    SideButton(Icons.Rounded.SkipNext, "Próxima", Modifier.weight(1f), hasNext, 1, actions::next)
                    ToggleIcon(
                        if (s.queue.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        when (s.queue.repeat) {
                            RepeatMode.ONE -> "Repetir faixa"
                            RepeatMode.ALL -> "Repetir fila"
                            RepeatMode.OFF -> "Repetição desligada"
                        },
                        s.queue.repeat != RepeatMode.OFF,
                        ToggleMotion.Spin,
                    ) {
                        actions.setRepeat(
                            when (s.queue.repeat) {
                                RepeatMode.OFF -> RepeatMode.ALL
                                RepeatMode.ALL -> RepeatMode.ONE
                                RepeatMode.ONE -> RepeatMode.OFF
                            },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                SeekLine(s, actions, large = true)
                Spacer(Modifier.height(16.dp))
                Text(
                    track?.title ?: "Nada tocando", color = ink, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    lineHeight = 34.sp, letterSpacing = (-.5f).sp, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track?.artists.orEmpty(), color = ink.copy(alpha = .62f), fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (track != null && track.album.isNotBlank() && track.album != track.title) {
                    Text(
                        track.album, color = ink.copy(alpha = .42f), fontSize = 13.sp,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // What the bar along the bottom carried, now that it has stepped
                // aside: the heart, where it sounds, and how loud.
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    track?.let { LikeButton(it.uri) }
                    ToggleIcon(Icons.Rounded.GraphicEq, "Equalizador", false) {
                        onClose()
                        actions.openEqualizer()
                    }
                    ToggleIcon(Icons.Rounded.Devices, "Dispositivos", s.remote != null) { actions.openDevices() }
                    Volume(s, actions)
                }
            }
        }
        }
    }
}
