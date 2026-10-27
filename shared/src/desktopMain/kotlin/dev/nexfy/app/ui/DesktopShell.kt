package dev.nexfy.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.AlbumSort
import dev.nexfy.app.NexfyServices
import dev.nexfy.app.PlaylistSort
import dev.nexfy.app.elapsedRealtimeMs
import dev.nexfy.app.pinnedFirst
import dev.nexfy.app.playScores
import dev.nexfy.app.playback.PlaybackSnapshot
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.GlassBackdrop
import dev.nexfy.app.ui.design.artInk
import dev.nexfy.app.ui.design.GlassLevel
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.LocalHaze
import dev.nexfy.app.ui.design.LocalPlaying
import dev.nexfy.app.ui.design.LocalPointer
import dev.nexfy.app.ui.design.LocalScreenScope
import dev.nexfy.app.ui.design.LocalSharedScope
import dev.nexfy.app.ui.design.LocalShellHeader
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.PillSelector
import dev.nexfy.app.ui.design.PlayingBars
import dev.nexfy.app.ui.design.ShimmerDriver
import dev.nexfy.app.ui.design.ThinSlider
import dev.nexfy.app.ui.design.durationLabel
import dev.nexfy.app.ui.design.glassPane
import dev.nexfy.app.ui.design.glassSource
import dev.nexfy.app.ui.design.liquidGlass
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.player.DevicesSheet
import dev.nexfy.app.ui.player.LyricsState
import dev.nexfy.app.ui.player.LyricsView
import dev.nexfy.app.ui.player.PlayPauseGlyph
import dev.nexfy.app.ui.player.TrackActionsSheet
import dev.nexfy.app.ui.theme.Hairline
import dev.nexfy.app.ui.theme.accentColor
import dev.nexfy.app.ui.theme.isDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.nexfy_core.AlbumDetail
import uniffi.nexfy_core.RepeatMode
import uniffi.nexfy_core.Track

/**
 * What the window itself can do, lent to the shell so it can draw the
 * frame rather than leave it to the system: the buttons every window has,
 * and the strip that moves it.
 */
interface WindowFrame {
    val maximized: Boolean

    fun minimize()

    fun toggleMaximize()

    fun close()

    /** Wraps the strip the window is dragged by. */
    @Composable
    fun DragArea(content: @Composable () -> Unit)
}

/** How tall the title strip is. */
private val TitleHeight = 42.dp

/** How tall the playing bar is, and how much room the pages leave under them for it. */
private val BarHeight = 84.dp
private val SidebarWidth = 268.dp
private val PanelWidth = 332.dp

/** The sidebar folded to covers alone, to make room for the song's panel. */
private val RailWidth = 78.dp

/** The seek line never steps faster than this, however long the window makes it. */
private const val LINE_STEP_MIN_MS = 33L

/**
 * The app on a computer: a column of the library on the left, the page in
 * the middle, what is playing along the bottom, and the song's own panel on
 * the right when it is asked for. The arrangement is the one every desktop
 * music app has, so nothing has to be learnt; the material is Nexfy's, so
 * the glass bar refracts the page scrolling behind it.
 *
 * The phone's [NexfyRoot] shows the same screens under a tab dock and a
 * player that pulls up, which is right for a thumb and wrong for a mouse.
 */
