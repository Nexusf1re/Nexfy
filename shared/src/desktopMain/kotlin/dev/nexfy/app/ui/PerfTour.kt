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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

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

    /** With `-Dnexfy.perf.stacks=true`, the window's thread is sampled too (see [EdtSampler]). */
    val stacks: Boolean = System.getProperty("nexfy.perf.stacks") == "true"
}

/**
 * Where the window's thread spends a step: its stack read every millisecond
 * while the step runs, and the frames counted. Flight Recorder barely samples
 * this thread on Windows, and the frames are composed, measured and recorded
 * on it, so this is the profile that names the code a missed frame ran.
 *
 * Reading a stack stops the thread at a safepoint, so a sampled run's frame
 * times are a little worse than a plain one's: it is for finding, not for
 * the before-and-after numbers.
 */
private class EdtSampler(private val edt: Thread) {
    @Volatile var step: String? = null
    private val busy = HashMap<String, Int>()
    private val inclusive = HashMap<String, HashMap<String, Int>>()
    private val leaves = HashMap<String, HashMap<String, Int>>()
    private val chains = HashMap<String, HashMap<String, Int>>()

    init {
        Thread({
            while (true) {
                val name = step
                if (name != null) take(name, edt.stackTrace)
                // Spun rather than slept: Windows wakes a sleeping thread on
                // its 15.6 ms tick, which left a missed frame a sample or two.
                val until = System.nanoTime() + 1_000_000
                while (System.nanoTime() < until) Thread.onSpinWait()
            }
        }, "perf-edt-sampler").apply { isDaemon = true }.start()
    }

    private fun take(name: String, stack: Array<StackTraceElement>) {
        val top = stack.firstOrNull() ?: return
        // Waiting for the next event or frame is not work.
        if (top.methodName == "park" || top.methodName == "wait" || top.methodName == "wait0" ||
            top.className.endsWith("EventQueue") && top.methodName == "getNextEvent"
        ) return
        synchronized(this) {
            busy.merge(name, 1, Int::plus)
            val seen = HashSet<String>()
            val incl = inclusive.getOrPut(name) { HashMap() }
            var leaf: String? = null
            for (frame in stack) {
                val app = frame.className.startsWith("dev.nexfy")
                val key = if (app) "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
                else "${frame.className}.${frame.methodName}"
                if (app && leaf == null) leaf = key
                if (seen.add(key)) incl.merge(key, 1, Int::plus)
            }
            leaves.getOrPut(name) { HashMap() }.merge(leaf ?: "${top.className}.${top.methodName}", 1, Int::plus)
            // What ran (the top few frames) and which of the app's code it ran
            // under (its innermost frames): who asked for the work, not only what it was.
            fun StackTraceElement.short() = "${className.substringAfterLast('.')}.$methodName:$lineNumber"
            val under = stack.filter { it.className.startsWith("dev.nexfy") }.take(6).map { it.short() }
            val chain = (stack.take(2).map { it.short() } + "..." + under).joinToString("\n        ")
            chains.getOrPut(name) { HashMap() }.merge(chain, 1, Int::plus)
        }
    }

    fun report(out: StringBuilder) = synchronized(this) {
        for ((name, total) in busy) {
            out.appendLine()
            out.appendLine("== $name: $total samples (about a millisecond each) busy on the window's thread")
            out.appendLine("-- innermost app frame")
            leaves[name].orEmpty().entries.sortedByDescending { it.value }.take(14)
                .forEach { out.appendLine("%5d  %s".format(it.value, it.key)) }
            out.appendLine("-- hottest stacks")
            chains[name].orEmpty().entries.sortedByDescending { it.value }.take(10)
                .forEach { out.appendLine("%5d  %s".format(it.value, it.key)) }
            out.appendLine("-- inclusive")
            inclusive[name].orEmpty().entries.sortedByDescending { it.value }
                .filter { (k, _) -> k.contains("nexfy") || k.contains("compose.ui") || k.contains("compose.foundation") || k.contains("skia") }
                .take(40)
                .forEach { out.appendLine("%5d  %s".format(it.value, it.key)) }
        }
    }
}

/** What the tour may move in the shell. */
internal class PerfControls(
    val setPanel: (open: Boolean, tab: Int) -> Unit,
    val setNowPlaying: (Boolean) -> Unit,
    /** Tocando agora reopened on its words. */
    val setLyrics: (Boolean) -> Unit,
)

/** The longest a step may take before the tour moves on without it. */
private const val STEP_LIMIT_MS = 30_000L

/** What the tour searches for: an artist anyone's catalogue has. */
private const val PERF_QUERY = "daft punk"

