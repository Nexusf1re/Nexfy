package dev.nexfy.app.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * True where the window's own frame already carries the app's name, the
 * way back and the buttons, so a screen must not draw a header of its own.
 * False on a phone, where every screen has to carry one.
 */
val LocalShellHeader = staticCompositionLocalOf { false }

/**
 * True where the input is a mouse. Settings about a finger (vibration,
 * dragging a page away) have nothing to act on, and are left out.
 */
val LocalPointer = staticCompositionLocalOf { false }

/**
 * How many tiles fit a row [width] wide. A phone's three columns become
 * five or six on a window, instead of tiles the size of a postcard.
 */
fun gridColumns(width: Dp, tile: Dp = 170.dp): Int = (width / tile).toInt().coerceIn(2, 8)
