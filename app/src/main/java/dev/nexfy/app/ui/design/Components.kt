package dev.nexfy.app.ui.design

import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.HazeState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.nexfy.app.ui.theme.LocalLook
import dev.nexfy.app.ui.theme.isDark
import kotlinx.coroutines.launch
import uniffi.nexfy_core.Track
import uniffi.nexfy_core.UserFace
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

// ------------------------------------------------------------- haptics --

/**
 * Light tick for toggles and drags, stronger for play/pause and skips.
 * Silent when "Vibração" is off, so callers never check it.
 */
class Haptics(private val feedback: HapticFeedback, private val enabled: Boolean) {
    fun tick() {
        if (enabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** A command did not go through. */
    fun reject() {
        if (enabled) feedback.performHapticFeedback(HapticFeedbackType.Reject)
    }

    fun press() {
        if (enabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalLook.current.haptics
    return remember(feedback, enabled) { Haptics(feedback, enabled) }
}

/** Space under lists so the floating dock and mini player never cover content. */
val LocalBottomSpace = compositionLocalOf { 104.dp }

// ------------------------------------------------------------- buttons --

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val c = MaterialTheme.colorScheme
    val bg = if (filled) c.primary else c.surfaceVariant
    val fg = if (filled) c.onPrimary else c.onSurface
    Row(
        modifier.springPress(source).alpha(if (enabled) 1f else .4f).clip(CircleShape).background(bg)
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Segmented choice whose highlight is a liquid lens, like the dock's: its
 * leading edge springs fast, the trailing one lags, then it settles; the
 * labels under it show magnified, more so while it stretches.
 */
@Composable
fun <T> PillSelector(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val index = options.indexOf(selected).coerceAtLeast(0).toFloat()
    val goingRight = remember { mutableStateOf(true) }
    val last = remember { mutableFloatStateOf(index) }
    if (index != last.floatValue) {
        goingRight.value = index > last.floatValue
        last.floatValue = index
    }
    val fast = Motion.settle<Float>(455f)
    val slow = Motion.settle<Float>(169f)
    val left by animateFloatAsState(index, if (goingRight.value) slow else fast, label = "l")
    val right by animateFloatAsState(index + 1, if (goingRight.value) fast else slow, label = "r")
    val n = options.size.coerceAtLeast(1)
    val dark = c.isDark
    val accent = dev.nexfy.app.ui.theme.accentColor()
    val labels = rememberGraphicsLayer()
    val lensPath = remember { Path() }
    Box(modifier.glassPane(CircleShape, c.surfaceVariant.copy(alpha = .7f), dark).padding(4.dp)) {
        Row(
            Modifier.drawWithContent {
                val w = size.width / n
                val l = left.coerceIn(-0.2f, n.toFloat()) * w
                val r = right.coerceIn(0f, n + 0.2f) * w
                labels.record { this@drawWithContent.drawContent() }
                if (r <= l) {
                    drawLayer(labels)
                    return@drawWithContent
                }
                val stretch = ((r - l) / w - 1f).coerceIn(0f, 1f)
                val lens = Rect(l, 0f, r, size.height)
                val corner = CornerRadius(size.height / 2)
                lensPath.rewind()
                lensPath.addRoundRect(RoundRect(lens, corner))
                drawRoundRect(accent.copy(alpha = if (dark) .2f else .14f), lens.topLeft, lens.size, corner)
                clipPath(lensPath, ClipOp.Difference) { drawLayer(labels) }
                clipPath(lensPath) { scale(1.06f + .06f * stretch, pivot = lens.center) { drawLayer(labels) } }
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(if (dark) .4f else .9f), Color.White.copy(.04f), Color.White.copy(if (dark) .16f else .5f)),
                        startY = lens.top,
                        endY = lens.bottom,
                    ),
                    lens.topLeft, lens.size, corner,
                    style = Stroke(1.dp.toPx()),
                )
            },
        ) {
            options.forEach { option ->
                val source = remember { MutableInteractionSource() }
                val on = option == selected
                val fg by animateColorAsState(if (on) c.onSurface else c.onSurfaceVariant, label = "fg")
                Box(
                    Modifier.weight(1f).springPress(source).clip(CircleShape)
                        .clickable(source, null) {
                            if (!on) haptics.tick()
                            onSelect(option)
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(option), color = fg, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    container: Color? = null,
) {
    val source = remember { MutableInteractionSource() }
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val edge = Brush.verticalGradient(listOf(c.onSurface.copy(alpha = .22f), c.onSurface.copy(alpha = .03f)))
    Box(
        Modifier.size(48.dp).springPress(source).clip(CircleShape)
            .background(if (filled) c.primary else (container ?: c.surfaceVariant).copy(alpha = .72f))
            .then(if (filled) Modifier else Modifier.border(1.dp, edge, CircleShape))
            .clickable(source, null) {
                haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (filled) c.onPrimary else c.onSurface, modifier = Modifier.size(20.dp))
    }
}

/** Round glass button for use over artwork. */
@Composable
fun GlassIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    light: Boolean = false,
    /** A toggle that is on: the icon takes the accent. */
    selected: Boolean = false,
    /**
     * Floats over a page's own glass backdrop ([LocalHaze] set to it), so
     * it can be real liquid glass; off, a tinted pane for use inside lists.
     */
    liquid: Boolean = false,
) {
    val dark = !light || MaterialTheme.colorScheme.isDark
    val tint = when {
        selected -> dev.nexfy.app.ui.theme.accentColor()
        light -> MaterialTheme.colorScheme.onBackground
        else -> Color.White
    }
    val content: @Composable BoxScope.() -> Unit = {
        Box(
            Modifier.matchParentSize().clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = tint.copy(alpha = if (enabled) 1f else .4f))
        }
    }
    if (liquid) {
        val source = remember { MutableInteractionSource() }
        Box(Modifier.size(48.dp).springPress(source).liquidGlass(CircleShape, dark, GlassLevel.Subtle)) {
            Box(
                Modifier.matchParentSize().clickable(source, null, enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, description, tint = tint.copy(alpha = if (enabled) 1f else .4f))
            }
        }
    } else {
        GlassBox(Modifier.size(48.dp), CircleShape, dark = dark, content = content)
    }
}

/** Thin progress slider: a 4 dp track that thickens while dragging. [value] is 0..1. */
@Composable
fun ThinSlider(
    /** Read while drawing, so a position that moves every frame costs no recomposition. */
    progress: () -> Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The song is still coming: the bar breathes instead of standing dead. */
    waiting: () -> Boolean = { false },
    /** Playing: the head carries a soft bloom, which dims when the music stops. */
    lit: Boolean = false,
) {
    val haptics = rememberHaptics()
    val bloom by animateFloatAsState(if (lit) 1f else 0f, Motion.standard(Motion.Medium), label = "bloom")
    var dragging by remember { mutableStateOf(false) }
    // Held: the track swells, the head grows into a knob and a halo opens under it.
    val grab by animateFloatAsState(if (dragging) 1f else 0f, Motion.settle(260f), label = "grab")
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Canvas(
        modifier.fillMaxWidth().height(28.dp).then(
            if (!enabled) {
                Modifier
            } else {
                Modifier
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { o ->
                                dragging = true
                                haptics.tick()
                                change((o.x / size.width).coerceIn(0f, 1f))
                            },
                            onDragEnd = { dragging = false; done() },
                            onDragCancel = { dragging = false; done() },
                        ) { c, _ -> change((c.position.x / size.width).coerceIn(0f, 1f)) }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            change((o.x / size.width).coerceIn(0f, 1f))
                            done()
                        }
                    }
            },
        ),
    ) {
        val thickness = (4.dp + 4.dp * grab).toPx()
        val knob = (3.dp + 4.dp * grab).toPx()
        val y = size.height / 2
        // The head never leaves the track, so the knob has room at both ends.
        val from = knob
        val to = size.width - knob
        val head = from + (to - from) * progress().coerceIn(0f, 1f)
        // Waiting: the played part fades in and out, about once a second.
        val breath = if (waiting()) .55f + .37f * (1f + sin(now() / 1000f * PI.toFloat() * 2f)) / 2f else .92f
        drawLine(Color.White.copy(alpha = .22f), Offset(from, y), Offset(to, y), thickness, StrokeCap.Round)
        // The played part gathers light toward the head instead of lying flat.
        drawLine(
            Brush.horizontalGradient(
                listOf(Color.White.copy(alpha = breath * .62f), Color.White.copy(alpha = breath)),
                startX = from,
                endX = head,
            ),
            Offset(from, y),
            Offset(head, y),
            thickness,
            StrokeCap.Round,
        )
        // The bloom under the head, and the wider halo while it is held.
        if (bloom > 0f) drawCircle(Color.White.copy(alpha = .13f * bloom), knob + 9.dp.toPx(), Offset(head, y))
        if (grab > 0f) drawCircle(Color.White.copy(alpha = .18f * grab), knob + 10.dp.toPx() * grab, Offset(head, y))
        drawCircle(Color.White.copy(alpha = breath), knob, Offset(head, y))
    }
}

// --------------------------------------------------------------- media --

@Composable
fun Cover(url: String?, modifier: Modifier, radius: Dp = 14.dp, motion: Boolean = false, sharedKey: String? = null) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(radius)
    Box(modifier.sharedCover(sharedKey, shape).clip(shape).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            Icon(Icons.Rounded.MusicNote, null, tint = c.onSurfaceVariant, modifier = Modifier.fillMaxSize(.4f))
        } else {
            AsyncImage(url, null, Modifier.fillMaxSize().kenBurns(motion), contentScale = ContentScale.Crop)
        }
    }
}

