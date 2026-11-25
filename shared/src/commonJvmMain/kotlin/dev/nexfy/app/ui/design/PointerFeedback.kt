package dev.nexfy.app.ui.design

import androidx.compose.animation.VectorConverter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * What a surface does under a mouse, all in one node that draws it: a band
 * of [ground] (the playing row's tint) or [lift] while the pointer is over
 * it, and the press that sinks it to 95 % and darkens it, as
 * [weightedPress] does. [onHover] tells the composition when the pointer
 * comes and goes, for what shows only then.
 *
 * Nothing here costs anything until the pointer arrives: no layer (the press
 * scales the drawing itself), no coroutine (the colour and the press animate
 * in the node's own scope, started by the event). A long list composes a
 * few dozen rows in a frame of a fast scroll, and the modifiers this stands
 * for started a coroutine each and took a layer each, per row.
 *
 * For a pointer only: a finger's press in a list waits to know it is not a
 * scroll, which [weightedPress] gets from the clickable's interactions.
 */
fun Modifier.pointerFeedback(
    corner: Dp,
    ground: Color = Color.Transparent,
    lift: Color = Color.Transparent,
    onHover: ((Boolean) -> Unit)? = null,
): Modifier = this then PointerFeedbackElement(corner, ground, lift, onHover)

private class PointerFeedbackElement(
    val corner: Dp,
    val ground: Color,
    val lift: Color,
    val onHover: ((Boolean) -> Unit)?,
) : ModifierNodeElement<PointerFeedbackNode>() {
    override fun create() = PointerFeedbackNode(corner, ground, lift, onHover)

    override fun update(node: PointerFeedbackNode) = node.update(corner, ground, lift, onHover)

    // The hover callback is a new lambda on every composition that only ever
    // sets the same state, so it does not make this a different element.
    override fun equals(other: Any?) = other is PointerFeedbackElement &&
        other.corner == corner && other.ground == ground && other.lift == lift

    override fun hashCode() = (corner.hashCode() * 31 + ground.hashCode()) * 31 + lift.hashCode()
}

private class PointerFeedbackNode(
    private var corner: Dp,
    private var ground: Color,
    private var lift: Color,
    private var onHover: ((Boolean) -> Unit)?,
) : Modifier.Node(), DrawModifierNode, PointerInputModifierNode {
    private var hovered = false
    private var pressed = false

    /** Made the first time the band has somewhere to go; until then it stands at its target. */
    private var band: Animatable<Color, AnimationVector4D>? = null
    private var press: Animatable<Float, AnimationVector1D>? = null

    private fun target() = if (ground.alpha > 0f) ground else if (hovered) lift else Color.Transparent

    fun update(corner: Dp, ground: Color, lift: Color, onHover: ((Boolean) -> Unit)?) {
        this.onHover = onHover
        if (corner != this.corner) {
            this.corner = corner
            invalidateDraw()
        }
        if (ground == this.ground && lift == this.lift) return
        val from = target()
        this.ground = ground
        this.lift = lift
        moveBand(from)
    }

    /** The band from [from], where it stood, to where it should be now. */
    private fun moveBand(from: Color) {
        if (!isAttached) return
        val to = target()
        // Made here, it has not been read by the drawing yet, which is told to.
        val anim = band ?: Animatable(from, Color.VectorConverter(to.colorSpace)).also {
            band = it
            invalidateDraw()
        }
        if (anim.targetValue == to) return
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { anim.animateTo(to, spring()) }
    }

    private fun setHovered(now: Boolean) {
        if (now == hovered) return
        val from = target()
        hovered = now
        onHover?.invoke(now)
        moveBand(from)
    }

    private fun setPressed(now: Boolean) {
        if (now == pressed || !isAttached) return
        pressed = now
        val anim = press ?: Animatable(0f).also {
            press = it
            invalidateDraw()
        }
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            anim.animateTo(if (now) 1f else 0f, if (now) Motion.enter(Motion.PressDown) else Motion.press())
        }
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        when (pointerEvent.type) {
            PointerEventType.Enter -> setHovered(true)
            PointerEventType.Exit -> {
                setHovered(false)
                setPressed(false)
            }
            PointerEventType.Press -> if (pointerEvent.buttons.isPrimaryPressed) setPressed(true)
            PointerEventType.Release -> setPressed(false)
            // A press that turned into a drag is no longer one.
            PointerEventType.Move -> if (pressed && pointerEvent.changes.any { it.isConsumed }) setPressed(false)
        }
    }

    override fun onCancelPointerInput() {
        setHovered(false)
        setPressed(false)
    }

    override fun onDetach() {
        // Scrolled away and back, it is a fresh one, not a hovered or pressed one.
        if (hovered) onHover?.invoke(false)
        hovered = false
        pressed = false
        band = null
        press = null
    }

    override fun ContentDrawScope.draw() {
        val radius = CornerRadius(corner.toPx())
        val color = band?.value ?: target()
        if (color.alpha > 0f) drawRoundRect(color, cornerRadius = radius)
        val p = press?.value?.coerceIn(0f, 1f) ?: 0f
        if (p <= 0f) {
            drawContent()
            return
        }
        val content = this
        scale(1f - .05f * p) {
            content.drawContent()
            drawRoundRect(Color.Black.copy(alpha = .12f * p), cornerRadius = radius)
        }
    }
}
