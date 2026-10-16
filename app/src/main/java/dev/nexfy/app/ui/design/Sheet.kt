package dev.nexfy.app.ui.design

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nexfy.app.ui.theme.Hairline
import dev.nexfy.app.ui.theme.isDark

/**
 * A bottom sheet of frosted glass: graphite over the app, which blurs and
 * steps back behind it ([sheetDepth]; the system's cross-window blur too,
 * where the device allows it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    val dark = c.isDark
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = shape,
        // Solid: lighter spots of the blurred app (a glass button, a bright cover)
        // would otherwise show through the list as smudges.
        containerColor = if (dark) Color(0xFF16171B) else Color.White,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (dark) .28f else .16f),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp)
                    .background(c.onSurface.copy(alpha = .28f), CircleShape),
            )
        },
    ) {
        // The app comes forward as soon as the sheet starts to close, with it,
        // not after it has gone.
        val showing = sheetState.targetValue != SheetValue.Hidden
        DisposableEffect(showing) {
            if (showing) SheetDepth.open++
            onDispose { if (showing) SheetDepth.open-- }
        }
        BlurBehind(24.dp)
        content()
    }
}

/**
 * Blurs everything behind this composable's window, easing the blur in.
 * Silent where cross-window blur is off (battery saver, older devices).
 */
@Composable
private fun BlurBehind(radius: Dp) {
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

/** Menus: near-solid graphite (a popup cannot blur what is behind it), framed by a hairline. */
@Composable
fun glassMenuColor(): Color {
    val c = MaterialTheme.colorScheme
    return (if (c.isDark) Color(0xFF1A1B1F) else Color.White).copy(alpha = .97f)
}

@Composable
fun glassMenuEdge(): BorderStroke {
    val c = MaterialTheme.colorScheme
    return BorderStroke(Hairline, if (c.isDark) Color.White.copy(alpha = .14f) else c.outline)
}

/**
 * How many glass sheets are open. The app behind them (see
 * [Modifier.sheetDepth]) blurs and steps back while any is, which works
 * where the system's cross-window blur does not.
 */
object SheetDepth {
    var open by mutableIntStateOf(0)
}

/** The app under open sheets: blurred and a touch smaller, eased in and out. */
@Composable
fun Modifier.sheetDepth(): Modifier {
    val open = SheetDepth.open > 0
    // In at the sheet's pace; out quicker, so no blur lingers once it is gone.
    val depth = animateFloatAsState(if (open) 1f else 0f, Motion.standard(if (open) Motion.Medium else Motion.Short), label = "sheetDepth")
    return graphicsLayer {
        val d = depth.value
        if (d > 0.001f) {
            val r = 16.dp.toPx() * d
            renderEffect = BlurEffect(r, r, TileMode.Clamp)
            val s = 1f - .04f * d
            scaleX = s
            scaleY = s
        } else {
            renderEffect = null
            scaleX = 1f
            scaleY = 1f
        }
    }
}
