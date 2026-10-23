package dev.nexfy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import coil3.PlatformContext
import kotlinx.coroutines.flow.Flow

/** Open [url] in whatever the machine uses for links. */
expect fun openInBrowser(context: PlatformContext, url: String)

/** Put [text] on the clipboard. */
expect fun copyText(context: PlatformContext, text: String)

/** What is on the clipboard, if it is text. */
expect fun pasteText(context: PlatformContext): String?

/**
 * Offer [text] to other apps. False where the system has no share sheet,
 * so the caller can copy it and say so instead.
 */
expect fun shareText(context: PlatformContext, text: String): Boolean

/** Tag the frames that follow with `key=value` while [value] holds; nothing where frames are not measured. */
@Composable
expect fun MetricsState(key: String, value: String?)

/**
 * Blur everything behind this window, easing the blur in. Silent where the
 * system does not blur across windows, and on platforms with no such idea.
 */
@Composable
expect fun BlurBehindWindow(radius: Dp)

/**
 * One step of a back gesture: how far it has gone, where the finger is,
 * and which edge it came from.
 */
data class BackStep(val progress: Float, val touchX: Float, val fromRightEdge: Boolean)

/** Run [onBack] when the user asks to go back: the system gesture or button, or Esc. */
@Composable
expect fun BackGesture(enabled: Boolean = true, onBack: () -> Unit)

/**
 * A back gesture the screen follows while it happens. [onBack] collects
 * the steps; the flow ending means the gesture went through, and a
 * CancellationException that it was given up. Where back is a key press
 * there are no steps to collect: the flow ends at once, and the screen
 * goes back with its own animation.
 */
@Composable
expect fun ProgressiveBackGesture(enabled: Boolean, onBack: suspend (Flow<BackStep>) -> Unit)

/**
 * Whether a blur in a graphics layer draws anything. Android only got
 * render effects in 12 (API 31); Skia on a computer has always had them.
 */
expect val blurEffectSupported: Boolean

/** Ask the system to draw its own bars for a light or a dark app; nothing where it draws none. */
@Composable
expect fun SystemBarAppearance(light: Boolean)

/** This install's version, to show in the settings; null when it cannot be read. */
expect fun appVersion(context: PlatformContext): String?
