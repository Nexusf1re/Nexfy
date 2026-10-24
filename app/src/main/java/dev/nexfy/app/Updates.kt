package dev.nexfy.app

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * New versions: asks GitHub for the latest release, at most once a day or
 * when asked, and offers it for as long as it is newer than this install.
 * The last answer is kept, so the offer survives a day without asking again.
 */
class Updates(context: Context, private val scope: CoroutineScope) : UpdateOffers {
    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** This install's version, as GitHub names it ("0.2.5"). */
    override val installed: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private val _offer = MutableStateFlow<Release?>(null)

    /** A newer release than this install, until it is installed. */
    override val offer: StateFlow<Release?> = _offer

    private val _bannerHidden = MutableStateFlow(false)

    /** The floating banner was closed: hidden until the app next starts. */
    override val bannerHidden: StateFlow<Boolean> = _bannerHidden

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
    override suspend fun check(): Release? = withContext(Dispatchers.IO) {
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

    private val _download = MutableStateFlow<UpdateDownload>(UpdateDownload.Idle)

    /** The update's APK coming in, in the app: what the sheet shows. */
    override val download: StateFlow<UpdateDownload> = _download

    /**
     * Download [release]'s APK and hand it to the installer. Neither a
     * browser nor the system download manager was reliable: one parks the
     * download behind a folder prompt, the other left it pending.
     */
    override fun download(offered: Release) {
        if (offered.apkUrl == null) return
        if (_download.value is UpdateDownload.Running) return
        _download.value = UpdateDownload.Running(null)
        scope.launch(Dispatchers.IO) {
            // What was offered can be a day old, and installing a release older
            // than the newest one Android already refuses as a downgrade: ask
            // again, and take whichever is newer.
            val latest = runCatching { check() }.getOrNull()
            val release = latest?.takeIf { newer(it.version, offered.version) && it.apkUrl != null } ?: offered
            val url = release.apkUrl ?: run {
                _download.value = UpdateDownload.Failed
                return@launch
            }
            val dir = File(app.getExternalFilesDir(null), "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "nexfy-${release.version}.apk")
            // Fetched here rather than through the system download manager,
            // which left the request pending for minutes on this phone.
            val got = runCatching { fetch(url, file) }
                .onFailure { Log.d("Nexfy", "update download: ${it.message}") }
                .getOrDefault(false)
            if (got) {
                _download.value = UpdateDownload.Ready(file)
                install(file)
            } else {
                file.delete()
                _download.value = UpdateDownload.Failed
            }
        }
    }

    /** Writes [url] to [file], reporting how far it got; false when it did not finish. */
    private fun fetch(url: String, file: File): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Nexfy")
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) return false
            val total = connection.contentLengthLong
            var done = 0L
            connection.inputStream.use { source ->
                file.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        done += read
                        _download.value = UpdateDownload.Running(if (total > 0) done.toFloat() / total else null)
                    }
                }
            }
            return total <= 0 || done == total
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Open the installer on a downloaded APK. Until the user allows Nexfy to
     * install apps, it opens that setting instead; back from it, "Instalar"
     * tries again.
     */
    override fun install(file: File) {
        val intent = if (!app.packageManager.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
        } else {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(app, "${app.packageName}.files", file), APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.d("Nexfy", "install: ${it.message}") }
    }

    /** Close the floating banner for now; the settings keep the update pending. */
    override fun hideBanner() {
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
        const val APK = "application/vnd.android.package-archive"
    }
}

