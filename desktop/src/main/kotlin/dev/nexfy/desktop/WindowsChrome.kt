package dev.nexfy.desktop

import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.WindowProc
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * A window that draws its own frame still wants everything the shell does
 * around one: the sweep to the taskbar and back, the grow into the corner
 * of the screen, the rounded corners and the shadow, the system menu, Aero
 * snap, and a border that resizes.
 *
 * None of that hangs off the frame being drawn by Windows — it hangs off
 * the window's styles. AWT makes an undecorated window a bare popup with
 * none of them, which is why it appeared and vanished with no motion at
 * all. So the styles go back on, and the one consequence of having them —
 * Windows drawing a caption and a border of its own — is taken away again
 * by answering `WM_NCCALCSIZE` with a client area that covers the whole
 * window. The shell then animates a window it believes is ordinary, and
 * every pixel of it is still Nexfy's to paint.
 *
 * This is the same trick every app with a frame of its own plays on
 * Windows. Everything here is wrapped so that a failure costs only the
 * motion: the window then behaves as it did before.
 */
private interface Dwm : StdCallLibrary {
    fun DwmSetWindowAttribute(window: HWND, attribute: Int, value: Pointer, size: Int): Int
}

private const val GWL_STYLE = -16
private const val GWLP_WNDPROC = -4

/** The styles the shell's own motion and chrome hang off. */
private const val WS_CAPTION = 0x00C00000
private const val WS_THICKFRAME = 0x00040000
private const val WS_MINIMIZEBOX = 0x00020000
private const val WS_MAXIMIZEBOX = 0x00010000
private const val WS_SYSMENU = 0x00080000
private const val WS_POPUP = -0x80000000
private const val WS_MAXIMIZE = 0x01000000

private const val WM_NCCALCSIZE = 0x0083
private const val WM_NCHITTEST = 0x0084

/** Where the pointer is: the client, or one of the eight ways to resize. */
private const val HTCLIENT = 1
private const val HTLEFT = 10
private const val HTRIGHT = 11
private const val HTTOP = 12
private const val HTTOPLEFT = 13
private const val HTTOPRIGHT = 14
private const val HTBOTTOM = 15
private const val HTBOTTOMLEFT = 16
private const val HTBOTTOMRIGHT = 17

/** Windows 11 rounds a window's corners when asked to. */
private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
private const val DWMWCP_ROUND = 2

private const val SM_CXSIZEFRAME = 32
private const val SM_CYSIZEFRAME = 33
private const val SM_CXPADDEDBORDER = 92

private const val SWP_NOMOVE = 0x0002
private const val SWP_NOSIZE = 0x0001
private const val SWP_NOZORDER = 0x0004
private const val SWP_FRAMECHANGED = 0x0020

/** How wide the invisible band that resizes the window is. */
private const val GRIP = 6

/**
 * The procedures replacing AWT's are reached from native code, so nothing
 * on the Java side refers to them: without this they would be collected
 * and the window would start crashing on its next message.
 */
private val installed = mutableListOf<Any>()

/**
 * Gives [window] the chrome of an ordinary window — its animations, its
 * rounded corners, its shadow, its system menu and a border that resizes —
 * while leaving every pixel to the app. Does nothing away from Windows,
 * and nothing where the call fails.
 */
fun dressWindow(window: Window) {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
    runCatching {
        val handle = HWND(Native.getComponentPointer(window) ?: return)
        val user32 = User32.INSTANCE
        val style = user32.GetWindowLong(handle, GWL_STYLE)
        val wanted = (style or WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_SYSMENU) and
            WS_POPUP.inv()
        user32.SetWindowLong(handle, GWL_STYLE, wanted)

        // The frame those styles ask for is removed message by message,
        // chaining to AWT's own procedure for everything else.
        val previous = Pointer(user32.GetWindowLongPtr(handle, GWLP_WNDPROC).toLong())
        val proc = WindowProc { hwnd, message, wParam, lParam ->
            when {
                message == WM_NCCALCSIZE && wParam.toLong() != 0L -> noFrame(hwnd, lParam)
                message == WM_NCHITTEST -> grip(hwnd, lParam) ?: user32.CallWindowProc(previous, hwnd, message, wParam, lParam)
                else -> user32.CallWindowProc(previous, hwnd, message, wParam, lParam)
            }
        }
        installed += proc
        user32.SetWindowLongPtr(handle, GWLP_WNDPROC, CallbackReference.getFunctionPointer(proc))

        runCatching {
            val round = Memory(4).apply { setInt(0, DWMWCP_ROUND) }
            Native.load("dwmapi", Dwm::class.java)
                .DwmSetWindowAttribute(handle, DWMWA_WINDOW_CORNER_PREFERENCE, round, 4)
        }

        // The frame is recomputed with the new styles, moving nothing.
        user32.SetWindowPos(handle, null, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED)
    }
}

/**
 * Keeps the window's client area over the whole window, frame and all.
 * Maximized, Windows hangs the frame off the edges of the screen, so the
 * app would be drawing those few pixels out of sight: there, and only
 * there, the client is pulled back inside by the frame's own thickness.
 */
private fun noFrame(window: HWND, lParam: LPARAM): LRESULT {
    val rect = Pointer(lParam.toLong())
    val user32 = User32.INSTANCE
    if (user32.GetWindowLong(window, GWL_STYLE) and WS_MAXIMIZE != 0) {
        val x = user32.GetSystemMetrics(SM_CXSIZEFRAME) + user32.GetSystemMetrics(SM_CXPADDEDBORDER)
        val y = user32.GetSystemMetrics(SM_CYSIZEFRAME) + user32.GetSystemMetrics(SM_CXPADDEDBORDER)
        rect.setInt(0, rect.getInt(0) + x)
        rect.setInt(4, rect.getInt(4) + y)
        rect.setInt(8, rect.getInt(8) - x)
        rect.setInt(12, rect.getInt(12) - y)
    }
    return LRESULT(0)
}

/**
 * The band along each edge that resizes the window. The frame is gone, so
 * Windows no longer finds these itself; the app's own content starts a
 * few pixels in, where the edge is not an edge any more. Returns null
 * where the pointer is over the app, which answers for itself.
 */
private fun grip(window: HWND, lParam: LPARAM): LRESULT? {
    val user32 = User32.INSTANCE
    // Maximized, there is nothing to pull: the edges are the screen's.
    if (user32.GetWindowLong(window, GWL_STYLE) and WS_MAXIMIZE != 0) return null
    val frame = RECT()
    if (!user32.GetWindowRect(window, frame)) return null
    val packed = lParam.toLong()
    val x = (packed and 0xFFFF).toShort().toInt()
    val y = ((packed shr 16) and 0xFFFF).toShort().toInt()
    val left = x < frame.left + GRIP
    val right = x >= frame.right - GRIP
    val top = y < frame.top + GRIP
    val bottom = y >= frame.bottom - GRIP
    val where = when {
        top && left -> HTTOPLEFT
        top && right -> HTTOPRIGHT
        bottom && left -> HTBOTTOMLEFT
        bottom && right -> HTBOTTOMRIGHT
        left -> HTLEFT
        right -> HTRIGHT
        top -> HTTOP
        bottom -> HTBOTTOM
        else -> HTCLIENT
    }
    return if (where == HTCLIENT) null else LRESULT(where.toLong())
}