@Composable
fun NexfyDesktop(services: NexfyServices, openBrowser: (String) -> Unit, frame: WindowFrame? = null) {
    val c = MaterialTheme.colorScheme
    val dark = c.isDark
    val nav = remember { NavState() }
    val scope = rememberCoroutineScope()
    val actions = remember { AppActions(services, nav, openBrowser, scope) }
    val snapshot by services.bridge.state.collectAsState()
    val backdrop = remember { GlassBackdrop() }
    var panelOpen by remember { mutableStateOf(false) }
    var panelTab by remember { mutableStateOf(PanelTab.Queue) }
    var nowPlaying by remember { mutableStateOf(false) }
    // The strip's field is the one search box of the window, so the shortcut
    // that reaches for it is held here, beside the key dispatch that fires it.
    val searchFocus = remember { FocusRequester() }

    ShimmerDriver()
    // The media keys, for as long as this shell is up.
    DisposableEffect(actions) {
        DesktopKeys.handler = { key, ctrl ->
            val media = !DesktopKeys.systemMedia
            when {
                media && (key == Key.MediaPlayPause || key == Key.MediaPlay || key == Key.MediaPause) -> {
                    actions.playPause()
                    true
                }
                (media && key == Key.MediaNext) || (ctrl && key == Key.DirectionRight) -> {
                    actions.next()
                    true
                }
                (media && key == Key.MediaPrevious) || (ctrl && key == Key.DirectionLeft) -> {
                    actions.previous()
                    true
                }
                // Ctrl+F is where every app puts "find": the strip's field
                // takes the caret, whatever page is open.
                ctrl && key == Key.F -> {
                    runCatching { searchFocus.requestFocus() }
                    true
                }
                else -> false
            }
        }
        onDispose { DesktopKeys.handler = null }
    }
    // Esc goes back a page, then out of a tab, as the phone's back does.
    BackGesture(enabled = nav.stack().isNotEmpty()) { nav.pop() }
    BackGesture(enabled = nav.stack().isEmpty() && nav.tab != Tab.Home) { nav.tab = Tab.Home }
    // Registered last, so it is the first asked: the layer on top leaves
    // before the page under it does, as the phone's player does.
    BackGesture(enabled = nowPlaying) { nowPlaying = false }

    CompositionLocalProvider(
        LocalHaze provides backdrop,
        LocalBottomSpace provides BarHeight,
        LocalPlaying provides snapshot.playing,
        // The strip below carries the name, the way back and the buttons, and
        // the input is a mouse: the screens leave out what belongs to a phone.
        LocalShellHeader provides true,
        LocalPointer provides true,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(c.background)) {
            // Three columns at their full width need a wide window. Where
            // there is less, the sidebar folds to a rail of covers rather
            // than the panel being refused: the words and the queue are what
            // the user asked to see, and the page stays wide enough to read.
            // Asked to stay wide, it keeps its names and takes instead the
            // share of the window the other two columns can spare.
            val wide by services.library.wideSidebar.collectAsState()
            val tight = panelOpen && maxWidth < 1180.dp
            val rail = tight && !wide
            val open = if (tight) minOf(SidebarWidth, (maxWidth - PanelWidth) * .36f) else SidebarWidth
            val sidebar by animateDpAsState(
                if (rail) RailWidth else open,
                Motion.standard(Motion.Medium),
                label = "sidebar",
            )
            Column(Modifier.fillMaxSize()) {
            TitleBar(actions, frame, searchFocus)
            // Tocando agora takes everything under the strip: the library, the
            // page and the bar step aside for it, and the strip itself stays so
            // an undecorated window can still be moved, minimised and closed.
            // It rises out of the playing bar, the way the phone's player pulls
            // up from its mini player, and sinks back into it on the way out.
            AnimatedContent(
                nowPlaying,
                Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    if (targetState) {
                        Motion.playerRise togetherWith fadeOut(Motion.exit())
                    } else {
                        fadeIn(Motion.enter()) togetherWith Motion.playerSink
                    }.using(SizeTransform(clip = false)).apply { targetContentZIndex = if (targetState) 1f else -1f }
                },
                label = "nowPlaying",
            ) { full ->
            if (full) {
                NowPlaying(snapshot, actions, Modifier.fillMaxSize()) { nowPlaying = false }
            } else {
            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    actions, rail,
                    // The fold is only ever in the way on a narrow window, so
                    // that is the only place it is offered.
                    fold = { services.library.setWideSidebar(!wide) }.takeIf { tight },
                    modifier = Modifier.width(sidebar).fillMaxHeight(),
                )
                Page(actions, Modifier.weight(1f).fillMaxHeight().glassSource(backdrop))
                // The song's column opens out from the page's edge rather than
                // appearing whole, and folds back into it.
                AnimatedVisibility(
                    panelOpen,
                    enter = expandHorizontally(Motion.standard(), Alignment.Start) + fadeIn(Motion.enter()),
                    exit = shrinkHorizontally(Motion.standard(Motion.Short + 80), Alignment.Start) + fadeOut(Motion.exit()),
                ) {
                    SongPanel(snapshot, actions, panelTab, { panelTab = it }, Modifier.width(PanelWidth).fillMaxHeight())
                }
            }
            }
            }
            }
            AnimatedVisibility(
                !nowPlaying,
                Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 2 },
                exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { it / 2 },
            ) {
                PlayingBar(
                    snapshot, actions,
                    panelOpen = panelOpen,
                    onPanel = { tab: PanelTab ->
                        panelOpen = !(panelOpen && panelTab == tab)
                        panelTab = tab
                    },
                    onExpand = { nowPlaying = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            NoticeBar(
                services.edits,
                Modifier.align(Alignment.BottomCenter).padding(bottom = BarHeight + 18.dp, start = 24.dp, end = 24.dp),
            )
            if (actions.showDevices.value) {
                DevicesSheet(snapshot, actions) { actions.showDevices.value = false }
            }
            LinkOpener(actions)
        }
    }
}

