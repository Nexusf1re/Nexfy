package dev.nexfy.app.ui.design

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import dev.chrisbanes.haze.HazeInputScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/** What the floating glass (dock, mini player) blurs: the main content. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

fun Modifier.glassSource(state: HazeState?) = if (state != null) hazeSource(state) else this

/**
 * The glass lens, as a RenderEffect shader over the blurred backdrop: near
 * the rim it samples towards the centre, so what is behind bends and
 * magnifies at the edge like through thick glass, with the colours split a
 * little. Deep inside it passes the backdrop through untouched.
 */
private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float depth;

half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 s = sign(p - c);
    float2 q = abs(p - c) - (c - radius);
    float2 outer = max(q, 0.0);
    float d = length(outer) + min(max(q.x, q.y), 0.0) - radius;
    float k = 1.0 - clamp(-d / band, 0.0, 1.0);
    k = k * k * k;
    float2 n;
    if (outer.x > 0.0 || outer.y > 0.0) {
        n = normalize(outer + 0.0001) * s;
    } else if (q.x > q.y) {
        n = float2(s.x, 0.0);
    } else {
        n = float2(0.0, s.y);
    }
    float2 shift = -n * k * depth;
    half4 g = content.eval(p + shift);
    half r = content.eval(p + shift * 1.15).r;
    half b = content.eval(p + shift * 0.85).b;
    return half4(r, g.g, b, g.a);
}
"""

/**
 * Liquid glass: what is behind, blurred and bent by a lens at the rim
 * (API 33+; a plain Haze blur before), a light tint, then a specular rim
 * brighter on top and a soft inner shadow at the bottom.
 */
@Composable
fun Modifier.liquidGlass(shape: Shape, dark: Boolean): Modifier {
    val haze = LocalHaze.current
    val tint = if (dark) Color(0xFF1C1C20).copy(alpha = .38f) else Color.White.copy(alpha = .46f)
    val base = clip(shape)
    val blurred = if (haze != null && Build.VERSION.SDK_INT >= 33) {
        base.refracted(haze, shape, tint, dark)
    } else if (haze != null) {
        base.hazeEffect(
            haze,
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
        drawContent()
        val outline = shape.createOutline(size, layoutDirection, this)
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
}

/**
 * The backdrop for [liquidGlass] on API 33+: Haze's recording of the main
 * content, drawn at this element's place into a layer that blurs it and
 * runs the [LENS]. The effect is rebuilt only when the size changes.
 */
@RequiresApi(33)
@Composable
private fun Modifier.refracted(haze: HazeState, shape: Shape, tint: Color, dark: Boolean): Modifier {
    val lens = rememberGraphicsLayer()
    val shader = remember { RuntimeShader(LENS) }
    val origin = remember { mutableStateOf(Offset.Zero) }
    // Plain, not snapshot state: written while drawing.
    val built = remember { arrayOf(Size.Zero) }
    val fallback = (if (dark) Color(0xFF1C1C20) else Color.White).copy(alpha = .9f)
    return onGloballyPositioned { origin.value = it.positionOnScreen() }.drawWithContent {
        val area = haze.areas.firstOrNull()
        val source = area?.contentLayer
        if (source == null || size.isEmpty()) {
            drawRect(fallback)
            drawContent()
            return@drawWithContent
        }
        if (built[0] != size) {
            val outline = shape.createOutline(size, layoutDirection, this)
            val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("radius", radius.coerceAtMost(size.minDimension / 2f))
            shader.setFloatUniform("band", minOf(size.minDimension / 2f, 20.dp.toPx()))
            shader.setFloatUniform("depth", 10.dp.toPx())
            val blur = 14.dp.toPx()
            lens.renderEffect = RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "content"),
                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP),
            ).asComposeRenderEffect()
            built[0] = size
        }
        val shift = area.positionOnScreen - origin.value
        lens.record(IntSize(size.width.toInt(), size.height.toInt())) {
            drawRect(if (dark) Color.Black else Color.White)
            translate(shift.x, shift.y) { drawLayer(source) }
        }
        drawLayer(lens)
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
    val tint = if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .55f)
    val edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .35f else .9f), Color.White.copy(alpha = .04f)))
    Box(modifier.clip(shape)) {
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(40.dp).graphicsLayer { alpha = .55f },
            )
        }
        Box(Modifier.matchParentSize().background(tint).border(1.dp, edge, shape))
        content()
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
