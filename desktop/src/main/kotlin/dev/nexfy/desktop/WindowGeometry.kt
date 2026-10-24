package dev.nexfy.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import coil3.PlatformContext
import dev.nexfy.app.openPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * Where the window was and how big it was, so it opens where it was left
 * instead of wherever the OS drops a new one.
 *
 * The record goes in the app's own settings directory, through the same
 * [openPrefs] store the library's settings use: that writes its file beside
 * the real one and moves it over, so a crash in the middle of a write cannot
 * leave half a geometry behind. It is kept as a single key, because five
 * numbers in five keys could be left disagreeing with each other, and a
 * window is only ever all five at once.
 */
private val prefs by lazy { openPrefs(PlatformContext.INSTANCE, "window") }

private const val KEY = "geometry"

/**
 * Below this the three columns have nowhere to go; the shell drops the right
 * panel on its own before that. A remembered size under it is refused: it
 * would only be fought back up by `minimumSize` on the next frame.
 */
val MinWindowSize = Dimension(940, 620)

/** How much of the window has to land on a screen for a hand to reach it. */
private val Grab = Dimension(160, 80)

/** A drag is a thousand moves; only where the hand stops is worth a file. */
private const val QUIET_MS = 250L

/** The bounds the window had while floating, and whether it was grown over them. */
data class WindowGeometry(val bounds: Rectangle, val maximized: Boolean) {
    val size: DpSize get() = DpSize(bounds.width.dp, bounds.height.dp)
    val position: WindowPosition get() = WindowPosition(bounds.x.dp, bounds.y.dp)
}

/**
 * What was written down last, or null when there is nothing or it no longer
 * suits this computer. Read before the window exists, so the state it is
 * built with is already the right one and nothing has to be moved after.
 */
fun readWindowGeometry(): WindowGeometry? {
    val line = runCatching { prefs.string(KEY) }.getOrNull() ?: return null
    val parts = line.split(',')
    if (parts.size != 5) return null
    val numbers = parts.take(4).mapNotNull { it.trim().toIntOrNull() }
    if (numbers.size != 4) return null
    val bounds = Rectangle(numbers[0], numbers[1], numbers[2], numbers[3])
    if (!fitsTheScreens(bounds)) return null
    return WindowGeometry(bounds, parts[4].trim() == "1")
}

/**
 * Follows [state] and keeps the geometry written down. [remembered] is what
 * the window opened with, so the state it starts in is not written straight
 * back out again.
 */
@Composable
fun rememberWindowGeometry(state: WindowState, remembered: WindowGeometry?): WindowMemory {
    val memory = remember(state) { WindowMemory(state, remembered) }
    LaunchedEffect(memory) { memory.follow() }
    return memory
}

/** What the state says now, and the only thing worth waking up for. */
private data class Reading(val floating: Rectangle?, val maximized: Boolean)

class WindowMemory internal constructor(
    private val state: WindowState,
    remembered: WindowGeometry?,
) {
    /**
     * The bounds the window had the last time it was floating. A maximized
     * window reports the whole screen, and Compose writes that into the
     * state, so what it should come back to has to be kept from before it
     * grew — otherwise a window closed maximized would snap to the default
     * size the moment it was unmaximized.
     */
    private var restored: Rectangle? = remembered?.bounds

    private var written: WindowGeometry? = remembered

    internal suspend fun follow() {
        // Minimized, and while the state is not yet showing real bounds,
        // there is nothing to learn: the reading is dropped, not written.
        snapshotFlow { reading() }.collectLatest { reading ->
            reading?.floating?.let { restored = it }
            if (reading == null || restored == null) return@collectLatest
            delay(QUIET_MS)
            keep()
        }
    }

    /** Writes now, for the moment the window closes and has no later. */
    fun flush() = keep()

    private fun keep() {
        val bounds = restored ?: return
        val next = WindowGeometry(bounds, state.placement == WindowPlacement.Maximized)
        if (next == written) return
        val line = "${bounds.x},${bounds.y},${bounds.width},${bounds.height},${if (next.maximized) 1 else 0}"
        // Noted as written only once it is, so a file that would not take it
        // is tried again at the next move rather than given up on.
        runCatching { prefs.putString(KEY, line) }.onSuccess { written = next }
    }

    private fun reading(): Reading? {
        if (state.isMinimized) return null
        val placement = state.placement
        // Only a floating window's bounds are its own; the state reads them
        // unconditionally, so maximized they are the screen's.
        return Reading(
            floating = if (placement == WindowPlacement.Floating) bounds() else null,
            maximized = placement == WindowPlacement.Maximized,
        )
    }

    private fun bounds(): Rectangle? {
        val position = state.position
        val size = state.size
        if (!position.isSpecified || !size.isSpecified) return null
        val bounds = Rectangle(
            position.x.value.roundToInt(),
            position.y.value.roundToInt(),
            size.width.value.roundToInt(),
            size.height.value.roundToInt(),
        )
        return bounds.takeIf { fitsTheScreens(it) }
    }
}

/**
 * Whether the window could be opened at [bounds] on the screens attached
 * *now*. A geometry remembered on a second monitor, or on a bigger one, says
 * nothing about this computer tonight, so it is checked rather than trusted —
 * and the same check guards what is written, which keeps a geometry nobody
 * could reopen from reaching the file in the first place.
 */
private fun fitsTheScreens(bounds: Rectangle): Boolean {
    if (bounds.width < MinWindowSize.width || bounds.height < MinWindowSize.height) return false
    val screens = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
    }.getOrNull().orEmpty()
    // The screen it mostly lives on; the window may straddle two.
    val host = screens.maxByOrNull { area(it.intersection(bounds)) } ?: return false
    val shown = host.intersection(bounds)
    // Off the edge of a screen that is still here, or on one that is gone.
    if (shown.width < Grab.width || shown.height < Grab.height) return false
    // And too wide for this monitor, having been sized on a wider one.
    return bounds.width <= host.width && bounds.height <= host.height
}

/** Disjoint rectangles intersect to a negative one, which is no area at all. */
private fun area(rect: Rectangle): Long =
    if (rect.isEmpty) 0L else rect.width.toLong() * rect.height
