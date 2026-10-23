package dev.nexfy.app.ui.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import dev.chrisbanes.haze.HazeInputScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * What the floating glass (dock, mini player) blurs: the main content, and
 * where on the screen it sits.
 *
 * The position is measured here rather than read from Haze's own: Haze
 * fills that in on Android but leaves it at zero on the desktop, and the
 * lens needs the backdrop and the pane of glass measured the same way to
 * line them up.
 */
class GlassBackdrop {
    val haze = HazeState()
    internal var origin by mutableStateOf(Offset.Zero)
}

/** What the floating glass (dock, mini player) blurs: the main content. */
val LocalHaze = staticCompositionLocalOf<GlassBackdrop?> { null }

fun Modifier.glassSource(backdrop: GlassBackdrop?) = if (backdrop != null) {
    onGloballyPositioned { backdrop.origin = it.positionOnScreen() }.hazeSource(backdrop.haze)
} else {
    this
}

/**
 * How much glass: [Subtle] for small controls, [Regular] for bars,
 * [Strong] for the floating pieces that carry the look. [depth] is how far
 * the rim bends what is behind, [band] how wide that rim is, [calm] how much
 * the backdrop's contrast is flattened towards the theme so what sits on the
 * glass always reads.
 */
enum class GlassLevel(val depth: Float, val band: Float, val blur: Float, val calm: Float, val tint: Float) {
    Subtle(depth = 5f, band = 12f, blur = 10f, calm = .45f, tint = .30f),
    Regular(depth = 9f, band = 18f, blur = 14f, calm = .5f, tint = .36f),
    Strong(depth = 15f, band = 26f, blur = 16f, calm = .42f, tint = .32f),
}

/**
 * Liquid glass: what is behind, blurred and bent by a lens at the rim
 * (wherever runtime shaders run; a plain Haze blur before), a light tint, then a specular rim
 * brighter on top and a soft inner shadow at the bottom.
 */
@Composable
fun Modifier.liquidGlass(shape: Shape, dark: Boolean, level: GlassLevel = GlassLevel.Regular): Modifier {
    val backdrop = LocalHaze.current
    val lens = remember { newLens() }
    val tint = if (dark) Color(0xFF16171B).copy(alpha = level.tint) else Color.White.copy(alpha = level.tint + .12f)
    val base = clip(shape)
    val blurred = if (backdrop != null && lens != null) {
        base.refracted(backdrop, lens, shape, tint, dark, level)
    } else if (backdrop != null) {
        base.hazeEffect(
            backdrop.haze,
            HazeStyle(
                backgroundColor = if (dark) Color.Black else Color.White,
                tints = listOf(HazeTint(tint)),
                blurRadius = 26.dp,
                // No grain: a full extra pass every frame for a barely visible texture.
                noiseFactor = 0f,
                fallbackTint = HazeTint((if (dark) Color(0xFF1C1C20) else Color.White).copy(alpha = .9f)),
            ),
        ) {
            // Blur at a third of the resolution: the same frosted look for a ninth of the GPU work.
            inputScale = HazeInputScale.Fixed(.35f)
        }
    } else {
        base.drawWithContent {
            drawRect(if (dark) Color(0xFF1C1C20).copy(alpha = .9f) else Color.White.copy(alpha = .9f))
            drawContent()
        }
    }
    return blurred.drawWithContent {
        val outline = shape.createOutline(size, layoutDirection, this)
        // The sheen goes under what sits on the glass, only the rim over it.
        glassSheen(outline, dark)
        drawContent()
        glassRim(outline, dark)
    }
}

/** Light on the glass body: brighter towards the top, a soft shade at the bottom, a highlight up left. */
internal fun DrawScope.glassSheen(outline: Outline, dark: Boolean) {
    drawOutline(
        outline,
        Brush.verticalGradient(
            0f to Color.White.copy(if (dark) .10f else .35f),
            .35f to Color.Transparent,
            .8f to Color.Transparent,
            1f to Color.Black.copy(if (dark) .22f else .08f),
        ),
    )
    drawOutline(
        outline,
        Brush.radialGradient(
            listOf(Color.White.copy(if (dark) .09f else .2f), Color.Transparent),
            center = Offset(size.width * .3f, 0f),
            radius = size.width * .45f,
        ),
    )
}

