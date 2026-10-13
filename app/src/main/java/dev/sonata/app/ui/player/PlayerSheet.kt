package dev.sonata.app.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import dev.sonata.app.playback.PlaybackSnapshot
import dev.sonata.app.ui.design.GlassBox
import dev.sonata.app.ui.design.LivingArtwork
import dev.sonata.app.ui.design.Motion
import dev.sonata.app.ui.design.ThinSlider
import dev.sonata.app.ui.design.kenBurns
import dev.sonata.app.ui.design.snapNow
import dev.sonata.app.ui.design.liquidGlass
import dev.sonata.app.ui.design.rememberHaptics
import dev.sonata.app.ui.design.springPress
import dev.sonata.app.ui.theme.isDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.sonata_core.RepeatMode

/** What the player can ask playback to do. */
interface PlayerControls {
    fun playPause()
    fun next()
    fun previous()
    fun seek(positionMs: Long)
    fun setShuffle(on: Boolean)
    fun setRepeat(mode: RepeatMode)
}

private val settle = Motion.settle<Float>(247f)
private fun fmt(ms: Long) = (ms.coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }

/** Current position, ticking while playing. */
@Composable
private fun rememberPosition(s: PlaybackSnapshot): Long {
    var position by remember { mutableLongStateOf(s.currentPositionMs()) }
    LaunchedEffect(s) {
        while (true) {
            position = s.currentPositionMs()
            if (!s.playing) break
            delay(250)
        }
    }
    return position
}

/**
 * The mini player that becomes the full-screen player. `f` runs from 0
 * (mini) to 1 (full) and drives everything — cover size and position,
 * backdrop, corners, controls. Dragging moves `f` with the finger; letting go
 * settles on a spring. Predictive back shrinks it along the gesture.
 *
 * The layout is fixed at full screen and never changes while expanding:
 * everything that animates is read in graphicsLayer/draw, so animation
 * frames neither recompose nor remeasure.
 */
