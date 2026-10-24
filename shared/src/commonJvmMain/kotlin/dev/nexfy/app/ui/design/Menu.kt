package dev.nexfy.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.nexfy.app.ui.BackGesture
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** The least a menu keeps between itself and the window's edges. */
private val MenuEdge = 8.dp

/** The corner and the lift of the app's own menus, which this is one of. */
private val MenuShape = RoundedCornerShape(18.dp)
private val MenuLift = 18.dp

/**
 * A menu where it was asked for: at [anchor], a point in **window** pixels
 * (a right-click, a `⋯` button), rather than rising from an edge of the
 * screen the way a sheet does for a thumb.
 *
 * It hangs down and to the right of that point, flips to the other side of
 * it where that side has no room, and is pushed in from the edge where
 * neither side has — so the whole menu is always in view, whichever corner
 * it was opened near. It leaves on Escape (the window's back key, so the
 * page under it stays where it was), on a click outside, on a window resize
 * and when the content it pointed at scrolls away (see [Menus]). Arrows walk
 * it, Enter chooses (every row is [androidx.compose.foundation.clickable],
 * which answers Enter and Space on its own), Home and End jump to the ends.
 */
@Composable
fun GlassMenu(anchor: Offset, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val window = LocalWindowInfo.current
    val density = LocalDensity.current
    // Count first: a scroller only reports movement while a menu is open.
    DisposableEffect(Unit) {
        Menus.open++
        onDispose { Menus.open-- }
    }
    LaunchedEffect(Unit) {
        val size = window.containerSize
        val moved = Menus.moved
        // Pinned to a point, a menu cannot survive the window under it
        // changing shape or sliding away: in both cases the row it was asked
        // from is no longer where it was, and a menu pointing at the wrong
        // row is worse than no menu.
        snapshotFlow { window.containerSize to Menus.moved }.first { (now, m) -> now != size || m != moved }
        dismiss()
    }
    // Esc on a computer and the phone's back gesture reach the innermost
    // layer first, which while this is up is this.
    BackGesture { dismiss() }

    val edge = with(density) { MenuEdge.roundToPx() }
    // Written while the menu is placed, read only while it is drawn.
    var origin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val position = remember(anchor, edge) { MenuAtPoint(anchor, edge) { origin = it } }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.enter(Motion.Short)) }
    val tall = with(density) { (window.containerSize.height - edge * 2).coerceAtLeast(0).toDp() }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Popup(
        popupPositionProvider = position,
        onDismissRequest = dismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        // The menu itself takes the focus, so the arrows reach it at once
        // while no row is yet lit: the first Down is what picks one.
        LaunchedEffect(Unit) { focus.requestFocus() }
        CompositionLocalProvider(LocalMenuDense provides true) {
            Column(
                Modifier
                    .graphicsLayer {
                        val v = appear.value
                        alpha = v
                        scaleX = .96f + .04f * v
                        scaleY = scaleX
                        // It grows out of the point it was asked from, which is
                        // whichever corner the flip above settled on.
                        transformOrigin = origin
                    }
                    .shadow(MenuLift, MenuShape)
                    .clip(MenuShape)
                    .background(glassMenuColor())
                    .border(glassMenuEdge(), MenuShape)
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            // The rows in the order they were written, which in
                            // a column is the order they are read; the
                            // geometric walk is asked only where that one
                            // found nowhere to go.
                            Key.DirectionDown -> {
                                if (!focusManager.moveFocus(FocusDirection.Next)) focusManager.moveFocus(FocusDirection.Down)
                                true
                            }
                            Key.DirectionUp -> {
                                if (!focusManager.moveFocus(FocusDirection.Previous)) focusManager.moveFocus(FocusDirection.Up)
                                true
                            }
                            // From the menu itself, forward lands on the first
                            // row and back wraps round to the last.
                            Key.MoveHome -> {
                                focus.requestFocus()
                                focusManager.moveFocus(FocusDirection.Next)
                                true
                            }
                            Key.MoveEnd -> {
                                focus.requestFocus()
                                focusManager.moveFocus(FocusDirection.Previous)
                                true
                            }
                            else -> false
                        }
                    }
                    // Fits its longest row, within reason; a menu longer than
                    // the window scrolls rather than hanging out of it, and
                    // the keyboard walk scrolls the row it reaches into view.
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 224.dp, max = 340.dp)
                    .heightIn(max = tall)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                content = content,
            )
        }
    }
}

