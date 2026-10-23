package dev.nexfy.app

import coil3.PlatformContext
import java.io.File
import java.util.Properties

/**
 * Where the desktop app keeps the Spotify credential, the audio cache and
 * the settings: the per-user application data directory, made on first use.
 */
fun nexfyDataDir(): File {
    val base = System.getenv("LOCALAPPDATA")
        ?: System.getenv("XDG_DATA_HOME")
        ?: (System.getProperty("user.home") + "/.local/share")
    return File(base, "Nexfy").apply { mkdirs() }
}

/**
 * A properties file, read once and written whole on every change. The
 * store holds a few dozen short values, so rewriting it costs less than
 * keeping a journal would, and a half-written file cannot survive: it is
 * written beside the real one and moved over it.
 */
private class FilePrefs(private val file: File) : Prefs {
    private val values = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    @Synchronized
    private fun flush() {
        val temporary = File(file.parentFile, file.name + ".new")
        temporary.outputStream().use { values.store(it, "Nexfy") }
        temporary.renameTo(file).let { moved ->
            // Windows refuses to rename over a file that exists.
            if (!moved) {
                file.delete()
                temporary.renameTo(file)
            }
        }
    }

    override fun string(key: String): String? = values.getProperty(key)

    @Synchronized
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values.setProperty(key, value)
        flush()
    }

    override fun boolean(key: String, fallback: Boolean): Boolean =
        values.getProperty(key)?.toBooleanStrictOrNull() ?: fallback

    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    override fun long(key: String, fallback: Long): Long =
        values.getProperty(key)?.toLongOrNull() ?: fallback

    override fun putLong(key: String, value: Long) = putString(key, value.toString())

    /** A set is one line per member, which no key of ours contains. */
    override fun strings(key: String): Set<String> =
        values.getProperty(key)?.lineSequence()?.filter(String::isNotBlank)?.toSet().orEmpty()

    override fun putStrings(key: String, value: Set<String>) = putString(key, value.joinToString("\n"))
}

actual fun openPrefs(context: PlatformContext, name: String): Prefs =
    FilePrefs(File(nexfyDataDir(), "$name.properties"))