@Composable
fun PlayerSheet(s: PlaybackSnapshot, controls: PlayerControls) {
    val track = s.track ?: return
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val f = remember { Animatable(0f).apply { updateBounds(0f, 1f) } }
    var back by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val position = rememberPosition(s)
    val duration = track.durationMs.toLong().coerceAtLeast(1)
    val count = s.queue.uris.size
    val index = s.queue.position.toInt()
    val hasNext = index < count - 1 || s.queue.repeat == RepeatMode.ALL

    val pf = { f.value.coerceIn(0f, 1f) }
    val expanded by remember { derivedStateOf { f.value > 0.5f } }
    val collapsed by remember { derivedStateOf { f.value < .05f } }
    val showMini by remember { derivedStateOf { f.value < .6f } }
    val showGlass by remember { derivedStateOf { f.value < .99f } }
    val showFull by remember { derivedStateOf { f.value > .4f } }
    val showBg by remember { derivedStateOf { f.value > 0.01f } }
    val settled by remember { derivedStateOf { f.value > .9f } }

    PredictiveBackHandler(enabled = expanded) { progress ->
        try {
            progress.collect { back = it.progress }
            scope.launch { f.animateTo(0f, settle) }
            back = 0f
        } catch (e: CancellationException) {
            animate(back, 0f, animationSpec = settle) { v, _ -> back = v }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullH = constraints.maxHeight.toFloat()
        val fullW = constraints.maxWidth.toFloat()
        val miniH = with(density) { 68.dp.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        val dragState = rememberDraggableState { d ->
            scope.snapNow(f, (f.value - d / (fullH - miniH)).coerceIn(0f, 1f))
        }
        // Mini: a compact card with equal side margins, just above the dock and the gesture bar.
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val dockH = with(density) { 82.dp.toPx() }
        val miniTop = fullH - miniH - margin - navBottom - dockH
        val surface = c.surface

        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val p = pf()
                    translationY = lerp(miniTop, 0f, p)
                    val sc = 1f - back * 0.12f
                    scaleX = sc
                    scaleY = sc
                    transformOrigin = TransformOrigin(.5f, 0f)
                    shape = SheetShape(lerp(margin, 0f, p), lerp(miniH, fullH, p).coerceIn(miniH, fullH), (lerp(34f, 0f, p) + back * 36f) * density.density)
                    clip = true
                }
                .drawBehind { drawRect(surface.copy(alpha = (pf() * 1.6f).coerceIn(0f, 1f))) }
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    onDragStarted = { haptics.tick() },
                    onDragStopped = { v ->
                        val target = when {
                            v < -Motion.FlingVelocity -> 1f
                            v > Motion.FlingVelocity -> 0f
                            else -> if (f.value > .5f) 1f else 0f
                        }
                        f.animateTo(target, settle, initialVelocity = -v / (fullH - miniH))
                    },
                ),
        ) {
            // Glass mini player: blurs what is behind it, only in the mini band.
            if (showGlass) {
                Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(68.dp)
                        .graphicsLayer { alpha = (1f - pf()).coerceIn(0f, 1f) }
                        .liquidGlass(RoundedCornerShape(34.dp), c.isDark),
                )
            }
            // Living backdrop: a slow mesh gradient in the cover's colours.
            if (showBg) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = pf() }) {
                    LivingArtwork(track.coverUrl, Modifier.fillMaxSize(), dim = .2f)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.15f), Color.Black.copy(.55f)))))
                }
            }

            val maxW = this@BoxWithConstraints.maxWidth
            val maxH = this@BoxWithConstraints.maxHeight
            val insetTop = with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }
            val insetBottom = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
            var controlsH by remember { mutableStateOf(300.dp) }
            val artSpace = (maxH - insetTop - 56.dp - controlsH - insetBottom - 12.dp).coerceAtLeast(120.dp)
            val artFull = minOf(maxW - 56.dp, artSpace - 16.dp, 420.dp).coerceAtLeast(120.dp)
            val fullX = (maxW - artFull) / 2
            val fullY = insetTop + 56.dp + (artSpace - artFull) / 2
            val artFullPx = with(density) { artFull.toPx() }
            val fullXPx = with(density) { fullX.toPx() }
            val fullYPx = with(density) { fullY.toPx() }
            val miniArt = with(density) { 48.dp.toPx() }
            val miniX = margin + with(density) { 10.dp.toPx() }
            val miniY = with(density) { 10.dp.toPx() }

            // The cover breathes: full while playing, recedes when paused.
            val breath = animateFloatAsState(if (s.playing || !expanded) 1f else 0.84f, Motion.settle(169f), label = "breath")
            // Throwable cover: a strong horizontal drag skips, a weak one springs back.
            val throwX = remember { Animatable(0f) }
            var tapHint by remember { mutableStateOf<Pair<Long, Int>?>(null) }

            fun GraphicsLayerScope.artMove(extra: Float) {
                val p = pf()
                val k = lerp(miniArt / artFullPx, 1f, p)
                transformOrigin = TransformOrigin(0f, 0f)
                val sc = k * extra
                val shift = artFullPx * k * (1f - extra) / 2f
                translationX = lerp(miniX, fullXPx, p) - fullXPx + shift + throwX.value
                translationY = lerp(miniY, fullYPx, p) - fullYPx + shift
                scaleX = sc
                scaleY = sc
            }

            Box(
                Modifier.offset(fullX, fullY).size(artFull)
                    .graphicsLayer {
                        val p = pf()
                        val k = lerp(miniArt / artFullPx, 1f, p)
                        artMove(lerp(1f, breath.value, p) * (1f - kotlin.math.abs(throwX.value) / fullW * .15f))
                        rotationZ = throwX.value / fullW * 14f
                        // Corners and shadow compensate the scale: 12 dp mini, 28 dp full.
                        shape = RoundedCornerShape((lerp(12f, 28f, p) * density.density / k.coerceAtLeast(.05f)).coerceAtLeast(0f))
                        clip = true
                        shadowElevation = lerp(0f, 24f, p) * density.density
                    }
                    .then(
                        if (settled) {
                            Modifier.pointerInput(track.uri) {
                                detectTapGestures(onDoubleTap = { o ->
                                    val dir = if (o.x < size.width / 2) -1 else 1
                                    controls.seek((position + dir * 10_000).coerceIn(0, duration))
                                    tapHint = System.nanoTime() to dir
                                })
                            }
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (settled) {
                            Modifier.draggable(
                                rememberDraggableState { d -> scope.snapNow(throwX, throwX.value + d) },
                                Orientation.Horizontal,
                                onDragStopped = { v ->
                                    val go = when {
                                        throwX.value < -fullW / 3 || v < -1800f -> 1
                                        throwX.value > fullW / 3 || v > 1800f -> -1
                                        else -> 0
                                    }
                                    if (go != 0 && (go == -1 || hasNext)) {
                                        haptics.press()
                                        throwX.animateTo(-go * fullW * 1.3f, Motion.settle(325f))
                                        if (go == 1) controls.next() else controls.previous()
                                        throwX.snapTo(go * fullW * 1.1f)
                                    }
                                    throwX.animateTo(0f, Motion.settle(208f))
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .background(c.outline),
                contentAlignment = Alignment.Center,
            ) {
                if (!track.coverUrl.isNullOrBlank()) {
                    AsyncImage(track.coverUrl, null, Modifier.fillMaxSize().kenBurns(s.playing && settled), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = c.onSurfaceVariant, modifier = Modifier.fillMaxSize(.4f))
                }
                tapHint?.let { (id, dir) -> SeekRipple(id, dir) }
            }

            // Mini player row (fades out while expanding). Only its band opens the player.
            if (showMini) {
                Row(
                    Modifier.fillMaxWidth().height(68.dp)
                        .clickable(enabled = collapsed, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            scope.launch { f.animateTo(1f, Motion.enter(Motion.Medium)) }
                        }
                        .graphicsLayer { alpha = (1f - pf() / .6f).coerceIn(0f, 1f) }
                        .padding(start = 86.dp, end = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = c.onSurface, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artists, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PlayButton(s, controls, 44.dp, c.primary, c.onPrimary)
                    if (hasNext) {
                        IconButton(controls::next) { Icon(Icons.Rounded.SkipNext, "Próxima", tint = c.onSurface) }
                    }
                }
            }

            // Full player content (appears while expanding).
            val rise = with(density) { 40.dp.toPx() }
            if (showFull) {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding()
                        .graphicsLayer {
                            val p = pf()
                            alpha = ((p - .4f) / .6f).coerceIn(0f, 1f)
                            translationY = lerp(rise, 0f, p)
                        }
                        .padding(horizontal = 28.dp),
                ) {
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ scope.launch { f.animateTo(0f, settle) } }) {
                            Icon(Icons.Rounded.KeyboardArrowDown, "Recolher", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(.35f)))
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(48.dp))
                    }
                    Spacer(Modifier.height(artSpace))
                    Column(
                        Modifier.wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged {
                            val h = with(density) { it.height.toDp() }
                            if (kotlin.math.abs((h - controlsH).value) > 1f) controlsH = h
                        },
                    ) {
                        Text(
                            track.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp,
                            letterSpacing = (-.5f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.artists, color = Color.White.copy(.62f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(18.dp))
                        var drag by remember { mutableStateOf<Float?>(null) }
                        ThinSlider(
                            drag ?: (position.toFloat() / duration),
                            { drag = it },
                            {
                                drag?.let { controls.seek((it * duration).toLong()) }
                                drag = null
                            },
                        )
                        val shown = drag?.let { (it * duration).toLong() } ?: position
                        Row {
                            Text(fmt(shown), color = Color.White.copy(.6f), fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text("-" + fmt(duration - shown), color = Color.White.copy(.6f), fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SideButton(Icons.Rounded.SkipPrevious, "Anterior", Modifier.weight(1f), true, controls::previous)
                            PlayButton(s, controls, 84.dp, Color.White, Color.Black, Modifier.weight(2f), pill = true)
                            SideButton(Icons.Rounded.SkipNext, "Próxima", Modifier.weight(1f), hasNext, controls::next)
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ToggleIcon(Icons.Rounded.Shuffle, "Aleatório", s.queue.shuffle) { controls.setShuffle(!s.queue.shuffle) }
                            Spacer(Modifier.width(8.dp))
                            ToggleIcon(
                                if (s.queue.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                when (s.queue.repeat) {
                                    RepeatMode.ONE -> "Repetir faixa"
                                    RepeatMode.ALL -> "Repetir fila"
                                    RepeatMode.OFF -> "Repetição desligada"
                                },
                                s.queue.repeat != RepeatMode.OFF,
                            ) {
                                controls.setRepeat(
                                    when (s.queue.repeat) {
                                        RepeatMode.OFF -> RepeatMode.ALL
                                        RepeatMode.ALL -> RepeatMode.ONE
                                        RepeatMode.ONE -> RepeatMode.OFF
                                    },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (count > 1) Text("${index + 1} de $count", color = Color.White.copy(.5f), fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButton(
    s: PlaybackSnapshot,
    controls: PlayerControls,
    size: Dp,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    pill: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        modifier.then(if (pill) Modifier.height(size) else Modifier.size(size)).springPress(source).clip(CircleShape).background(bg)
            .clickable(source, null) {
                haptics.press()
                controls.playPause()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (s.loading) {
            CircularProgressIndicator(Modifier.size(size * .4f), color = fg, strokeWidth = 2.dp)
        } else {
            AnimatedContent(s.playing, label = "pp") { playing ->
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "Pausar" else "Tocar",
                    tint = fg,
                    modifier = Modifier.size(size * .45f),
                )
            }
        }
    }
}

@Composable
private fun SideButton(icon: ImageVector, description: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    GlassBox(modifier.height(84.dp).alpha(if (enabled) 1f else .35f).springPress(source), CircleShape) {
        Box(
            Modifier.matchParentSize().clickable(source, null, enabled = enabled) {
                haptics.press()
                onClick()
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        Modifier.size(48.dp).springPress(source).clip(CircleShape)
            .background(if (on) Color.White.copy(.9f) else Color.White.copy(.08f))
            .clickable(source, null) {
                haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (on) Color.Black else Color.White.copy(.8f), modifier = Modifier.size(22.dp))
    }
}

/** A wash from the tapped side with "−10 s" or "+10 s". */
@Composable
private fun BoxScope.SeekRipple(id: Long, dir: Int) {
    val v = remember(id) { Animatable(0f) }
    LaunchedEffect(id) { v.animateTo(1f, Motion.enter(Motion.ExtraLong)) }
    if (v.value >= 1f) return
    Box(
        Modifier.align(if (dir < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.5f)
            .graphicsLayer { alpha = 1f - v.value }
            .background(
                Brush.horizontalGradient(
                    if (dir < 0) listOf(Color.White.copy(.35f), Color.Transparent) else listOf(Color.Transparent, Color.White.copy(.35f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (dir < 0) "−10 s" else "+10 s",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer {
                val sc = .8f + v.value * .4f
                scaleX = sc
                scaleY = sc
                translationX = dir * v.value * 40f
            },
        )
    }
}

/** The card's clip: band [inset, w - inset] × [0, height] with rounded corners. */
private data class SheetShape(val inset: Float, val height: Float, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(
            RoundRect(
                inset, 0f, (size.width - inset).coerceAtLeast(inset), height.coerceAtMost(size.height),
                CornerRadius(radius.coerceAtLeast(0f)),
            ),
        )
}