/**
 * Down and to the right of the point, flipped where that does not fit,
 * clamped where nothing does. Compose asks for the position once the menu
 * has been measured and before it is drawn, so unlike a measure-then-move
 * popover there is never a frame in the wrong place to hide.
 */
private class MenuAtPoint(
    private val anchor: Offset,
    private val edge: Int,
    private val onPlaced: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val px = anchor.x.roundToInt()
        val py = anchor.y.roundToInt()
        val roomRight = windowSize.width - px - edge >= popupContentSize.width
        val roomLeft = px - edge >= popupContentSize.width
        // A menu opens the way the text reads and turns back only when it
        // must: flipping is what keeps it whole, where shrinking or
        // scrolling it would cost the user a row.
        val left = if (layoutDirection == LayoutDirection.Rtl) roomLeft else !roomRight && roomLeft
        val above = windowSize.height - py - edge < popupContentSize.height && py - edge >= popupContentSize.height
        val x = if (left) px - popupContentSize.width else px
        val y = if (above) py - popupContentSize.height else py
        onPlaced(TransformOrigin(if (left) 1f else 0f, if (above) 1f else 0f))
        // Neither side had room — a menu taller than the window, a point in a
        // corner — so it is pushed in off the edge instead of left hanging.
        return IntOffset(
            x.coerceIn(edge, (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge)),
            y.coerceIn(edge, (windowSize.height - popupContentSize.height - edge).coerceAtLeast(edge)),
        )
    }
}

/**
 * The one thing an anchored menu cannot see for itself: that what it was
 * opened from has moved. A list scrolling under a standing menu leaves it
 * pointing at a row that is somewhere else now, and nothing inside a popup
 * is told. So a scroller wears [closesMenus], or whatever moves content
 * says [contentMoved], and open menus close.
 */
object Menus {
    /** How many anchored menus are up; a scroller reports nothing while none is. */
    internal var open by mutableIntStateOf(0)

    internal var moved by mutableIntStateOf(0)

    fun contentMoved() {
        if (open > 0) moved++
    }
}

/** A scrollable that closes the menus standing over it when it moves. */
@Composable
fun Modifier.closesMenus(): Modifier {
    val connection = remember {
        object : NestedScrollConnection {
            // After the fact and consumed only: a flick the list refused
            // (it was already at the end) has moved nothing.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed != Offset.Zero) Menus.contentMoved()
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection).pointerInput(Unit) {
        // The wheel as well, watched on the way in and never consumed, so
        // the list still scrolls: a wheel tick is the one way a computer
        // moves a list, and it does not always come through nested scroll.
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Scroll) Menus.contentMoved()
            }
        }
    }
}

/**
 * Calls [onMenu] with the point the right button was pressed at, in window
 * pixels as [GlassMenu] wants it, and eats that press so the surface under
 * it does not act as well. A pointer event carries a position local to the
 * surface that caught it, which is why the place is needed to translate it.
 *
 * Read on [PointerEventPass.Initial], before the surface's own click
 * gesture is offered the press. A touchscreen reports no second button, so
 * on a phone this never fires.
 */
@Composable
fun Modifier.secondaryClickAt(onMenu: (Offset) -> Unit): Modifier {
    val at = rememberUpdatedState(onMenu)
    // Not state: where the surface sits changes on every scroll, and no one
    // needs to recompose for it.
    val place = remember { Place() }
    return onGloballyPositioned { place.coords = it }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    val local = event.changes.firstOrNull()?.position ?: Offset.Zero
                    event.changes.forEach { it.consume() }
                    val coords = place.coords
                    at.value(if (coords != null && coords.isAttached) coords.localToWindow(local) else local)
                }
            }
        }
    }
}

private class Place {
    var coords: LayoutCoordinates? = null
}

/**
 * Whether the rows being drawn are a pointer menu's or a sheet's. A menu at
 * the pointer is read at a desk and sized like every other menu in the app;
 * the same list of actions in a sheet is reached by a thumb and keeps the
 * sheet's room. One list of actions, so each row asks here rather than the
 * screens passing a flag down.
 */
val LocalMenuDense = staticCompositionLocalOf { false }

/** Between groups of actions: what follows is of a different kind, or undoes something. */
@Composable
fun MenuSeparator() = HorizontalDivider(
    Modifier.padding(horizontal = if (LocalMenuDense.current) 0.dp else 24.dp, vertical = 6.dp),
    color = MaterialTheme.colorScheme.outlineVariant,
)
