package dev.nexfy.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat
import androidx.metrics.performance.PerformanceMetricsState
import coil3.PlatformContext
import dev.nexfy.app.ui.design.Motion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

actual fun openInBrowser(context: PlatformContext, url: String) {
    val open = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(open)
}

actual fun copyText(context: PlatformContext, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
}

actual fun pasteText(context: PlatformContext): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

actual fun shareText(context: PlatformContext, text: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

@Composable
actual fun MetricsState(key: String, value: String?) {
    val view = LocalView.current
    val holder = remember(view) { PerformanceMetricsState.getHolderForHierarchy(view) }
    LaunchedEffect(holder, value) {
        val state = holder.state ?: return@LaunchedEffect
        if (value == null) state.removeState(key) else state.putState(key, value)
    }
}

@Composable
actual fun BlurBehindWindow(radius: Dp) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    val target = with(LocalDensity.current) { radius.toPx() }
    LaunchedEffect(view) {
        val root = view.rootView
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return@LaunchedEffect
        val windows = view.context.getSystemService(WindowManager::class.java) ?: return@LaunchedEffect
        if (!windows.isCrossWindowBlurEnabled) return@LaunchedEffect
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        val blur = Animatable(0f)
        blur.animateTo(target, Motion.standard(Motion.Medium)) {
            params.blurBehindRadius = value.toInt()
            runCatching { windows.updateViewLayout(root, params) }
        }
    }
}

@Composable
actual fun BackGesture(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable
actual fun ProgressiveBackGesture(enabled: Boolean, onBack: suspend (Flow<BackStep>) -> Unit) =
    PredictiveBackHandler(enabled) { events -> onBack(events.map(BackEventCompat::step)) }

private fun BackEventCompat.step() =
    BackStep(progress, touchX, fromRightEdge = swipeEdge == BackEventCompat.EDGE_RIGHT)

actual val blurEffectSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
actual fun SystemBarAppearance(light: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, light) {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
        onDispose { }
    }
}

actual fun appVersion(context: PlatformContext): String? = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName} (${info.longVersionCode})"
}.getOrNull()
