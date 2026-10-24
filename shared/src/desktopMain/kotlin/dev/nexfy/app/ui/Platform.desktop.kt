package dev.nexfy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Dp
import coil3.PlatformContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

actual fun openInBrowser(context: PlatformContext, url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

actual fun copyText(context: PlatformContext, text: String, label: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

actual fun pasteText(context: PlatformContext): String? = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()

/** A computer has no share sheet; the caller copies the text instead. */
actual fun shareText(context: PlatformContext, text: String) = false

/** Frames are not measured here. */
@Composable
actual fun MetricsState(key: String, value: String?) = Unit

/** One window, nothing of the app behind it to blur. */
@Composable
actual fun BlurBehindWindow(radius: Dp) = Unit

/**
 * Going back on a computer is the Esc key, which the window hands here.
 * The innermost screen that wants it gets it, as the system's own back
 * reaches the top of the stack first.
 */
object DesktopBack {
    private class Handler(val enabled: () -> Boolean, val run: () -> Unit)

    private val handlers = mutableListOf<Handler>()

    /** True when a screen took the key, so the window leaves it alone. */
    fun pressed(): Boolean {
        for (handler in handlers.asReversed()) {
            if (!handler.enabled()) continue
            handler.run()
            return true
        }
        return false
    }

    internal fun add(enabled: () -> Boolean, run: () -> Unit): () -> Unit {
        val handler = Handler(enabled, run)
        handlers += handler
        return { handlers -= handler }
    }
}

@Composable
actual fun BackGesture(enabled: Boolean, onBack: () -> Unit) {
    val on = rememberUpdatedState(enabled)
    val back = rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val remove = DesktopBack.add({ on.value }) { back.value() }
        onDispose(remove)
    }
}

@Composable
actual fun ProgressiveBackGesture(enabled: Boolean, onBack: suspend (Flow<BackStep>) -> Unit) {
    val scope = rememberCoroutineScope()
    val on = rememberUpdatedState(enabled)
    val back = rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        // A key press has no steps to follow: the flow ends at once and the
        // screen goes back with its own animation.
        val remove = DesktopBack.add({ on.value }) { scope.launch { back.value(emptyFlow()) } }
        onDispose(remove)
    }
}

/** Skia blurs on every platform the desktop app runs on. */
actual val blurEffectSupported: Boolean = true

/** The window has no system bars of its own to colour. */
@Composable
actual fun SystemBarAppearance(light: Boolean) = Unit

actual fun appVersion(context: PlatformContext): String? =
    DesktopBack::class.java.`package`?.implementationVersion

actual fun appDataDir(context: PlatformContext): java.io.File = dev.nexfy.app.nexfyDataDir()

/**
 * The keys a music app answers: the media keys of a keyboard that has
 * them, and Ctrl with the arrows for the same on one that does not. The
 * window hands every key press here first.
 *
 * Space is deliberately not one of them: it would be taken from the
 * search field as the user typed.
 */
object DesktopKeys {
    /** Set by the shell while it is up. */
    internal var handler: ((key: Key, ctrl: Boolean) -> Boolean)? = null

    /** True when the app took the key, so the window leaves it alone. */
    fun pressed(key: Key, ctrl: Boolean): Boolean = handler?.invoke(key, ctrl) == true
}
