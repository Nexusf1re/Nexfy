package dev.nexfy.app

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** A Nexfy release on GitHub. */
data class Release(val version: String, val notes: String, val apkUrl: String?, val pageUrl: String)

/** Where the update's file is: not asked for, coming in ([fraction] once its size is known), in, or failed. */
sealed interface UpdateDownload {
    data object Idle : UpdateDownload
    data class Running(val fraction: Float?) : UpdateDownload
    data class Ready(val file: File) : UpdateDownload
    data object Failed : UpdateDownload
}

/**
 * What the update banner and the settings page need of the updater. The
 * phone has one (it downloads and installs an APK); the desktop has none,
 * so [NexfyServices.updates] is null there and those rows do not appear.
 */
interface UpdateOffers {
    /** This install's version, as GitHub names it ("0.2.5"). */
    val installed: String

    /** A newer release than this install, until it is installed. */
    val offer: StateFlow<Release?>

    /** The floating banner was closed: hidden until the app next starts. */
    val bannerHidden: StateFlow<Boolean>

    val download: StateFlow<UpdateDownload>

    suspend fun check(): Release?
    fun download(offered: Release)
    fun install(file: File)
    fun hideBanner()
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