/** Cover card on a pane of glass tinted by its cover, with a weighted press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverCard(
    imageUrl: String?,
    title: String,
    subtitle: String,
    key: String,
    circle: Boolean = false,
    /** Shared-element key of the cover, so it travels to the page it opens. */
    shared: String? = null,
    /** Held down: the card's menu, as the rows in the lists have. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    // A pane of glass tinted by the cover; the text stays in the theme's ink on it.
    val pane = if (circle) Color.Transparent else tileColor(imageUrl, key).copy(alpha = if (c.isDark) .3f else .36f)
    val ink = c.onSurface
    Column(
        Modifier.carouselDepth().width(156.dp).weightedPress(source, 16.dp)
            .then(if (circle) Modifier.clip(RoundedCornerShape(16.dp)) else Modifier.glassPane(RoundedCornerShape(16.dp), pane, c.isDark))
            .combinedClickable(
                source, null,
                onClick = {
                    haptics.tick()
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptics.press()
                        it()
                    }
                },
            )
            .padding(bottom = if (circle) 0.dp else 10.dp),
    ) {
        Cover(imageUrl, Modifier.size(156.dp), if (circle) 78.dp else 14.dp, sharedKey = shared)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = if (circle) 0.dp else 10.dp)) {
            val align = if (circle) TextAlign.Center else null
            Text(
                title, Modifier.fillMaxWidth(), textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, color = ink,
            )
            Text(
                subtitle, Modifier.fillMaxWidth(), textAlign = align, color = ink.copy(alpha = .68f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
            )
        }
    }
}

/**
 * Horizontal row that bleeds to the screen edges and snaps cards to the left margin.
 * When [firstKey] changes (items arrived at the front), the row returns to the start;
 * a keyed LazyRow would otherwise stay anchored on the old first card.
 */
