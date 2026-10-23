package dev.nexfy.app.ui.design

import androidx.compose.ui.graphics.RenderEffect

/**
 * The glass lens, as a runtime shader over the blurred backdrop: near the
 * rim it samples towards the centre, so what is behind bends and magnifies
 * at the edge like through thick glass, with the colours split a little.
 * The backdrop is then made a touch more vivid but flatter, pulled towards
 * the theme's base, so icons and text on the glass keep their edge.
 *
 * One source for both platforms: Android's AGSL is a dialect of Skia's
 * SkSL, and this uses only what they share.
 */
internal const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float depth;
uniform float calm;
uniform float base;

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
    float3 col = float3(content.eval(p + shift * 1.15).r, g.g, content.eval(p + shift * 0.85).b);
    float l = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = mix(float3(l), col, 1.2);
    col = mix(col, col * 0.5 + base * 0.5, calm);
    return half4(half3(clamp(col, 0.0, 1.0)), g.a);
}
"""

/**
 * Builds the [LENS] effect for one pane of glass. The sizes are in pixels,
 * and the result is rebuilt only when the pane's size changes.
 */
interface Lens {
    fun effect(
        width: Float,
        height: Float,
        radius: Float,
        band: Float,
        depth: Float,
        calm: Float,
        base: Float,
        blur: Float,
    ): RenderEffect
}

/** Null where the platform has no runtime shaders: Android before 13. */
expect fun newLens(): Lens?
