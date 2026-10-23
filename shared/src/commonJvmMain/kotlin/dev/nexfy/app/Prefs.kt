package dev.nexfy.app

import coil3.PlatformContext

/**
 * The small settings store the library and the appearance options keep:
 * what SharedPreferences gave the Android app, narrowed to the calls
 * it actually makes, so the desktop can answer them from a file.
 */
interface Prefs {
    fun string(key: String): String?
    fun putString(key: String, value: String?)
    fun boolean(key: String, fallback: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun long(key: String, fallback: Long): Long
    fun putLong(key: String, value: Long)
    fun strings(key: String): Set<String>
    fun putStrings(key: String, value: Set<String>)
}

/** The store called [name], created on first use. */
expect fun openPrefs(context: PlatformContext, name: String): Prefs
