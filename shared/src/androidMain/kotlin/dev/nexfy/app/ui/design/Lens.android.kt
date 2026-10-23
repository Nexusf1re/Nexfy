package dev.nexfy.app.ui.design

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

private class AgslLens(private val shader: RuntimeShader) : Lens {
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
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("band", band)
        shader.setFloatUniform("depth", depth)
        shader.setFloatUniform("calm", calm)
        shader.setFloatUniform("base", base)
        // Chained inner first: the content is blurred, then the lens bends it.
        return AndroidRenderEffect.createChainEffect(
            AndroidRenderEffect.createRuntimeShaderEffect(shader, "content"),
            AndroidRenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP),
        ).asComposeRenderEffect()
    }
}

/** RuntimeShader arrived in Android 13; before it the glass is a plain blur. */
actual fun newLens(): Lens? =
    if (Build.VERSION.SDK_INT >= 33) AgslLens(RuntimeShader(LENS)) else null
