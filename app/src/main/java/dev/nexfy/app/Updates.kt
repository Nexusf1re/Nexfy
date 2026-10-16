package dev.nexfy.app

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A Nexfy release on GitHub. */
data class Release(val version: String, val notes: String, val apkUrl: String?, val pageUrl: String)

/**
 * New versions: asks GitHub for the latest release, at most once a day or
 * when asked, and offers it for as long as it is newer than this install.
 * The last answer is kept, so the offer survives a day without asking again.
 */
class Updates(context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** This install's version, as GitHub names it ("0.2.5"). */
    val installed: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private val _offer = MutableStateFlow<Release?>(null)

    /** A newer release than this install, until it is installed. */
    val offer: StateFlow<Release?> = _offer

    private val _bannerHidden = MutableStateFlow(false)

    /** The floating banner was closed: hidden until the app next starts. */
    val bannerHidden: StateFlow<Boolean> = _bannerHidden

    init {
        prefs.getString(LATEST, null)?.let(::parse)?.let(::consider)
    }

    /** Ask GitHub if a day has passed since the last time. */
    suspend fun checkDaily() {
        if (System.currentTimeMillis() - prefs.getLong(CHECKED, 0) < DAY_MS) return
        check()
    }

    /**
     * Ask GitHub now. Returns the latest release when it is newer than this
     * install, null when this is the latest; throws when GitHub cannot be read.
     */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_URL).openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Nexfy")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val body = try {
            if (connection.responseCode != 200) error("GitHub ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val release = parse(body) ?: error("release sem versão")
        prefs.edit().putLong(CHECKED, System.currentTimeMillis()).putString(LATEST, body).apply()
        consider(release)
        release.takeIf { newer(it.version, installed) }
    }

    /** Close the floating banner for now; the settings keep the update pending. */
    fun hideBanner() {
        _bannerHidden.value = true
    }

    private fun consider(release: Release) {
        _offer.value = release.takeIf { newer(it.version, installed) }
    }

    private fun parse(json: String): Release? = runCatching {
        val o = JSONObject(json)
        val version = o.getString("tag_name").removePrefix("v")
        val assets = o.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0))
            .map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url")
        Release(version, o.optString("body").trim(), apk, o.optString("html_url"))
    }.onFailure { Log.d("Nexfy", "release: ${it.message}") }.getOrNull()

    private companion object {
        const val LATEST_URL = "https://api.github.com/repos/Nexusf1re/Nexfy/releases/latest"
        const val CHECKED = "checked"
        const val LATEST = "latest"
        const val DAY_MS = 86_400_000L
    }
}

/**
 * Whether version [a] comes after [b], comparing their numbers in order
 * ("0.2.10" after "0.2.9"). A suffix ("0.2.5-dev") counts as that version.
 */
fun newer(a: String, b: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val x = parts(a)
    val y = parts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}