/** The glass edge: a specular line, bright on top, faint on the sides, lit again at the bottom. */
internal fun DrawScope.glassRim(outline: Outline, dark: Boolean) {
    drawOutline(
        outline,
        Brush.verticalGradient(
            listOf(
                Color.White.copy(if (dark) .38f else .9f),
                Color.White.copy(.06f),
                Color.White.copy(if (dark) .16f else .4f),
            ),
        ),
        style = Stroke(1.dp.toPx()),
    )
}

/**
 * The backdrop for [liquidGlass] where the lens runs: Haze's recording of
 * the main content, drawn at this element's place into a layer that blurs
 * it and runs the lens. The effect is rebuilt only when the size changes.
 */
@Composable
private fun Modifier.refracted(
    backdrop: GlassBackdrop,
    lens: Lens,
    shape: Shape,
    tint: Color,
    dark: Boolean,
    level: GlassLevel,
): Modifier {
    val layer = rememberGraphicsLayer()
    val origin = remember { mutableStateOf(Offset.Zero) }
    // Plain, not snapshot state: written while drawing.
    val built = remember { arrayOf(Size.Zero) }
    val fallback = (if (dark) Color(0xFF1C1C20) else Color.White).copy(alpha = .9f)
    return onGloballyPositioned { origin.value = it.positionOnScreen() }.drawWithContent {
        val source = backdrop.haze.areas.firstOrNull()?.contentLayer
        if (source == null || size.isEmpty()) {
            drawRect(fallback)
            drawContent()
            return@drawWithContent
        }
        if (built[0] != size) {
            val outline = shape.createOutline(size, layoutDirection, this)
            val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
            layer.renderEffect = lens.effect(
                width = size.width,
                height = size.height,
                radius = radius.coerceAtMost(size.minDimension / 2f),
                band = minOf(size.minDimension / 2f, level.band.dp.toPx()),
                depth = level.depth.dp.toPx(),
                calm = level.calm,
                base = if (dark) .05f else .96f,
                blur = level.blur.dp.toPx(),
            )
            built[0] = size
        }
        val shift = backdrop.origin - origin.value
        layer.record(IntSize(size.width.toInt(), size.height.toInt())) {
            drawRect(if (dark) Color.Black else Color.White)
            translate(shift.x, shift.y) { drawLayer(source) }
        }
        drawLayer(layer)
        drawRect(tint)
        drawContent()
    }
}

/**
 * Glass over artwork (player and collection headers): translucent tint,
 * an optional blurred copy of [backdrop], and a thin top highlight.
 */
@Composable
fun GlassBox(
    modifier: Modifier,
    shape: Shape,
    backdrop: String? = null,
    dark: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    // A touch darker than before on dark art, so the label on it keeps its edge.
    val tint = if (dark) Color.Black.copy(alpha = .16f) else Color.White.copy(alpha = .55f)
    Box(modifier.clip(shape)) {
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(40.dp).graphicsLayer { alpha = .55f },
            )
        }
        Box(
            Modifier.matchParentSize().drawBehind {
                drawRect(tint)
                glassSheen(shape.createOutline(size, layoutDirection, this), dark)
            },
        )
        content()
        // Same lit edge as the liquid glass, over the content.
        Box(Modifier.matchParentSize().drawBehind { glassRim(shape.createOutline(size, layoutDirection, this), dark) })
    }
}

/**
 * A page's background with a faint glow of [accent] (the playing cover's
 * colour) rising from the bottom left. The colour is read while drawing, so
 * a new song only repaints, never recomposes.
 */
fun Modifier.ambientGlow(background: Color, accent: State<Color>, dark: Boolean): Modifier = drawBehind {
    drawRect(background)
    val glow = accent.value.copy(alpha = if (dark) .14f else .09f)
    val center = Offset(size.width * .3f, size.height)
    scale(1f, .55f, pivot = center) {
        drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center, size.width), size.width, center)
    }
}

/**
 * A pane of glass for cards inside lists, where nothing behind can be
 * refracted: [tint] over the page, the glass sheen, and the lit rim over
 * the content.
 */
fun Modifier.glassPane(shape: Shape, tint: Color, dark: Boolean): Modifier = clip(shape).drawWithContent {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, tint)
    glassSheen(outline, dark)
    drawContent()
    glassRim(outline, dark)
}
