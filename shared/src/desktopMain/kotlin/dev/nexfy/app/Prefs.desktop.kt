package dev.nexfy.app

import coil3.PlatformContext
import java.io.File
import java.util.Properties

/**
 * Where the desktop app keeps the Spotify credential, the audio cache, the
 * listening log and the settings: the per-user application data directory,
 * made on first use.
 *
 * Not "Nexfy" itself: on Windows that is where the per-user installer puts
 * the program, and an update uninstalls the old version by deleting that
 * whole folder, which took everything kept there with it.
 */
fun nexfyDataDir(): File = dataDir

private val dataDir: File by lazy {
    val base = System.getenv("LOCALAPPDATA")
        ?: System.getenv("XDG_DATA_HOME")
        ?: (System.getProperty("user.home") + "/.local/share")
    File(base, "NexfyData").apply {
        mkdirs()
        moveDataFrom(File(base, "Nexfy"), this)
    }
}

/** What the installer puts in the program's folder: never data. */
private val PROGRAM_FILES = setOf("app", "runtime", "Nexfy.exe", "Nexfy.ico", "nexfy.jsa")

/**
 * Once, from where versions up to 0.3.1 kept the data (the program's own
 * folder), so an update after this one finds it out of the installer's way.
 */
private fun moveDataFrom(old: File, into: File) {
    if (File(into, "credentials").exists()) return
    val entries = old.listFiles()?.filter { it.name !in PROGRAM_FILES } ?: return
    for (entry in entries) {
        val target = File(into, entry.name)
        if (target.exists()) continue
        // A rename is instant on the same disk; across disks it copies.
        if (!entry.renameTo(target)) {
            runCatching { entry.copyRecursively(target, overwrite = false) && entry.deleteRecursively() }
        }
    }
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
