package dev.nexfy.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window

/**
 * A window with no frame of its own loses what Windows does around one.
 * AWT makes an undecorated window a plain popup, and the shell animates
 * nothing for a popup: it vanishes to the taskbar and comes back with no
 * motion, because the styles that say "this window minimizes and
 * maximizes" are not on it.
 *
 * Putting those styles back costs nothing on screen — the caption and its
 * buttons come from a different style, which stays off — and gives the
 * window its animations, its place in the system menu and Aero snap.
 */
private interface User32 : Library {
    fun GetWindowLongPtrW(window: Pointer, index: Int): Pointer

    fun SetWindowLongPtrW(window: Pointer, index: Int, value: Pointer): Pointer

    fun SetWindowPos(
        window: Pointer,
        after: Pointer?,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        flags: Int,
    ): Boolean
}

private const val GWL_STYLE = -16

/** The window minimizes, maximizes and has a system menu. None of them draws anything. */
private const val WS_MINIMIZEBOX = 0x00020000L
private const val WS_MAXIMIZEBOX = 0x00010000L
private const val WS_SYSMENU = 0x00080000L

/** Redraw the frame after the styles change, moving and resizing nothing. */
private const val SWP_NOMOVE = 0x0002
private const val SWP_NOSIZE = 0x0001
private const val SWP_NOZORDER = 0x0004
private const val SWP_FRAMECHANGED = 0x0020

/**
 * Gives [window] back the styles its animations hang off. Silent where
 * this is not Windows, or where the call fails: the window then behaves as
 * it did, which is only a window that appears without motion.
 */
fun restoreWindowAnimations(window: Window) {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
    runCatching {
        val user32 = Native.load("user32", User32::class.java)
        val handle = Native.getComponentPointer(window) ?: return
        val style = Pointer.nativeValue(user32.GetWindowLongPtrW(handle, GWL_STYLE))
        val wanted = style or WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_SYSMENU
        if (wanted == style) return
        user32.SetWindowLongPtrW(handle, GWL_STYLE, Pointer(wanted))
        user32.SetWindowPos(handle, null, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED)
    }
}