/**
 * The window's own strip: the mark and the way back on the left, the search
 * field standing in the middle of the bar, and the three buttons on the
 * right, drawn flat and round the way a Linux or a macOS window wears them
 * rather than as the system's grey rectangles.
 *
 * The band that moves the window lies *under* the three of them instead of
 * around them, so a drag that starts on the field is the field's own while
 * everything between the controls is still the window's handle, the
 * double-click that fills the screen included. A strip hit nowhere falls
 * through to it, which is what every gap here is.
 */
@Composable
private fun TitleBar(actions: AppActions, frame: WindowFrame?, search: FocusRequester) {
    val c = MaterialTheme.colorScheme
    val nav = actions.nav
    Box(Modifier.fillMaxWidth().height(TitleHeight)) {
        if (frame != null) frame.DragArea { Box(Modifier.fillMaxSize()) }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No wordmark here: the sidebar carries it, directly below, and
            // the name twice within an inch of itself reads as a mistake.
            //
            // No arrow forward either: a tab's stack only ever loses its top,
            // so there is never a page ahead to return to.
            Spacer(Modifier.width(2.dp))
            BackButton(enabled = nav.stack().isNotEmpty()) { nav.pop() }
            Spacer(Modifier.weight(1f))
            SearchField(actions, search, Modifier.weight(1.6f))
            Spacer(Modifier.weight(1f))
            if (frame != null) {
                WindowButton(Icons.Rounded.Remove, "Minimizar", frame::minimize)
                WindowButton(
                    if (frame.maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare,
                    if (frame.maximized) "Restaurar" else "Maximizar",
                    frame::toggleMaximize,
                    glyph = 13.dp,
                )
                WindowButton(Icons.Rounded.Close, "Fechar", frame::close, danger = true)
            }
        }
    }
}

/**
 * Where a search starts, from whatever page is open. The field does not
 * search: Enter hands the words to the Buscar screen, which is the one that
 * knows how, through the recent searches both sides already read — so the
 * screen's own field stays the single place the query is typed and cleared.
 */