@Composable
internal fun PerfTourRunner(actions: AppActions, controls: PerfControls) {
    if (!PerfTour.enabled) return
    LaunchedEffect(Unit) {
        val nav = actions.nav
        val refresh = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate
            .takeIf { it > 0 } ?: 60
        val report = StringBuilder()
        // The composition runs on the window's thread, so this is it.
        val sampler = if (PerfTour.stacks) EdtSampler(Thread.currentThread()) else null
        report.appendLine("Nexfy desktop, frame timing. Screen ${refresh} Hz (a frame every ${"%.1f".format(1000.0 / refresh)} ms)")
        report.appendLine("step                          frames   fps   p50 ms  p95 ms  p99 ms  max ms  missed  cpu%  1st ms")
        // Let the first page and its covers settle before anything is timed.
        delay(6_000)
        // The user's own fullest playlist: a real table to open and scroll, not
        // one of Spotify's generated ones that arrive empty (the DJ).
        val playlist = runCatching { actions.core.myPlaylists(false) }.getOrNull().orEmpty()
            .filter { it.owner != "Spotify" && it.trackCount > 0u }
            .maxByOrNull { it.trackCount }

        suspend fun step(name: String, run: suspend () -> Unit) {
            val gaps = ArrayList<Double>(2048)
            val cpu0 = processCpuNanos()
            val wall0 = System.nanoTime()
            sampler?.step = name
            println("perf: $name")
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
                // A step that never ends (a window minimised stops the frames a
                // scroll waits for) is reported as such rather than hanging the run.
                if (withTimeoutOrNull(STEP_LIMIT_MS) { run() } == null) report.appendLine("$name: did not finish in ${STEP_LIMIT_MS / 1000}s")
                clock.cancel()
            }
            sampler?.step = null
            val wall = System.nanoTime() - wall0
            val cpu = (processCpuNanos() - cpu0).toDouble() / wall * 100 / Runtime.getRuntime().availableProcessors()
            report.appendLine(line(name, gaps, refresh, cpu))
            delay(600)
        }

        val home = nav.list("root:${Tab.Home.name}")
        step("parado na Início (2s)") { delay(2_000) }
        // The rest runs with music playing here, as the app is used: the
        // playing bar's clock and bars, a real song in the panel, words in
        // Tocando agora. Paused again at the end if the tour started it.
        val bridge = actions.services.bridge
        val startedHere = playlist != null && bridge.state.value.track == null
        if (startedHere) {
            step("começar a tocar") {
                actions.playCollection(Route.Playlist(playlist!!.id))
                var waited = 0
                while (!bridge.state.value.playing && waited < 40) {
                    delay(250)
                    waited++
                }
                delay(1_500)
            }
        }
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
            // The same page again: what is left is the page's own cost, not the
            // first time the JVM met its code.
            step("abrir playlist de novo") {
                actions.open(Route.Playlist(playlist.id), Peek(playlist.name, playlist.owner, playlist.imageUrl))
                delay(1_500)
            }
            step("voltar de novo") {
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
        step("abrir a Biblioteca") {
            nav.tab = Tab.Library
            delay(1_200)
        }
        step("rolar a Biblioteca") {
            val list = nav.list("root:${Tab.Library.name}")
            repeat(2) {
                list.animateScrollBy(1_600f)
                list.animateScrollBy(-1_600f)
            }
        }
        step("abrir Curtidas") {
            actions.open(Route.Liked)
            delay(1_800)
        }
        step("rolar Curtidas") {
            val list = nav.list(Route.Liked.key)
            repeat(2) {
                list.animateScrollBy(3_000f)
                list.animateScrollBy(-3_000f)
            }
        }
        step("voltar de Curtidas") {
            nav.pop()
            delay(1_000)
        }
        step("buscar (digitar)") {
            nav.tab = Tab.Search
            delay(500)
            for (n in 1..PERF_QUERY.length) {
                nav.searchQuery = PERF_QUERY.take(n)
                delay(110)
            }
            delay(2_000)
        }
        val artist = runCatching { actions.core.search(PERF_QUERY).artists.firstOrNull() }.getOrNull()
        if (artist != null) {
            step("abrir artista") {
                actions.open(Route.Artist(artist.id), Peek(artist.name, imageUrl = artist.imageUrl))
                delay(1_800)
            }
            step("rolar artista") {
                val list = nav.list(Route.Artist(artist.id).key)
                list.animateScrollBy(1_800f)
                list.animateScrollBy(-1_800f)
            }
            step("voltar do artista") {
                nav.pop()
                delay(1_000)
            }
        }
        nav.searchQuery = ""
        nav.tab = Tab.Home
        delay(800)
        step("Tocando agora com letra") {
            controls.setNowPlaying(true)
            delay(700)
            controls.setLyrics(true)
            delay(3_000)
            controls.setNowPlaying(false)
            delay(900)
        }

        if (startedHere && bridge.state.value.playing) actions.services.commands.playPause()
        val playedAll = bridge.state.value.track != null
        report.appendLine(if (playedAll) "com música tocando: ${bridge.state.value.track?.title}" else "sem música tocando")
        val heap = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }
        report.appendLine("heap em uso no fim: $heap MB")
        sampler?.report(report)
        val text = report.toString()
        println(text)
        runCatching { nexfyDataDir().resolve("perf.txt").writeText(text) }
        // A clean exit, so the JVM writes its class archive (see the desktop
        // build) and the next run measures a launch that has one.
        exitProcess(0)
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
    // The worst of the first few frames: what the step's own change cost,
    // a new screen composed or a panel laid out, apart from what follows.
    val first = gaps.take(4).max()
    return "%-28s %6d %5.0f %7.1f %7.1f %7.1f %7.1f %7d %5.1f %7.1f".format(
        name, gaps.size, gaps.size / (total / 1000), pct(.5), pct(.95), pct(.99), sorted.last(), missed, cpu, first,
    )
}