@Composable
fun Carousel(state: LazyListState, firstKey: Any? = null, content: LazyListScope.() -> Unit) {
    // Plain holder, not state: updated during composition without recomposing.
    val shownFirst = remember { arrayOf(firstKey) }
    if (firstKey != shownFirst[0]) {
        shownFirst[0] = firstKey
        if (!state.isScrollInProgress) state.requestScrollToItem(0)
    }
    val bleed = 24.dp
    LazyRow(
        Modifier.layout { m, cs ->
            val p = bleed.roundToPx()
            val placeable = m.measure(cs.copy(minWidth = cs.maxWidth + 2 * p, maxWidth = cs.maxWidth + 2 * p))
            layout(cs.maxWidth, placeable.height) { placeable.place(-p, 0) }
        },
        state = state,
        contentPadding = PaddingValues(horizontal = bleed),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
        content = content,
    )
}

fun durationLabel(ms: Long): String =
    if (ms <= 0) "" else (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

/** How far (fraction of the row) a swipe must go to add to the queue. */
private const val SWIPE_THRESHOLD = .28f

/**
 * A track: cover (or its number in albums), title, artists, duration.
 * Swiping it right reveals a queue icon and, past the threshold, calls
 * [onSwipe] (add to queue); the row springs back either way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    number: Int? = null,
    playing: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onSwipe: (() -> Unit)? = null,
    /** Who added it, in playlists several people add to. */
    addedBy: UserFace? = null,
) {
    val c = MaterialTheme.colorScheme
    val press = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    var armed by remember { mutableStateOf(false) }
    // Read only where drawn or on the playing row: a colour change then
    // redraws the swipe wash and recomposes one row, not every row.
    val accent = dev.nexfy.app.ui.theme.accentState()
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        if (onSwipe != null) {
            // What the swipe uncovers: the queue icon, filling in once armed.
            Box(
                Modifier.matchParentSize().clip(RoundedCornerShape(16.dp))
                    .drawBehind { drawRect(accent.value.copy(alpha = (offset.value / width * 2.2f).coerceIn(0f, .9f))) }
                    .padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.PlaylistAdd, "Adicionar à fila",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp).graphicsLayer {
                        val p = (offset.value / (width * SWIPE_THRESHOLD)).coerceIn(0f, 1f)
                        alpha = p
                        scaleX = .7f + .3f * p
                        scaleY = scaleX
                    },
                )
            }
        }
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { translationX = offset.value }
            .then(
                if (onSwipe == null) {
                    Modifier
                } else {
                    Modifier.draggable(
                        rememberDraggableState { d ->
                            scope.snapNow(offset, (offset.value + d).coerceIn(0f, width * .6f))
                            val now = offset.value > width * SWIPE_THRESHOLD
                            if (now != armed) {
                                armed = now
                                haptics.tick()
                            }
                        },
                        Orientation.Horizontal,
                        onDragStopped = {
                            if (armed) {
                                haptics.press()
                                onSwipe()
                            }
                            armed = false
                            offset.animateTo(0f, Motion.settle(320f))
                        },
                    )
                },
            )
            .weightedPress(press, 16.dp).clip(RoundedCornerShape(16.dp))
            .background(if (offset.value > 0f) c.background else Color.Transparent)
            .combinedClickable(press, null, onClick = onClick, onLongClick = onLongClick)
            .alpha(if (track.playable) 1f else .4f)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                if (playing) {
                    PlayingBars(accent.value, Modifier.size(16.dp))
                } else {
                    Text("$number", color = c.onSurfaceVariant, fontSize = 15.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            Box(contentAlignment = Alignment.Center) {
                Cover(track.imageUrl, Modifier.size(56.dp), 12.dp)
                if (playing) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(.45f)))
                    PlayingBars(Color.White, Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) accent.value else c.onSurface,
            )
            val subtitle = buildString {
                if (track.explicit) append("🅴 ")
                append(track.artists)
                durationLabel(track.durationMs.toLong()).takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
            }
            Text(subtitle, color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (addedBy != null) {
            Spacer(Modifier.width(10.dp))
            UserAvatar(addedBy, Modifier.padding(end = 6.dp).size(26.dp))
        }
    }
    }
}

