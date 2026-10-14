package dev.nexfy.app.ui.design

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
import androidx.compose.ui.draw.drawWithContent
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
 * Liquid glass: a real blur of what is behind (Haze, RenderEffect on
 * API 31+), a light tint with grain, then lens edges: a specular rim
 * brighter on top, a soft inner shadow at the bottom and a faint refraction
 * band along the border.
 */
@Composable
fun Modifier.liquidGlass(shape: Shape, dark: Boolean): Modifier {
    val haze = LocalHaze.current
    val tint = if (dark) Color(0xFF1C1C20).copy(alpha = .42f) else Color.White.copy(alpha = .5f)
    val base = clip(shape)
    val blurred = if (haze != null) {
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
