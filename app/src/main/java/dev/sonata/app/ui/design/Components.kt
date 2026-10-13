package dev.sonata.app.ui.design

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
import dev.sonata.app.ui.theme.isDark
import kotlinx.coroutines.launch
import uniffi.sonata_core.Track

// ------------------------------------------------------------- haptics --

/** Light tick for toggles and drags, stronger for play/pause and skips. */
class Haptics(private val feedback: HapticFeedback) {
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun press() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
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
 * Segmented choice whose highlight stretches between options: its leading
 * edge springs fast, the trailing one lags, then it settles.
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
    Box(modifier.clip(CircleShape).background(c.surfaceVariant).padding(4.dp)) {
        Box(
            Modifier.matchParentSize().drawBehind {
                val w = size.width / n
                val l = left.coerceIn(-0.2f, n.toFloat()) * w
                val r = right.coerceIn(0f, n + 0.2f) * w
                if (r > l) {
                    drawRoundRect(c.primary, Offset(l, 0f), Size(r - l, size.height), CornerRadius(size.height / 2))
                }
            },
        )
        Row {
            options.forEach { option ->
                val source = remember { MutableInteractionSource() }
                val on = option == selected
                val fg by animateColorAsState(if (on) c.onPrimary else c.onSurfaceVariant, label = "fg")
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
fun GlassIcon(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true, light: Boolean = false) {
    val tint = if (light) MaterialTheme.colorScheme.onBackground else Color.White
    GlassBox(Modifier.size(48.dp), CircleShape, dark = !light || MaterialTheme.colorScheme.isDark) {
        Box(
            Modifier.matchParentSize().clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = tint.copy(alpha = if (enabled) 1f else .4f))
        }
    }
}

/** Thin progress slider: a 4 dp track that thickens while dragging. [value] is 0..1. */
@Composable
fun ThinSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    var dragging by remember { mutableStateOf(false) }
    val thick by animateFloatAsState(if (dragging) 1f else 0f, Motion.settle(), label = "thick")
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val track = RoundedCornerShape(50)
    Box(
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
        contentAlignment = Alignment.CenterStart,
    ) {
        val height: Dp = 4.dp + 4.dp * thick.coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(height).clip(track).background(Color.White.copy(alpha = .22f)))
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(height).clip(track).background(Color.White.copy(alpha = .92f)))
    }
}

// --------------------------------------------------------------- media --

@Composable
fun Cover(url: String?, modifier: Modifier, radius: Dp = 14.dp, motion: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(radius)).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            Icon(Icons.Rounded.MusicNote, null, tint = c.onSurfaceVariant, modifier = Modifier.fillMaxSize(.4f))
        } else {
            AsyncImage(url, null, Modifier.fillMaxSize().kenBurns(motion), contentScale = ContentScale.Crop)
        }
    }
}

/** Flat cover card with a weighted press and a soft tint of its cover behind the text. */
@Composable
fun CoverCard(
    imageUrl: String?,
    title: String,
    subtitle: String,
    key: String,
    circle: Boolean = false,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val tile = if (circle) Color.Transparent else tileColor(imageUrl, key)
    val ink = if (circle) c.onSurface else contentFor(tile)
    Column(
        Modifier.width(156.dp).weightedPress(source, 16.dp).clip(RoundedCornerShape(16.dp)).background(tile)
            .clickable(source, null) {
                haptics.tick()
                onClick()
            }
            .padding(bottom = if (circle) 0.dp else 10.dp),
    ) {
        Cover(imageUrl, Modifier.size(156.dp), if (circle) 78.dp else 14.dp)
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

/** Horizontal row that bleeds to the screen edges and snaps cards to the left margin. */
@Composable
fun Carousel(state: LazyListState, content: LazyListScope.() -> Unit) {
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
) {
    val c = MaterialTheme.colorScheme
    val press = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    var armed by remember { mutableStateOf(false) }
    val accent = dev.sonata.app.ui.theme.accentColor()
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        if (onSwipe != null) {
            // What the swipe uncovers: the queue icon, filling in once armed.
            Box(
                Modifier.matchParentSize().clip(RoundedCornerShape(16.dp))
                    .drawBehind { drawRect(accent.copy(alpha = (offset.value / width * 2.2f).coerceIn(0f, .9f))) }
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
            Text("$number", Modifier.width(36.dp), color = c.onSurfaceVariant, fontSize = 15.sp, textAlign = TextAlign.Center)
        } else {
            Cover(track.imageUrl, Modifier.size(56.dp), 12.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) dev.sonata.app.ui.theme.accentColor() else c.onSurface,
            )
            val subtitle = buildString {
                if (track.explicit) append("🅴 ")
                append(track.artists)
                durationLabel(track.durationMs.toLong()).takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
            }
            Text(subtitle, color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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

/** Full-screen backdrop for collections: living artwork fading into the background downwards and as the list scrolls. */
@Composable
fun CollectionBackdrop(art: String?, collapse: State<Float>, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier) {
        LivingArtwork(art, Modifier.fillMaxSize(), dim = .25f)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, .30f to Color.Transparent, .55f to bg.copy(alpha = .85f), .75f to bg),
            ),
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (collapse.value * .9f).coerceIn(0f, 1f) }.background(bg))
    }
}

/** Detail top bar: back always visible; title and play appear once the header scrolls away. */
@Composable
fun DetailTopBar(title: String, collapse: State<Float>, onBack: () -> Unit, onPlay: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    // Appears only once the header has faded out, so the two never overlap.
    val bar by remember { derivedStateOf { ((collapse.value - .7f) / .3f).coerceIn(0f, 1f) } }
    val edge = 12.dp
    Row(
        Modifier.fillMaxWidth()
            // Opaque behind the status bar too, with a soft edge below instead of a hard line.
            .drawBehind {
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
            }
            .statusBarsPadding()
            .padding(bottom = edge)
            .height(TopBarHeight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", onBack, light = bar > .5f)
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