/** A user's photo in a circle, or their initial when they have none. */
@Composable
fun UserAvatar(user: UserFace, modifier: Modifier = Modifier, description: String = "Adicionada por ${user.name}") {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.clip(CircleShape).background(c.surfaceVariant).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (user.imageUrl.isNullOrBlank()) {
            Text(user.name.take(1).uppercase(), color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else {
            AsyncImage(user.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(top = 18.dp, bottom = 10.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = c.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, color = c.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

// ------------------------------------------------------------- headers --

/** 0 = large title fully shown, 1 = scrolled past it. */
@Composable
fun rememberTitleCollapse(list: LazyListState): State<Float> {
    val px = with(LocalDensity.current) { 56.dp.toPx() }
    return remember(list) {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / px).coerceIn(0f, 1f)
        }
    }
}

/** Big bold tab title that fades and shrinks toward its start as the list scrolls. */
@Composable
fun LargeTitle(text: String, collapse: State<Float>, modifier: Modifier = Modifier) = Text(
    text,
    modifier.graphicsLayer {
        val v = collapse.value.coerceIn(0f, 1f)
        alpha = 1f - v
        val s = 1f - .12f * v
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin(0f, .5f)
    },
    fontSize = 34.sp,
    lineHeight = 40.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-.5).sp,
)

val TopBarHeight = 64.dp

/** 0 = collection header fully visible, 1 = scrolled past it. */
@Composable
fun collapseOf(list: LazyListState): State<Float> {
    val fallback = with(LocalDensity.current) { 400.dp.toPx() }
    return remember(list) {
        derivedStateOf {
            val px = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size?.toFloat()?.takeIf { it > 0f } ?: fallback
            if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / (px * .8f)).coerceIn(0f, 1f)
        }
    }
}

/**
 * Full-screen backdrop for collections: living artwork fading into the
 * background downwards and as the list scrolls. On a light theme it is a
 * pale wash, so the header reads in [headerInk].
 */
@Composable
fun CollectionBackdrop(art: String?, collapse: State<Float>, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val pale = !MaterialTheme.colorScheme.isDark
    Box(modifier) {
        // Hidden once the list covers it: no layer is drawn at alpha 0.
        LivingArtwork(art, Modifier.fillMaxSize().graphicsLayer { alpha = (1f - collapse.value).coerceIn(0f, 1f) }, dim = .25f, pale = pale)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, .30f to Color.Transparent, .55f to bg.copy(alpha = .85f), .75f to bg),
            ),
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = collapse.value.coerceIn(0f, 1f) }.background(bg))
    }
}

