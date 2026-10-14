package dev.nexfy.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.PillButton
import dev.nexfy.app.ui.design.RoundIconButton
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.springPress
import dev.nexfy.app.ui.theme.accentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.nexfy_core.EqualizerPreset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private val BandLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
private const val Bands = 10
private const val MaxDb = 12f

/** The core takes at most this many updates a second while a handle moves. */
private const val SendIntervalMs = 33L

/**
 * The 10-band equalizer: a curve whose handles drag the bands live, presets
 * (built in and the user's), a preamp and an on/off switch. Only this
 * phone's sound changes; other Connect devices play their own.
 */
@Composable
fun EqualizerScreen(actions: AppActions, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val core = actions.core
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val accent = accentColor()
    val initial = remember { core.equalizer() }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var preamp by remember { mutableFloatStateOf(initial.preampDb) }
    // What the sound uses; the curve's animatables chase these.
    val gains = remember { initial.gainsDb.padded().toMutableStateList() }
    val shown = remember { gains.map { Animatable(it) } }
    val moves = remember { arrayOfNulls<Job>(Bands) }
    var presets by remember { mutableStateOf(core.equalizerPresets()) }
    var message by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EqualizerPreset?>(null) }
    val selected by remember {
        derivedStateOf { presets.firstOrNull { p -> p.gainsDb.zip(gains).all { (a, b) -> abs(a - b) < .05f } }?.name }
    }

    // Live while dragging, conflated so the core sees the latest values at ~30/s.
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(enabled, preamp, gains.toList()) }.drop(1).conflate().collect { (on, pre, bands) ->
            withContext(Dispatchers.IO) { core.setEqualizer(on, pre, bands) }
            delay(SendIntervalMs)
        }
    }
    // Leaving mid-interval would drop the last change.
    DisposableEffect(Unit) { onDispose { core.setEqualizer(enabled, preamp, gains.toList()) } }

    /** Glide the curve to [target], a band at a time, like a wave. */
    fun applyGains(target: List<Float>) {
        target.padded().forEachIndexed { i, db ->
            gains[i] = db
            moves[i]?.cancel()
            moves[i] = scope.launch {
                if (Motion.reduced) {
                    shown[i].animateTo(db, Motion.standard(Motion.Short))
                } else {
                    delay(i * 18L)
                    shown[i].animateTo(db, spring(dampingRatio = .62f, stiffness = 240f))
                }
            }
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(bottom = LocalBottomSpace.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", { actions.back() }, container = c.background)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .toggleable(enabled, role = Role.Switch) {
                    haptics.tick()
                    enabled = it
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Equalizador", fontSize = 34.sp, style = MaterialTheme.typography.headlineLarge)
                Text(
                    if (!enabled) "Desligado" else selected ?: "Personalizado",
                    color = c.onSurfaceVariant, fontSize = 14.sp,
                )
            }
            Switch(
                enabled, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = c.background),
            )
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceVariant)
                .padding(start = 8.dp, end = 14.dp, top = 16.dp, bottom = 10.dp),
        ) {
            EqualizerCurve(
                gains = gains,
                shown = shown,
                enabled = enabled,
                onChange = { band, db ->
                    moves[band]?.cancel()
                    gains[band] = db
                    scope.launch { shown[band].snapTo(db) }
                },
                onReset = { band ->
                    haptics.tick()
                    applyGains(gains.mapIndexed { i, db -> if (i == band) 0f else db })
                },
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                PresetChip(
                    preset.name,
                    selected = preset.name == selected,
                    onClick = {
                        haptics.tick()
                        applyGains(preset.gainsDb)
                    },
                    onLongClick = if (preset.builtin) null else {
                        {
                            haptics.press()
                            deleting = preset
                        }
                    },
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceVariant).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pré-amplificação", style = MaterialTheme.typography.titleMedium)
                    Text("Ajusta o volume do som equalizado.", color = c.onSurfaceVariant, fontSize = 13.sp)
                }
                Text(dbLabel(preamp), style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = preamp,
                onValueChange = { raw ->
                    val db = snapDb(raw, step = .5f)
                    if (db != preamp) {
                        crossTick(preamp, db, haptics::tick, haptics::press)
                        preamp = db
                    }
                },
                valueRange = -MaxDb..MaxDb,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = c.outline),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Salvar como preset", { naming = true }, filled = false)
            PillButton("Redefinir", {
                haptics.tick()
                preamp = 0f
                applyGains(List(Bands) { 0f })
            }, filled = false)
        }
        message?.let { Text(it, color = accent) }
        Text("Vale para o som deste celular.", color = c.onSurfaceVariant, fontSize = 13.sp)
    }

    if (naming) {
        NameDialog("Salvar preset", selected?.takeIf { s -> presets.none { it.name == s && it.builtin } }.orEmpty(), "Salvar", onDismiss = { naming = false }) { name ->
            naming = false
            message = try {
                core.saveEqualizerPreset(name, gains.toList())
                presets = core.equalizerPresets()
                null
            } catch (e: Exception) {
                e.message ?: "Não deu para salvar o preset"
            }
        }
    }
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Apagar \"${preset.name}\"?") },
            text = { Text("O som atual continua como está.") },
            confirmButton = {
                TextButton({
                    core.deleteEqualizerPreset(preset.name)
                    presets = core.equalizerPresets()
                    deleting = null
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancelar") } },
        )
    }
}

