package dev.nexfy.app.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import dev.nexfy.app.nexfyDataDir
import java.awt.GraphicsEnvironment
import java.lang.management.ManagementFactory
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A measuring run of the window, for development: started with
 * `-Dnexfy.perf=true`, it walks the shell through what a user does (scroll
 * the home page, change tabs, open a playlist and go back, the song's panel
 * and its tabs, Tocando agora) and times every frame of each step. The
 * report goes to `perf.txt` in the data directory and to stdout.
 *
 * The frame clock is asked for every frame while a step runs, so the window
 * draws continuously and each gap between frames is what one frame cost:
 * a gap past one and a half refreshes is a frame the screen did not get.
 */
internal object PerfTour {
    val enabled: Boolean = System.getProperty("nexfy.perf") == "true"
}

/** What the tour may move in the shell. */
internal class PerfControls(
    val setPanel: (open: Boolean, tab: Int) -> Unit,
    val setNowPlaying: (Boolean) -> Unit,
)

@Composable
internal fun PerfTourRunner(actions: AppActions, controls: PerfControls) {
    if (!PerfTour.enabled) return
    LaunchedEffect(Unit) {
        val nav = actions.nav
        val refresh = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate
            .takeIf { it > 0 } ?: 60
        val report = StringBuilder()
        report.appendLine("Nexfy desktop, frame timing. Screen ${refresh} Hz (a frame every ${"%.1f".format(1000.0 / refresh)} ms)")
        report.appendLine("step                          frames   fps   p50 ms  p95 ms  p99 ms  max ms  missed  cpu%")
        // Let the first page and its covers settle before anything is timed.
        delay(6_000)
        val playlist = runCatching { actions.core.myPlaylists(false) }.getOrNull()?.firstOrNull()

        suspend fun step(name: String, run: suspend () -> Unit) {
            val gaps = ArrayList<Double>(2048)
            val cpu0 = processCpuNanos()
            val wall0 = System.nanoTime()
            coroutineScope {
                val clock = launch {
                    var last = -1L
                    while (true) {
                        withFrameNanos { now ->
                            if (last >= 0) gaps += (now - last) / 1e6
                            last = now
                        }
                    }
                }
                run()
                clock.cancel()
            }
            val wall = System.nanoTime() - wall0
            val cpu = (processCpuNanos() - cpu0).toDouble() / wall * 100 / Runtime.getRuntime().availableProcessors()
            report.appendLine(line(name, gaps, refresh, cpu))
            delay(600)
        }

        val home = nav.list("root:${Tab.Home.name}")
        step("parado na Início (2s)") { delay(2_000) }
        step("rolar a Início") {
            repeat(3) {
                home.animateScrollBy(1_400f)
                home.animateScrollBy(-1_400f)
            }
        }
        step("trocar de aba x6") {
            for (tab in listOf(Tab.Search, Tab.Library, Tab.Home, Tab.Library, Tab.Search, Tab.Home)) {
                nav.tab = tab
                delay(700)
            }
        }
        if (playlist != null) {
            step("abrir playlist") {
                actions.open(Route.Playlist(playlist.id), Peek(playlist.name, playlist.owner, playlist.imageUrl))
                delay(1_500)
            }
            step("rolar a playlist") {
                val list = nav.list(Route.Playlist(playlist.id).key)
                repeat(3) {
                    list.animateScrollBy(1_600f)
                    list.animateScrollBy(-1_600f)
                }
            }
            step("voltar da playlist") {
                nav.pop()
                delay(1_200)
            }
        }
        step("abrir e fechar o painel x3") {
            repeat(3) {
                controls.setPanel(true, 0)
                delay(800)
                controls.setPanel(false, 0)
                delay(800)
            }
        }
        step("abas do painel") {
            controls.setPanel(true, 0)
            delay(700)
            for (tab in listOf(1, 2, 0, 2, 1)) {
                controls.setPanel(true, tab)
                delay(700)
            }
            controls.setPanel(false, 0)
            delay(600)
        }
        step("Tocando agora abrir e fechar x2") {
            repeat(2) {
                controls.setNowPlaying(true)
                delay(1_400)
                controls.setNowPlaying(false)
                delay(1_000)
            }
        }
        step("Tocando agora parado (3s)") {
            controls.setNowPlaying(true)
            delay(800)
            delay(3_000)
            controls.setNowPlaying(false)
            delay(800)
        }

        val heap = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }
        report.appendLine("heap em uso no fim: $heap MB")
        val text = report.toString()
        println(text)
        runCatching { nexfyDataDir().resolve("perf.txt").writeText(text) }
    }
}

private fun processCpuNanos(): Long =
    (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.processCpuTime ?: 0L

private fun line(name: String, gaps: List<Double>, refresh: Int, cpu: Double): String {
    if (gaps.isEmpty()) return "%-28s %6d".format(name, 0)
    val sorted = gaps.sorted()
    fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
    val total = gaps.sum()
    val budget = 1000.0 / refresh
    val missed = gaps.sumOf { g -> ((g / budget) - .5).toInt().coerceAtLeast(0) }
    return "%-28s %6d %5.0f %7.1f %7.1f %7.1f %7.1f %7d %5.1f".format(
        name, gaps.size, gaps.size / (total / 1000), pct(.5), pct(.95), pct(.99), sorted.last(), missed, cpu,
    )
}