@Composable
private fun SearchField(actions: AppActions, search: FocusRequester, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val nav = actions.nav
    val accent = accentColor()
    // The Buscar screen's field is this same query (see NavState.searchQuery),
    // so whichever one is typed in, both read it and one search runs.
    var query by nav::searchQuery
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    // The same accent ring the Buscar screen's field lights up with, at the
    // size a title strip has room for.
    val ring by animateFloatAsState(if (focused) 1f else 0f, Motion.standard(), label = "searchRing")
    val icon by animateColorAsState(if (focused) accent else c.onSurfaceVariant, Motion.standard(), label = "searchIcon")
    val submit: () -> Unit = {
        // Recording the query is the Buscar screen's to do, once it knows the
        // search found something; here it is only shown.
        if (query.trim().length >= 2) {
            nav.tab = Tab.Search
            nav.popToRoot()
        }
    }
    Row(
        modifier.height(28.dp)
            .glassPane(CircleShape, c.surfaceVariant.copy(alpha = .6f), c.isDark)
            .drawWithContent {
                drawContent()
                if (ring > 0f) {
                    drawRoundRect(
                        accent.copy(alpha = .7f * ring),
                        cornerRadius = CornerRadius(size.height / 2),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = icon, modifier = Modifier.size(15.dp))
        BasicTextField(
            query, { query = it },
            Modifier.weight(1f).padding(horizontal = 8.dp).focusRequester(search)
                .onPreviewKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (event.type == KeyEventType.KeyDown && enter) {
                        submit()
                        true
                    } else {
                        false
                    }
                },
            singleLine = true,
            interactionSource = focus,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
            cursorBrush = SolidColor(c.onSurface),
            decorationBox = { field ->
                if (query.isEmpty()) {
                    Text(
                        "O que queres ouvir?", color = c.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                field()
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, "Limpar busca", tint = c.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape).clickable { query = "" }.padding(4.dp).size(14.dp),
            )
        }
    }
}

/** Greyed out rather than gone, so the strip does not shift as pages open. */
@Composable
private fun BackButton(enabled: Boolean, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tint by animateColorAsState(
        if (enabled) c.onSurface else c.onSurfaceVariant.copy(.35f),
        label = "back",
    )
    Box(
        Modifier.size(30.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onBack),
        Alignment.Center,
    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", tint = tint, modifier = Modifier.size(18.dp)) }
}

/** A flat round button that only shows its ground under the pointer. */
@Composable
private fun WindowButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    glyph: Dp = 15.dp,
) {
    val c = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val over by hover.collectIsHoveredAsState()
    val ground by animateColorAsState(
        when {
            !over -> Color.Transparent
            danger -> Color(0xFFE5484D)
            else -> c.onSurface.copy(.12f)
        },
        label = "windowButton",
    )
    val tint by animateColorAsState(
        if (over && danger) Color.White else c.onSurfaceVariant,
        label = "windowGlyph",
    )
    Box(
        Modifier.padding(start = 4.dp).size(30.dp).clip(CircleShape).background(ground)
            .hoverable(hover).clickable(onClick = onClick),
        Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(glyph)) }
}

// ------------------------------------------------------------------ sidebar

/**
 * The wordmark, where to go, and the library itself: one standing panel.
 * Folded to a [rail], the same column keeps only what a cover and an icon
 * can say, which is enough to find a playlist already known by its art.
 */
@Composable
private fun Sidebar(actions: AppActions, rail: Boolean, fold: (() -> Unit)?, modifier: Modifier) {
    val nav = actions.nav
    Column(modifier.padding(start = 12.dp, top = 12.dp, end = 6.dp, bottom = BarHeight + 16.dp)) {
        Panel {
            if (rail) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    fold?.let { NavIcon(Icons.Rounded.KeyboardDoubleArrowRight, "Expandir a biblioteca", it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    NavIcon(Icons.Rounded.Settings, "Ajustes") { actions.open(Route.Settings) }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "nexfy", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, letterSpacing = (-1).sp,
                    )
                    fold?.let { NavIcon(Icons.Rounded.KeyboardDoubleArrowLeft, "Recolher a biblioteca", it) }
                    NavIcon(Icons.Rounded.Settings, "Ajustes") { actions.open(Route.Settings) }
                }
            }
            NavRow(Icons.Rounded.Home, "Início", nav.tab == Tab.Home && nav.stack().isEmpty(), rail) {
                nav.tab = Tab.Home
                nav.popToRoot()
            }
            NavRow(Icons.Rounded.Search, "Buscar", nav.tab == Tab.Search && nav.stack().isEmpty(), rail) {
                nav.tab = Tab.Search
                nav.popToRoot()
            }
            NavRow(Icons.Rounded.LibraryMusic, "Biblioteca", nav.tab == Tab.Library && nav.stack().isEmpty(), rail) {
                nav.tab = Tab.Library
                nav.popToRoot()
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(12.dp))
        Panel(Modifier.weight(1f)) { LibraryList(actions, rail) }
    }
}

/** A panel of the shell: the page's own surface, framed by a hairline. */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface)
            .border(Hairline, if (c.isDark) Color.White.copy(.07f) else c.outline, shape),
    ) { content() }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, selected: Boolean, rail: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val accent = accentColor()
    val tint = if (selected) accent else c.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(.12f) else Color.Transparent)
            .clickable { haptics.tick(); onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (rail) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(icon, if (rail) label else null, tint = tint, modifier = Modifier.size(20.dp))
        if (!rail) {
            Text(
                label, Modifier.padding(start = 14.dp),
                color = if (selected) c.onSurface else c.onSurfaceVariant,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun NavIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Icon(
        icon, description, tint = c.onSurfaceVariant,
        modifier = Modifier.clip(CircleShape).clickable { haptics.tick(); onClick() }.padding(8.dp).size(20.dp),
    )
}

/** The user's playlists and saved albums, the way the library page reads them. */
@Composable
private fun LibraryList(actions: AppActions, rail: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val library = actions.services.library
    val version by actions.services.edits.version.collectAsState()
    val playlists = rememberLoaded("side-pl", reloadOn = version) { actions.core.myPlaylists(it) }
    val albums = rememberLoaded("side-al", reloadOn = version) { actions.core.savedAlbums(it) }
    // The home page's own key, so the plays it already fetched are on screen
    // from the first frame instead of the column reordering itself later.
    val recent = rememberLoaded("home-recent") { actions.core.recentlyPlayed(it) }
    val pinned by library.pinned.collectAsState()
    val plays by library.plays.collectAsState()
    // What is played most stands at the top, with anything pinned above it:
    // the order the library page and the home page already use.
    val scores = playScores(plays, recent.state.value.orEmpty())
    val orderedPlaylists = playlists.state.value?.let {
        pinnedFirst(PlaylistSort.MostPlayed.apply(it, scores), pinned) { p -> "pl:${p.id}" }
    }
    val orderedAlbums = albums.state.value?.let {
        pinnedFirst(AlbumSort.MostPlayed.apply(it, scores), pinned) { a -> "al:${a.id}" }
    }
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)) {
        if (!rail) {
            item {
                Text(
                    "Sua biblioteca",
                    Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp),
                    color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        item {
            ShelfRow(null, "Curtidas", "Músicas", Icons.Rounded.Favorite, rail) { actions.open(Route.Liked) }
        }
        items(orderedPlaylists.orEmpty(), key = { "pl:${it.id}" }) { p ->
            ShelfRow(p.imageUrl, p.name, "Playlist · ${p.owner}", rail = rail) {
                actions.open(Route.Playlist(p.id), Peek(p.name, p.owner, p.imageUrl))
            }
        }
        items(orderedAlbums.orEmpty(), key = { "al:${it.id}" }) { a ->
            ShelfRow(a.imageUrl, a.name, "Álbum · ${a.artists}", rail = rail) {
                actions.open(Route.Album(a.id), Peek(a.name, a.artists, a.imageUrl))
            }
        }
    }
}

@Composable
private fun ShelfRow(
    art: String?,
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    rail: Boolean = false,
    place: Int? = null,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (rail) 3.dp else 1.dp)
            .clip(RoundedCornerShape(12.dp)).clickable { haptics.tick(); onClick() }
            .padding(horizontal = if (rail) 6.dp else 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (rail) Arrangement.Center else Arrangement.Start,
    ) {
        // In a queue, where a song stands is worth as much as its name.
        place?.let {
            Text(
                "$it", Modifier.widthIn(min = 20.dp).padding(end = 6.dp),
                color = c.onSurfaceVariant.copy(alpha = .7f), fontSize = 12.sp,
                textAlign = TextAlign.End,
            )
        }
        // Folded, the cover is the only name the row has, so it is given the
        // space the two lines of words took.
        val size = if (rail) 46.dp else 42.dp
        if (icon != null) {
            Box(
                Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(accentColor().copy(.18f)),
                Alignment.Center,
            ) { Icon(icon, if (rail) title else null, tint = accentColor(), modifier = Modifier.size(20.dp)) }
        } else {
            Cover(art, Modifier.size(size), radius = 10.dp)
        }
        if (!rail) {
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, color = c.onSurface, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// --------------------------------------------------------------------- page

/**
 * The pages that are a column of words and switches, which read badly
 * stretched across a wide window. Lists and art fill it as they should.
 */
private val Reading = setOf(
    Route.Settings, Route.Equalizer, Route.Guide, Route.Stats, Route.Friends, Route.Downloads,
)

/** The page itself, with the way back above it when there is one. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Page(actions: AppActions, modifier: Modifier) {
    val nav = actions.nav
    Column(modifier.padding(start = 6.dp, top = 12.dp, end = 12.dp)) {
        Panel(Modifier.weight(1f)) {
            // The phone's own page motion, inside the panel: a page opened
            // comes forward out of depth, Esc sends it back into it, and
            // another tab slides in from its side. A card's cover travels to
            // the header of the page it opens, as it does on the phone.
            SharedTransitionLayout(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalSharedScope provides this) {
                    AnimatedContent(
                        nav.screen,
                        Modifier.fillMaxSize(),
                        transitionSpec = {
                            when {
                                initialState.tab != targetState.tab ->
                                    Motion.tabSwitch(targetState.tab.ordinal - initialState.tab.ordinal)
                                targetState.depth < initialState.depth -> Motion.pageBack
                                else -> Motion.pageEnter togetherWith Motion.pageExit using SizeTransform(clip = false)
                            }
                        },
                        label = "page",
                    ) { screen ->
                        CompositionLocalProvider(LocalScreenScope provides this) {
                            val held = screen.route in Reading
                            Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
                                ScreenContent(
                                    screen, actions,
                                    if (held) Modifier.widthIn(max = 760.dp).fillMaxHeight() else Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------- playing bar

/**
 * What is playing, along the bottom: the song on the left, the transport
 * and the line in the middle, the devices and the volume on the right. It
 * is liquid glass over the page, so the list scrolling behind it shows
 * through, blurred and bent at the rim.
 */
@Composable
private fun PlayingBar(
    s: PlaybackSnapshot,
    actions: AppActions,
    panelOpen: Boolean,
    onPanel: ((PanelTab) -> Unit)?,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    val c = MaterialTheme.colorScheme
    val dark = c.isDark
    val track = s.track
    val shape = RoundedCornerShape(22.dp)
    var menu by remember { mutableStateOf(false) }
    if (menu && track != null) {
        TrackActionsSheet(track, actions, onNavigate = { menu = false }) { menu = false }
    }
    Row(
        modifier.height(BarHeight).liquidGlass(shape, dark, GlassLevel.Strong)
            // The bar floats over the page and is seen through, so the page
            // reads as being under it — and a click on the bar's own empty
            // space used to go through to whatever row was under there and
            // play it. Everything inside the bar still takes its own clicks;
            // this only stops the rest falling through.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The song.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Cover(track?.coverUrl, Modifier.size(52.dp), radius = 12.dp)
            // A new song's name rises into place as the last one leaves, so a
            // skip reads as a change rather than a flicker.
            AnimatedContent(
                track,
                Modifier.padding(start = 12.dp).weight(1f),
                transitionSpec = {
                    (fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 3 }) togetherWith
                        (fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { -it / 3 })
                },
                contentAlignment = Alignment.CenterStart,
                label = "barSong",
                contentKey = { it?.uri },
            ) { song ->
                Column {
                    Text(
                        song?.title ?: "Nada tocando", color = c.onSurface, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song?.artists.orEmpty(), color = c.onSurfaceVariant, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (track != null) {
                NavIcon(Icons.Rounded.MoreHoriz, "Mais opções") { menu = true }
            }
        }
        // The transport and the line.
        Column(
            Modifier.weight(1.4f).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Transport(s, actions)
            Spacer(Modifier.height(4.dp))
            SeekLine(s, actions)
        }
        // Where it plays, and how loud.
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (s.playing) PlayingBars(accentColor(), Modifier.size(16.dp).padding(end = 2.dp))
            onPanel?.let { open ->
                NavIcon(Icons.Rounded.Lyrics, "Letra") { open(PanelTab.Lyrics) }
                NavIcon(Icons.AutoMirrored.Rounded.QueueMusic, "A seguir") { open(PanelTab.Queue) }
            }
            // The whole window for the song, the way the phone's bar pulls up
            // into the full player.
            NavIcon(Icons.Rounded.OpenInFull, "Tocando agora", onExpand)
            NavIcon(Icons.Rounded.Devices, "Dispositivos") { actions.openDevices() }
            Volume(s, actions)
        }
    }
}

@Composable
private fun Transport(s: PlaybackSnapshot, actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val haptics = rememberHaptics()
    val shuffle = s.queue.shuffle
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            Icons.Rounded.Shuffle, if (shuffle) "Desligar aleatório" else "Aleatório",
            tint = if (shuffle) accent else c.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).clickable { haptics.tick(); actions.setShuffle(!shuffle) }
                .padding(7.dp).size(18.dp),
        )
        NavIcon(Icons.Rounded.SkipPrevious, "Anterior") { actions.previous() }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.onSurface)
                .clickable { haptics.press(); actions.playPause() },
            Alignment.Center,
        ) { PlayPauseGlyph(s.playing, c.surface, Modifier.size(18.dp)) }
        NavIcon(Icons.Rounded.SkipNext, "Próxima") { actions.next() }
        val repeat = s.queue.repeat
        Icon(
            if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "Repetir",
            tint = if (repeat == RepeatMode.OFF) c.onSurfaceVariant else accent,
            modifier = Modifier.clip(CircleShape).clickable {
                haptics.tick()
                actions.setRepeat(
                    when (repeat) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    },
                )
            }.padding(7.dp).size(18.dp),
        )
    }
}