/** Text over a [CollectionBackdrop]: white on the dark art, the theme's ink on the pale wash. */
@Composable
fun headerInk(): Color = if (MaterialTheme.colorScheme.isDark) Color.White else MaterialTheme.colorScheme.onBackground

/** Detail top bar: back always visible; title and play appear once the header scrolls away. */
@Composable
fun DetailTopBar(
    title: String,
    collapse: State<Float>,
    onBack: () -> Unit,
    /** The page's own glass backdrop (see [glassSource]): the bar is liquid glass over it. */
    glass: HazeState? = null,
    onPlay: (() -> Unit)?,
) = CompositionLocalProvider(LocalHaze provides (glass ?: LocalHaze.current)) {
    val c = MaterialTheme.colorScheme
    // Appears only once the header has faded out, so the two never overlap.
    val bar by remember { derivedStateOf { ((collapse.value - .7f) / .3f).coerceIn(0f, 1f) } }
    // On a page with its own glass backdrop the bar is liquid glass with a
    // rounded lower edge; elsewhere it stays an opaque band.
    val liquid = glass != null
    val edge = if (liquid) 0.dp else 12.dp
    Box(Modifier.fillMaxWidth()) {
        if (liquid && bar > 0f) {
            Box(
                Modifier.matchParentSize().graphicsLayer { alpha = bar }
                    .liquidGlass(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp), c.isDark, GlassLevel.Regular),
            )
        }
        Row(
            Modifier.fillMaxWidth()
                // Opaque behind the status bar too, with a soft edge below instead of a hard line.
                .then(
                    if (liquid) Modifier else Modifier.drawBehind {
                        val solid = size.height - edge.toPx()
                        drawRect(c.background.copy(alpha = bar), size = Size(size.width, solid))
                        drawRect(
                            Brush.verticalGradient(
                                listOf(c.background.copy(alpha = bar), Color.Transparent),
                                startY = solid,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, solid),
                            size = Size(size.width, edge.toPx()),
                        )
                    },
                )
                .statusBarsPadding()
                .padding(bottom = edge)
                .height(TopBarHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", onBack, light = bar > .5f, liquid = liquid)
            Text(
                title, Modifier.weight(1f).padding(horizontal = 12.dp).graphicsLayer { alpha = bar },
                style = MaterialTheme.typography.titleMedium, color = c.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (bar > 0f && onPlay != null) {
                Box(
                    Modifier.size(48.dp).graphicsLayer { alpha = bar }.clip(CircleShape).clickable(enabled = bar > .5f, onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayCircle, "Tocar", tint = c.onBackground, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

/**
 * Pull down at the top of a list to close the screen: it follows the finger
 * (scale and fade in proportion) and closes past 25 % or on a fling.
 */
@Composable
fun Modifier.dragToDismiss(dismiss: Animatable<Float, AnimationVector1D>, onBack: () -> Unit): Modifier {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var heightPx by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) { dismiss.snapTo(0f) }
    val connection = remember(dismiss) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (dismiss.value > 0f && available.y < 0f) {
                    val d = (available.y / heightPx).coerceAtLeast(-dismiss.value)
                    scope.snapNow(dismiss, (dismiss.value + d).coerceIn(0f, 1f))
                    Offset(0f, d * heightPx)
                } else {
                    Offset.Zero
                }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    scope.snapNow(dismiss, (dismiss.value + available.y / heightPx).coerceIn(0f, 1f))
                    available
                } else {
                    Offset.Zero
                }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dismiss.value <= 0f) return Velocity.Zero
                if (dismiss.value > .25f || available.y > Motion.FlingVelocity) {
                    haptics.tick()
                    onBack()
                } else {
                    dismiss.animateTo(0f, Motion.settle(), initialVelocity = available.y / heightPx)
                }
                return available
            }
        }
    }
    return onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
        .nestedScroll(connection)
        .graphicsLayer {
            val d = dismiss.value.coerceIn(0f, 1f)
            translationY = d * heightPx * .5f
            val s = 1f - .1f * d
            scaleX = s
            scaleY = s
            alpha = 1f - .4f * d
        }
}

/** Milliseconds for the draw-time animations above; the same clock everywhere. */
private fun now() = android.os.SystemClock.elapsedRealtime()
