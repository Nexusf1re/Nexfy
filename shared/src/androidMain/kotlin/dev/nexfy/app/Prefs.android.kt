package dev.nexfy.app

import android.content.Context
import android.content.SharedPreferences
import coil3.PlatformContext

private class AndroidPrefs(private val prefs: SharedPreferences) : Prefs {
    override fun string(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun boolean(key: String, fallback: Boolean): Boolean = prefs.getBoolean(key, fallback)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun long(key: String, fallback: Long): Long = prefs.getLong(key, fallback)

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun strings(key: String): Set<String> = prefs.getStringSet(key, null).orEmpty()

    override fun putStrings(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }
}

actual fun openPrefs(context: PlatformContext, name: String): Prefs =
    AndroidPrefs(context.getSharedPreferences(name, Context.MODE_PRIVATE))
