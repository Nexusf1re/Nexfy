package dev.nexfy.app.ui.design

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

private class SkslLens(source: String) : Lens {
    private val builder = RuntimeShaderBuilder(RuntimeEffect.makeForShader(source))

    override fun effect(
        width: Float,
        height: Float,
        radius: Float,
        band: Float,
        depth: Float,
        calm: Float,
        base: Float,
        blur: Float,
    ): RenderEffect {
        builder.uniform("size", width, height)
        builder.uniform("radius", radius)
        builder.uniform("band", band)
        builder.uniform("depth", depth)
        builder.uniform("calm", calm)
        builder.uniform("base", base)
        // Android's blur takes a radius, Skia's a sigma; this is the
        // conversion Skia itself uses for a blur of that radius.
        val sigma = blur * .57735f + .5f
        return ImageFilter.makeRuntimeShader(
            builder,
            "content",
            ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP, null, null),
        ).asComposeRenderEffect()
    }
}

/** Every desktop Skia build runs SkSL, so the lens is always on here. */
actual fun newLens(): Lens? = SkslLens(LENS)
