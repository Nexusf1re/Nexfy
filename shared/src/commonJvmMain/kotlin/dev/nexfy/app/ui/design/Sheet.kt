package dev.nexfy.app.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nexfy.app.ui.BlurBehindWindow
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
    // A sheet rises from the bottom edge because that is where a thumb is.
    // A pointer is wherever the user left it, so the same content opens in
    // the middle of the window instead, as every desktop app opens one.
    if (LocalPointer.current) return GlassDialog(onDismissRequest, content)
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
        BlurBehindWindow(24.dp)
        content()
    }
}

/** The same content, centred and framed, for a window driven by a pointer. */
@Composable
private fun GlassDialog(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    val dark = c.isDark
    val shape = RoundedCornerShape(22.dp)
    // The app behind steps back for a dialog as it does for a sheet.
    DisposableEffect(Unit) {
        SheetDepth.open++
        onDispose { SheetDepth.open-- }
    }
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 640.dp)
                .clip(shape)
                // Solid, for the same reason the sheet is: bright spots of the
                // blurred app would smudge the list.
                .background(if (dark) Color(0xFF16171B) else Color.White)
                .border(Hairline, if (dark) Color.White.copy(.10f) else c.outline, shape)
                .padding(top = 14.dp),
        ) { content() }
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