/** Where the chart's parts sit, shared by drawing and touch. */
private class ChartFrame(size: Size, density: Density) {
    val left = with(density) { 34.dp.toPx() }
    val right = size.width
    val top = with(density) { 14.dp.toPx() }
    val bottom = size.height - with(density) { 26.dp.toPx() }
    private val inset = with(density) { 12.dp.toPx() }

    fun x(band: Int) = left + inset + band * (right - left - 2 * inset) / (Bands - 1)
    fun y(db: Float) = top + (MaxDb - db) / (2 * MaxDb) * (bottom - top)

    /** dB per pixel of vertical drag. */
    val dbPerPx get() = 2 * MaxDb / (bottom - top)

    fun nearest(x: Float) = (0 until Bands).minBy { abs(x(it) - x) }
}

/**
 * The hero: a smooth curve through the band handles, a soft accent fill,
 * the dB grid and the frequencies. Drag a handle up or down; double-tap
 * one to flatten it. Off, the curve fades to grey.
 */
@Composable
private fun EqualizerCurve(
    gains: SnapshotStateList<Float>,
    shown: List<Animatable<Float, AnimationVector1D>>,
    enabled: Boolean,
    onChange: (band: Int, db: Float) -> Unit,
    onReset: (band: Int) -> Unit,
    modifier: Modifier,
) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    // Updated, not captured: the gesture handlers below live as long as the chart.
    val haptics by rememberUpdatedState(rememberHaptics())
    val measurer = rememberTextMeasurer()
    val change by rememberUpdatedState(onChange)
    val reset by rememberUpdatedState(onReset)
    var active by remember { mutableIntStateOf(-1) }
    var lastActive by remember { mutableIntStateOf(0) }
    val lit by animateFloatAsState(if (enabled) 1f else 0f, Motion.standard(), label = "lit")
    val grow by animateFloatAsState(if (active >= 0) 1f else 0f, Motion.press(), label = "grow")

    val label = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp)
    val freqs = remember(measurer, c.onSurfaceVariant) { BandLabels.map { measurer.measure(it, label) } }
    val scale = remember(measurer, c.onSurfaceVariant) { listOf(12, 6, 0, -6, -12).map { it to measurer.measure(if (it > 0) "+$it" else "$it", label) } }
    val bubbleStyle = TextStyle(color = c.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { o -> reset(ChartFrame(size.toSize(), this).nearest(o.x)) })
            }
            .pointerInput(Unit) {
                // Relative to where the finger lands, so a touch never makes the band jump.
                var raw = 0f
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        active = ChartFrame(size.toSize(), this).nearest(o.x)
                        lastActive = active
                        raw = gains[active]
                        haptics.tick()
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                ) { pointer, dy ->
                    val band = active
                    if (band < 0) return@detectVerticalDragGestures
                    pointer.consume()
                    raw = (raw - dy * ChartFrame(size.toSize(), this).dbPerPx).coerceIn(-MaxDb, MaxDb)
                    val before = gains[band]
                    val db = snapDb(raw, step = .1f)
                    if (db != before) {
                        crossTick(before, db, haptics::tick, haptics::press)
                        change(band, db)
                    }
                }
            },
    ) {
        val f = ChartFrame(size, this)
        val ink = lerp(c.onSurfaceVariant, accent, lit)
        val grid = c.onSurfaceVariant.copy(alpha = .12f)

        scale.forEach { (db, text) ->
            val y = f.y(db.toFloat())
            drawLine(if (db == 0) c.onSurfaceVariant.copy(alpha = .3f) else grid, Offset(f.left, y), Offset(f.right, y), 1.dp.toPx())
            drawText(text, topLeft = Offset(f.left - 8.dp.toPx() - text.size.width, y - text.size.height / 2f))
        }
        freqs.forEachIndexed { band, text ->
            val x = f.x(band)
            drawLine(grid, Offset(x, f.top), Offset(x, f.bottom), 1.dp.toPx())
            drawText(text, topLeft = Offset(x - text.size.width / 2f, f.bottom + 8.dp.toPx()))
        }

        val points = List(Bands) { Offset(f.x(it), f.y(shown[it].value)) }
        val curve = spline(points, f.left, f.right)
        val fill = Path().apply {
            addPath(curve)
            lineTo(f.right, f.bottom)
            lineTo(f.left, f.bottom)
            close()
        }
        clipRect(f.left, 0f, f.right, f.bottom) {
            drawPath(fill, Brush.verticalGradient(listOf(ink.copy(alpha = .34f), ink.copy(alpha = 0f)), startY = f.top, endY = f.bottom))
        }
        drawPath(curve, ink.copy(alpha = .16f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(curve, ink, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        points.forEachIndexed { band, p ->
            val g = if (band == lastActive) grow else 0f
            val r = (7.dp.toPx()) * (1f + .45f * g)
            drawCircle(lerp(c.surfaceVariant, ink, g), r, p)
            drawCircle(ink, r, p, style = Stroke(2.5.dp.toPx()))
        }

        // The value of the band being dragged, in a pill above its handle.
        if (grow > .01f) {
            val p = points[lastActive]
            val text = measurer.measure(dbLabel(gains[lastActive]), bubbleStyle)
            val pad = 8.dp.toPx()
            val w = text.size.width + 2 * pad
            val h = text.size.height + pad
            val gap = 22.dp.toPx()
            val y = if (p.y - gap - h > 0f) p.y - gap - h else p.y + gap
            val x = (p.x - w / 2f).coerceIn(f.left, f.right - w)
            drawRoundRect(c.primary.copy(alpha = grow), Offset(x, y), Size(w, h), CornerRadius(h / 2f))
            drawText(text, topLeft = Offset(x + pad, y + pad / 2f), alpha = grow)
        }
    }
}

/**
 * A smooth curve through [points] (Catmull-Rom, drawn as cubic Béziers),
 * level at both ends and carried flat out to [left] and [right].
 */
private fun spline(points: List<Offset>, left: Float, right: Float): Path = Path().apply {
    moveTo(left, points.first().y)
    lineTo(points.first().x, points.first().y)
    for (i in 0 until points.lastIndex) {
        val p1 = points[i]
        val p2 = points[i + 1]
        // Mirrored ghosts at the ends keep the tangents there horizontal.
        val p0 = if (i > 0) points[i - 1] else Offset(2 * p1.x - p2.x, p2.y)
        val p3 = if (i + 2 <= points.lastIndex) points[i + 2] else Offset(2 * p2.x - p1.x, p1.y)
        cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
    lineTo(right, points.last().y)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(name: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) accent.copy(alpha = .18f) else c.surfaceVariant, Motion.standard(), label = "bg")
    val edge by animateColorAsState(if (selected) accent else c.surfaceVariant, Motion.standard(), label = "edge")
    val fg by animateColorAsState(if (selected) accent else c.onSurface, Motion.standard(), label = "fg")
    Text(
        name,
        color = fg,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier.springPress(source).clip(CircleShape).background(bg).border(1.dp, edge, CircleShape)
            .combinedClickable(source, null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Ten bands, whatever came in. */
private fun List<Float>.padded(): List<Float> = List(Bands) { getOrElse(it) { 0f } }

/** Rounded to [step], with a small pull toward 0 dB so flat is easy to find. */
private fun snapDb(raw: Float, step: Float): Float =
    if (abs(raw) < .35f) 0f else ((raw / step).roundToInt() * step).coerceIn(-MaxDb, MaxDb)

/** A firm tick on landing at 0 dB, a light one on each whole dB crossed. */
private fun crossTick(before: Float, after: Float, tick: () -> Unit, firm: () -> Unit) {
    when {
        after == 0f -> firm()
        before == 0f && abs(after) < 1f -> Unit
        floor(before) != floor(after) -> tick()
    }
}

/** "+3,5 dB", "-2 dB", "0 dB". */
private fun dbLabel(db: Float): String {
    val tenths = (db * 10).roundToInt()
    if (tenths == 0) return "0 dB"
    val text = if (tenths % 10 == 0) "%+d".format(Locale.ROOT, tenths / 10) else "%+.1f".format(Locale.ROOT, tenths / 10f).replace('.', ',')
    return "$text dB"
}