/**
 * The line, with where the song is and how long it runs either side of it.
 * [large] is the full-window player, where the clock is read from a chair
 * rather than a hand's length away and sits over living artwork.
 */
@Composable
internal fun SeekLine(s: PlaybackSnapshot, actions: AppActions, modifier: Modifier = Modifier, large: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val duration = s.track?.durationMs?.toLong() ?: 0L
    // Dragged, the head follows the mouse and the clock with it; let go, the
    // player takes over again.
    var dragging by remember { mutableStateOf(false) }
    var held by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    // The clock shows whole seconds, so it changes once a second, and the line
    // moves a pixel at a time: for a song of minutes, a few steps a second.
    // Paused, neither ticks. Both used to follow every frame of the screen,
    // repainting the whole window and the glass bar's blur at 60 or 144 Hz.
    val position by produceState(s.currentPositionMs(), s) {
        value = s.currentPositionMs()
        if (!s.playing || s.loading) return@produceState
        while (true) {
            delay(1_000 - value.mod(1_000L) + 5)
            value = s.currentPositionMs()
        }
    }
    val line by produceState(s.currentPositionMs(), s, width, duration) {
        value = s.currentPositionMs()
        if (!s.playing || s.loading || duration <= 0 || width <= 0) return@produceState
        val step = (duration / width).coerceIn(LINE_STEP_MIN_MS, 1_000L)
        while (true) {
            delay(step)
            value = s.currentPositionMs()
        }
    }
    val shown = if (dragging) (held * duration).toLong() else position.coerceIn(0L, duration.coerceAtLeast(0L))
    val clock = if (large) artInk().copy(alpha = .66f) else c.onSurfaceVariant
    val size = if (large) 13.sp else 11.sp
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(durationLabel(shown), color = clock, fontSize = size)
        ThinSlider(
            // Read while drawing: the line's steps repaint it, never recompose.
            progress = {
                val at = if (dragging) (held * duration).toLong() else line
                if (duration <= 0) 0f else (at.toFloat() / duration).coerceIn(0f, 1f)
            },
            onChange = { dragging = true; held = it },
            onDone = {
                dragging = false
                if (duration > 0) actions.seek((held * duration).toLong())
            },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).onSizeChanged { width = it.width },
            enabled = s.track != null,
            waiting = { s.loading },
            lit = s.playing,
        )
        Text(durationLabel(duration), color = clock, fontSize = size)
    }
}

/**
 * How loud: this machine's own output, or the device playing elsewhere
 * when one is. It follows the slider as it is dragged rather than waiting
 * for it to be let go, which is what a volume should do.
 */
@Composable
internal fun Volume(s: PlaybackSnapshot, actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val output = actions.services.output
    val remote = s.remote
    val here by output.volume.collectAsState()
    var held by remember(remote?.id) {
        mutableFloatStateOf(if (remote == null) here.toFloat() else (remote.volume?.toInt() ?: 50) / 100f)
    }
    val level = if (remote == null) here.toFloat() else held
    val icon = when {
        level <= 0.001f -> Icons.AutoMirrored.Rounded.VolumeOff
        level < .5f -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    }
    Row(Modifier.width(132.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, "Volume", tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
        ThinSlider(
            progress = { level },
            onChange = {
                held = it
                if (remote == null) output.setVolume(it.toDouble())
            },
            onDone = { if (remote != null) actions.setVolume((held * 100).toInt()) },
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

// -------------------------------------------------------------- song panel

/** What the right-hand column is showing. */
private enum class PanelTab(val label: String) {
    Art("Capa"),
    Details("Detalhes"),
    Queue("A seguir"),
    Lyrics("Letra"),
}

/**
 * The song's own column: its art, what plays after it, and the words. The
 * phone keeps all three behind the player's own gestures; a window has the
 * room to leave one of them standing open beside the page.
 */
@Composable
private fun SongPanel(s: PlaybackSnapshot, actions: AppActions, tab: PanelTab, onTab: (PanelTab) -> Unit, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val track = s.track
    Column(modifier.padding(start = 6.dp, top = 12.dp, end = 12.dp, bottom = BarHeight + 16.dp)) {
        Panel(Modifier.weight(1f)) {
            // The cover's own tab says all this and larger, so the strip of
            // it stands down there rather than saying it twice.
            if (tab != PanelTab.Art) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(track?.coverUrl, Modifier.size(44.dp), radius = 10.dp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            track?.title ?: "Nada tocando", color = c.onSurface, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track?.artists.orEmpty(), color = c.onSurfaceVariant, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(14.dp))
            }
            PillSelector(
                PanelTab.entries, tab, { it.label }, onTab,
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            )
            // The next tab slides in from the side its pill stands on.
            AnimatedContent(
                tab,
                Modifier.weight(1f),
                transitionSpec = { Motion.tabSwitch(targetState.ordinal - initialState.ordinal) },
                label = "panelTab",
            ) { shown ->
                when (shown) {
                    PanelTab.Art -> CoverPane(s, Modifier.fillMaxSize())
                    PanelTab.Details -> DetailsPane(s, actions, Modifier.fillMaxSize())
                    PanelTab.Queue -> QueueList(s, actions, Modifier.fillMaxSize())
                    PanelTab.Lyrics -> LyricsPane(s, actions, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * The cover alone, as large as the column allows, with the song named
 * under it. Nothing to read and nothing to do: the record sleeve, for
 * when the window is left on the second monitor.
 */
@Composable
private fun CoverPane(s: PlaybackSnapshot, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val track = s.track
    Column(
        modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(track?.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), radius = 18.dp)
        Spacer(Modifier.height(18.dp))
        Text(
            track?.title ?: "Nada tocando", color = c.onSurface, fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (track != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                track.artists, color = c.onSurfaceVariant, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            if (track.album.isNotBlank() && track.album != track.title) {
                Spacer(Modifier.height(10.dp))
                Text(
                    track.album, color = c.onSurfaceVariant.copy(alpha = .7f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The song's own facts, the way a sleeve prints them: the name of each in
 * the quiet column, what it says in the other. Only what the core really
 * carries is here, and a fact it does not carry leaves no row behind — a
 * blank line reads as a fault, where a missing one reads as nothing.
 */
@Composable
private fun DetailsPane(s: PlaybackSnapshot, actions: AppActions, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val info = s.track
    if (info == null) {
        Box(modifier.fillMaxWidth(), Alignment.Center) {
            Text("Nada tocando", color = c.onSurfaceVariant, fontSize = 13.sp)
        }
        return
    }
    // What plays carries only what the bar draws. The catalogue row behind it
    // knows which album the song belongs to, and the album knows the year it
    // came out and where the song stands in it; both are read from the cache
    // first, so the list is filled in the moment the tab is opened again.
    val song by produceState<Track?>(null, info.uri) {
        value = runCatching { actions.core.tracksInfo(listOf(info.uri)) }.getOrNull()?.firstOrNull()
    }
    val albumId = song?.albumId
    val album by produceState<AlbumDetail?>(null, albumId) {
        val id = albumId ?: return@produceState
        value = runCatching { actions.core.album(id, false) }.getOrNull()
    }
    val place = album?.tracks?.indexOfFirst { it.uri == info.uri }?.takeIf { it >= 0 }
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
        DetailRow("Tocando de", playingFrom(s, info.album, actions))
        DetailRow("Álbum", info.album.takeIf { it.isNotBlank() })
        DetailRow("Artistas", info.artists.takeIf { it.isNotBlank() })
        DetailRow("Duração", durationLabel(info.durationMs.toLong()).takeIf { info.durationMs > 0u })
        DetailRow("Ano", album?.album?.year?.takeIf { it.isNotBlank() })
        DetailRow("Faixa", place?.let { i -> album?.let { "${i + 1} de ${it.tracks.size}" } })
    }
}

/** One fact, under a hairline that keeps the two columns reading as a list. */
@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val c = MaterialTheme.colorScheme
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                label, Modifier.width(88.dp),
                color = c.onSurfaceVariant.copy(alpha = .75f), fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                value, Modifier.weight(1f), color = c.onSurface, fontSize = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxWidth().height(Hairline).background(c.onSurface.copy(alpha = .06f)))
    }
}

/**
 * What the queue was started from, named: the album itself, the playlist by
 * the name the library already knows it under, or the user's own songs.
 * Anything else the context can be is left unsaid rather than guessed at.
 *
 * [albumName] is what to call an album context, which the caller already
 * has: while a song plays from an album, that album is the one it is on.
 */
@Composable
internal fun playingFrom(s: PlaybackSnapshot, albumName: String?, actions: AppActions): String? {
    val uri = s.queue.contextUri
    val target = remember(uri) { uri?.let { SpotifyLinks.parse(it) } }
    val playlistId = (target as? LinkTarget.Playlist)?.id
    val playlist by produceState<String?>(null, playlistId) {
        val id = playlistId ?: return@produceState
        value = runCatching { actions.core.knownPlaylist(id)?.name }.getOrNull()
    }
    return when {
        uri == null -> null
        // The collection is a user's own context, which no link parses.
        uri.endsWith(":collection") -> "Músicas curtidas"
        target is LinkTarget.Album -> albumName?.takeIf { it.isNotBlank() }
        target is LinkTarget.Playlist -> playlist
        else -> null
    }
}

/**
 * The songs lined up behind this one, numbered from the one playing now.
 * The cover is the tab beside this one, so the list starts at the music.
 */
@Composable
private fun QueueList(s: PlaybackSnapshot, actions: AppActions, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val next = s.queue.next
    val uris = remember(next) { next.take(30).map { it.uri } }
    val songs by produceState(emptyList<Track>(), uris) {
        value = if (uris.isEmpty()) emptyList() else runCatching { actions.core.tracksInfo(uris) }.getOrDefault(emptyList())
    }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
        itemsIndexed(songs) { i, song ->
            ShelfRow(song.imageUrl, song.title, song.artists, place = i + 1) {
                next.getOrNull(i)?.let { actions.skipTo(it, i) }
            }
        }
        if (songs.isEmpty()) {
            item {
                Text(
                    "A fila está vazia.",
                    Modifier.padding(16.dp),
                    color = c.onSurfaceVariant, fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * The words, scrolling with the song where they are timed. The same view
 * the phone's player shows, which seeks to a line when one is clicked.
 */
@Composable
internal fun LyricsPane(s: PlaybackSnapshot, actions: AppActions, modifier: Modifier) {
    val track = s.track
    if (track == null) {
        Box(modifier.fillMaxWidth(), Alignment.Center) {
            Text("Nada tocando", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        return
    }
    var lyrics by remember(track.uri) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    LaunchedEffect(track.uri) {
        lyrics = try {
            actions.lyrics(track)?.let { LyricsState.Found(it) } ?: LyricsState.None
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LyricsState.Failed
        }
    }
    // Timed lines follow the song, so the position is read every frame; a
    // paused song is read once.
    val position by produceState(s.currentPositionMs(), s) {
        value = s.currentPositionMs()
        if (!s.playing) return@produceState
        while (true) {
            withFrameMillis { }
            value = s.currentPositionMs()
        }
    }
    LyricsView(lyrics, position, actions, modifier.fillMaxWidth().padding(horizontal = 18.dp), playback = s)
}
