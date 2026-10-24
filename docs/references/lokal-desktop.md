# Lokal (Electron/React desktop music player) — arrangement and behaviour reference

A reference for Nexfy's desktop shell, written from a clone of **Lokal**, an
Electron + React + Tailwind local-file music player. It records *where things
sit* and *what they do*, and judges each against Nexfy's desktop as it stands.

**Nothing here is about Lokal's colours, fonts or brand.** Nexfy keeps its own
visual language (liquid glass, living artwork, cover-derived accent, Portuguese
copy). Only arrangement, sizing rules and behaviour transfer.

## How to read this

- **Source** means read in Lokal's React/Electron source; the file and symbol
  are named. Numbers are as written (Tailwind translated: `w-56` → 224px).
- **Screenshot** means inferred from `docs/screenshots/` (converted PNGs at
  `scratchpad/shots/d-*.png`), which show **one window width** only.
- **The screenshots are from an older build than the source.** Confirmed drifts:
  Home's third tab reads `MIX` in the shots, `Flow` in source; the shots show a
  generic greeting, no Made-for-you Cover Flow, only two Discovery providers,
  and shorter Album/Artist action rows. **Source wins on any disagreement.**
- Nexfy citations are file + symbol in this repo.
- Verdicts: `HAVE` / `PARTIAL` / `MISSING` / `REJECT`. `REJECT` means it fights
  Nexfy's visual language or its nature — Nexfy is a Spotify client over a Rust
  core, with no local files, no Soulseek and no audio-quality analysis.

## Nexfy's desktop today, in one paragraph

`shared/src/desktopMain/.../DesktopShell.kt:NexfyDesktop` — an undecorated
window (`desktop/src/main/kotlin/dev/nexfy/desktop/Main.kt`, 1100×760 default,
940×620 minimum) holding a 42dp title strip, then a row of sidebar (268dp) /
page / optional song panel (332dp), with an 84dp glass playing bar floating over
the bottom. `Tocando agora` (`NowPlaying.kt`) replaces all of it below the
strip. Screens are shared with the phone and switch on `LocalPointer`.

---

# 1. The arrangement

## 1.1 Shell regions

Source: `src/App.jsx:3267-3310`, `src/components/TitleBar.jsx:24`,
`src/components/Sidebar.jsx:205`, `src/components/PlayerBar.jsx:321`,
`src/panelWidths.jsx:10-11`.

| Region | Lokal | Nexfy | Verdict |
|---|---|---|---|
| Title bar | 35px, frameless, 3-col grid `minmax(max-content,1fr) / minmax(0,520px) / minmax(0,1fr)` | 42dp, `TitleBar`, back + field + 3 buttons | **HAVE** |
| Left sidebar | 224px, or 72px collapsed | 268dp, or 78dp rail | **HAVE** |
| Main scroll area | `flex-1 min-w-0 overflow-y-auto`, the only scroller | `Page`, one panel | **HAVE** |
| Right panel | 300px default | 332dp `SongPanel` | **HAVE** |
| Player bar | 80px; floating variant inset 12px, docked variant bordered | 84dp, floating, inset 12/10dp | **HAVE** |
| Bottom reserve | `--player-space: 104px` padding every pane | `LocalBottomSpace = BarHeight` | **HAVE** |
| Extra queue/lyrics columns | 320px each, "Independent panels" mode, both open at once | one panel, one tab | **REJECT** — three stacked columns leave no page on a 940dp window |

**Panel widths are draggable and remembered.** `src/panelWidths.jsx`:
sidebar 180–420px (default 224), right panel 260–620px (default 300); a 6px
`role="separator"` strip on the panel's inner edge, `cursor-col-resize`,
**double-click resets to the default**, stored in `localStorage` under
`lokal-panel-widths`. Off by default ("Fixed" vs "Freeform" in Settings).
Nexfy's widths are `val SidebarWidth = 268.dp` / `PanelWidth = 332.dp`
constants. → **MISSING** (drag-to-resize + reset + persistence).

## 1.2 What gives when the window changes

**The one idea worth taking.** Lokal's pages lay out by **the width they are
given**, not the window's (`src/pageWidth.js`, `src/pageBreakpoints.js:3`): a
`ResizeObserver` on `<main>` writes `data-page="sm md lg"`, and Tailwind
variants `@sm`…`@2xl` match it. Breakpoints are the window ones minus the 224px
sidebar: **416 / 544 / 800 / 1056 / 1312px**. Opening the right panel therefore
collapses a page to its narrower layout instead of letting it spill.
Deliberately not a CSS container query (comment `pageWidth.js:10`).

Nexfy gets this free: `BoxWithConstraints` measures the slot, and
`Collection.kt:299` (`maxWidth >= TableWidth`), `:313` (`>= 640.dp`),
`Library.kt:174`, `design/Shell.kt:gridColumns` all read the given width.
→ **HAVE**.

Window-width rules in Lokal's shell are only two: the wordmark hides below 640px
(`TitleBar.jsx:29`), and `max-[1360px]:` shrinks the player bar's side zones
370→320px and **hides the visualizer entirely** (`PlayerBar.jsx:325,481,483`).

Nexfy's own rule (`DesktopShell.kt:249-256`): below **1180dp with the panel
open**, the sidebar folds to a 78dp rail rather than dropping the panel; asked
to stay wide (`wideSidebar`), it instead takes `min(268dp, (maxWidth - 332dp) × .36)`.
Lokal has no equivalent — it lets the user over-narrow the page. → **HAVE**, and
Nexfy's is better; do not copy Lokal's.

| Rule | Verdict |
|---|---|
| Page-relative breakpoints | **HAVE** |
| Automatic sidebar→rail fold under pressure | **HAVE** (Nexfy-only) |
| Player-bar controls dropping at a width | **PARTIAL** — Nexfy's bar never sheds anything; at 940dp the three zones are tight |

## 1.3 Window chrome

Source: `electron/main.js`, `src/index.css:142-143`.

| Thing | Lokal | Nexfy | Verdict |
|---|---|---|---|
| Frame | `frame: false`, own title bar | `undecorated = true` | **HAVE** |
| Drag region | `.titlebar { -webkit-app-region: drag }`, with `input,button,select,textarea` globally `no-drag` | `frame.DragArea` laid *under* the row so gaps fall through | **HAVE** |
| Double-click title bar | maximize | `detectTapGestures(onDoubleTap)` in `Main.kt` | **HAVE** |
| Min size | 960×640, `useContentSize` | 940×620 | **HAVE** |
| Default size | 1400×860 | 1100×760 | **HAVE** |
| **Geometry remembered** | `<userData>/window-state.json`, `{bounds, maximized}`, written via tmp+rename, debounced 250ms on resize/move/maximize/unmaximize, flushed on close; `validWindowBounds` rejects sizes under the minimum or fully off-screen; stores `getNormalBounds()` so a maximized window keeps its restored size | `rememberWindowState(size = DpSize(1100.dp, 760.dp))` — **nothing persisted** | **MISSING** |
| Close to tray | `close_to_tray` setting hides instead of quitting | — | **REJECT** — a tray resident is a different product decision, not a layout one |
| Taskbar progress | `taskbar_progress` draws song progress on the taskbar button | — | **MISSING** (small, Windows-only) |
| Snap | nothing custom; OS handles it. Lokal nudges bounds ±1px (`main.js:898`) to repair Windows' stale hit-test after an overlay unmounts | `dressWindow(window)` in `WindowsChrome.kt` restores corners/resize border | **HAVE** |

## 1.4 Sidebar

Source: `src/components/Sidebar.jsx`, `src/sidebarNav.js`.

Order: wordmark + collapse toggle → account block (avatar, name, sign-out with a
two-step confirm that auto-cancels after 2500ms) → **nine fixed destinations**
(Home, Library, Recap, Albums, Genres, Radio, Artists, Videos, Quality) →
divider → playlists, the only part that scrolls (`flex-1 overflow-y-auto`), with
a header row of three icon buttons (smart playlist, import, new) and a pinned
Liked Songs row above the list. Playlist rows carry a 28px cover.

| Behaviour | Lokal | Nexfy | Verdict |
|---|---|---|---|
| Collapse to icons | explicit toggle, any window width; labels move to `title`/`aria-label`; badge shrinks to a dot | rail exists but is **only offered when `tight`** (`fold = {...}.takeIf { tight }`) | **PARTIAL** — Nexfy cannot collapse on a wide window |
| Collapse remembered | `localStorage['lokal-sidebar-collapsed']` | `LibraryPrefs.wideSidebar` (inverted, `WIDE_SIDEBAR`) | **HAVE** |
| Playlist list scrolls separately | yes | yes, `LibraryList` is its own `LazyColumn` in its own `Panel` | **HAVE** |
| Nav order user-configurable | yes — `sidebarNavOrder`, items demoted below a `HIDDEN_MARK` into a **More** item that expands in place; edited in Settings › Appearance › Navigation | three fixed rows (Início/Buscar/Biblioteca) | **REJECT** — Nexfy has three destinations; reordering three is ceremony |
| Right-click a sidebar item | **none** (verified: no `onContextMenu` in `Sidebar.jsx`) | none | **HAVE** (parity; neither has it) |
| Drag a track onto a sidebar playlist | drop target only: `application/json` `{type:'tracks',...}`, smart playlists refuse, then `api.addToPlaylist` per track | — | **MISSING** |
| Drag to reorder playlists | no (`draggable` absent) | no | **HAVE** |
| Icon-only tooltips | native `title` | **no tooltip API used anywhere in Nexfy** | **MISSING** — a 78dp rail of unlabelled covers needs them |

## 1.5 Screens

All Lokal pages below are source-read; screenshot confirmations are marked.

**Home** (`src/pages/Home.jsx:574`) — `max-w-6xl` (1152px) centred, 24px padding.
Greeting (`text-2xl`) + subtitle, then a 5-tab pill row **Local · Discovery ·
Flow · Releases · History** routed as `/home/<tab>`, then the tab body. The
greeting pair is computed from the user's own recent plays
(`src/homeGreeting.mjs:96`, patterns `repeat → mellow/energetic → night/morning
→ streak → variety → time-of-day`, needs ≥5 entries). Local tab: Made-for-you
Cover Flow (cover `min(320, stage×0.52)`px), Your Mixes
(`grid-cols-2 @md:grid-cols-3`, 6 max), Suggested, Recently Added.
*Screenshot confirms* the tab row and the 3-column mix grid.

Nexfy `Home.kt:HomeScreen` — a greeting `LargeTitle` that collapses on scroll, a
liked tile, then titled carousels (`homeRow` + `Carousel`), skeletons while
pending, `SectionTitle(onOpenAll=)` only under a pointer. Centred max width:
none (lists fill). → Arrangement **HAVE**; tab row **REJECT** (Lokal's tabs
exist to separate local from online, a split Nexfy does not have);
personalised greeting from play history **MISSING** (Nexfy has `greeting()` =
time of day only, `Tabs.kt:104`, and already stores `plays`).

**Library** (`Library.jsx:237`) — small `text-lg uppercase` title, not a hero;
a header row of four selects (source / genre / format+quality / sort) plus a
list↔grid pair; a scan banner; a count line and a page-local search box
(**180ms debounce**, `focus:w-48`, Escape clears); Play All / Play next /
Shuffle pills; Simple↔Advanced toggle; grid is
`grid-cols-3 @sm:grid-cols-4 @md:grid-cols-5`. *Screenshot* shows the older
layout (an Albums pill, a Music Folder card with Rescan/Change).

Nexfy `Library.kt:LibraryScreen` — `LargeTitle`, a `PillSelector` of
Playlists/Álbuns/Podcasts, a downloads shortcut, a sort button + new-playlist +
grid/list toggle, a search field, then rows or a grid of
`gridColumns(maxWidth - 40.dp)` columns. → **HAVE**. Lokal's source/genre/
quality/format filters → **REJECT** (local-file tag facets).

**Playlist** (`Playlist.jsx:287`) — 112px cover, `PLAYLIST` eyebrow, `text-2xl`
title with an inline pencil that swaps in a text input, `N tracks · h:mm`. The
toolbar is passed **into the track table** as `toolbarStart`, so it scrolls with
the table, not the header: play circle, search-this-playlist (expands to a
`w-64` field), shuffle, share-as-picture, ghost songs, sync, delete. Then the
table, then a Recommended Songs section. *Screenshot confirms.*

Nexfy `Details.kt:PlaylistScreen` → `Collection.kt:CollectionScreen` — cover
232dp (wide) / 208dp, eyebrow + 44sp title + subtitle + meta, action row
`[shuffle] [Tocar] [search] [keep] [⋯]` as glass pills, header fades and the
cover shrinks 15% on scroll (`collapseOf`), a `DetailTopBar` takes over. Header
goes side-by-side at `maxWidth >= 640.dp`. → **HAVE**, and Nexfy's collapsing
hero is richer. Toolbar-inside-the-table → **REJECT** (Nexfy's actions belong
to the hero; moving them into the list would orphan them from the cover).
Inline rename from the header → **MISSING** (Nexfy renames via `NameDialog` in
`LibraryMenus.kt`).

**Album** (`Albums.jsx:477`, `OnlineAlbum.jsx:135`) — 176px cover, eyebrow
`ALBUM · LIBRARY`, `text-3xl` title, artist link, `N songs · N min · year`,
buttons Play / Play next / Shuffle / Full album online / Download missing /
Refresh. The track list here drops to a bare `#` / title / duration grid — no
header row, no album column, because every row shares the album.
*Screenshot confirms* exactly this.

Nexfy `Details.kt:AlbumScreen` → the same `CollectionScreen`, and
`TrackColumns(album = distinct albums > 1)` computes the album column away for
the same reason (`Collection.kt:281`). → **HAVE**, same conclusion reached
independently.

**Artist** (`Artist.jsx:308`) — the only edge-to-edge page: a 224px banner of
the artist photo at 40% under a gradient scrim, with a 96px circular avatar,
eyebrow, `text-3xl` name and track count overlaid at its foot; Back pill top
left, Manage + Follow top right. Body `px-8 py-5 space-y-7`: action row (Play
all / Play next / Shuffle / Radio / Share / online toggle / Refresh), About
prose `max-w-2xl`, Popular table, Releases grouped by type with a per-group
list↔grid toggle (`grid-cols-2 @md:3 @lg:4`), Features. *Screenshot confirms*
the banner, avatar and a shorter action row.

Nexfy `Details.kt:ArtistScreen` — `CollectionScreen` with `round = true` (the
cover becomes a circle) plus a `SectionTitle("Discografia")` + `Carousel`
footer. → Arrangement **PARTIAL**: no banner, no bio, no release-type grouping,
no follow button. A wide artist banner is the one place Lokal's arrangement is
clearly ahead.

**Search** (`Search.jsx:212`, `HeaderSearch.jsx:233`) — the field is **global,
in the window title bar**, `flex: 0 1 474px`, placeholder "What do you want to
play? Or paste a link". Focusing or typing in it navigates to `/search`;
leaving `/search` clears it. **200ms debounce**, with a sequence ref so a new
keystroke invalidates an in-flight search immediately. Results are grouped in a
fixed order: spelling correction line → Artists (`3 / 5 / 6` cols) → Albums
(`3 / 4 / 5`) → Tracks (table, with Play All) → Lyrics matches → Online.
A recent-searches dropdown hangs under the field (arrow keys wrap, Enter picks,
per-row X removes, a Clear button). *Screenshot confirms* the grouping order.

Nexfy — the title strip's field and the Buscar screen share one `nav.searchQuery`
(`DesktopShell.kt:364`, `Tabs.kt:129`), the screen omits its own field under a
pointer, **450ms debounce**, results grouped Músicas (as a table, with "Tocar
todas") / Artistas / Álbuns / Playlists, recent searches persisted with per-row
remove. → **HAVE**. Spelling correction → **MISSING** (needs a core API).
Recent-searches dropdown under the strip field → **PARTIAL** (Nexfy's list only
appears on the Buscar screen).

**Recap** (`Recap.jsx:553`) — `max-w-6xl` centred. Eyebrow + `text-3xl` title +
explainer, three buttons **Replay / Save as playlist / Story** that drop their
labels below 416px; a period picker of three chip rows (Year → Month → Week,
each horizontally scrolling, in-progress periods dashed); a hero with a blurred
cover backdrop, a summary sentence and a 4-up metric grid
(`1 / 2 / 4` cols: Minutes, Tracks, Artists, Peak Hour) beside a 260px cover
rail that appears at ≥800px; then Top Tracks beside Top Artists + Genres;
then Listening Sessions (`1 / 2` cols). Periods are finished weeks (Mon–Sun),
months and years, lookback 12/12/3 (`src/recapPeriods.js:11`).
*Screenshot confirms* the whole page.

Nexfy `Stats.kt:StatsScreen` — a `PillSelector` of periods, `HighlightRow`,
`Total` tiles, `Bars` (hours/weekdays), ranked `CountRow`/`ArtistRow` lists,
reached from `StatsShortcut` on Home. → **HAVE** for the page. Finished-period
navigation (pick *which* week) → **PARTIAL** (Nexfy picks a span, not a
specific past week). "Save as playlist" from a recap → **MISSING**.

**Recap Story** (`RecapStory.jsx`) — a 9:16 card portalled into `<body>`,
`CARD_HEIGHT = min(100dvh - 8.5rem, (100vw - 8rem) × 16/9)`, the rest of the app
set `inert`. Slides are built only where there is something to say (overview →
hour → session → genre → artist → albums → end) and are **timed**
(6500/7000/8000/8000/7500/8000ms; the last has `duration: 0` and stays), with a
segmented progress bar driven by a `useMotionValue` so it does not re-render.
Advance by tapping (left 30% = back), arrow keys, or flanking chevrons; pause by
holding 220ms, Space, or the pause button; a hidden window pauses. Keys are
captured in the capture phase so the app's own Space/arrow shortcuts do not
fire. → **REJECT** for Nexfy's desktop: a timed full-screen story is a phone
format, and Nexfy's window already has `Tocando agora` for the full-bleed
moment. (Worth revisiting for Nexfy's *phone* build, not this document.)

**Discovery** (`Home.jsx:156`, the Discovery tab) — a provider header (icon tile,
"Discovery" + provider name, a 3-button segmented group Last.fm / YouTube Music
/ Spotify, a Refresh link) over sections Mixed for you, Fresh Finds, Quick
Picks, Scrobble History, Albums For You, Artists For You — each with an eyebrow,
an `h2`, a count and a **See all / Show less** toggle that writes the open
section into the URL. Rows are 48px-thumb two-line items in a `1 / 2 / 3`-column
grid; cards are `2 / 3 / 6` or `3 / 4 / 6 / 8` (artists, circular, capped 112px).
*Screenshot confirms* the provider header and the Fresh Finds / Quick Picks
grids. → **PARTIAL**: Nexfy's Home has recommendation rows (`Lançamentos dos
teus artistas`, `Tuas mais ouvidas`, `Artistas que tu mais ouve`) but as
carousels, with no "see all" expansion and no provider switch. The provider
toggle itself is **REJECT** — Nexfy's recommendations come from Spotify, full
stop. A **collapsed grid that expands in place** is the transferable half, and
Nexfy's `SectionTitle(onOpenAll=)` already gestures at it.

**Settings** (`Settings.jsx:46`) — **seven category tabs in a sticky header**,
then one long scroll of that category: Integrations, Playback, Library, Addons,
Appearance, Data, About. The header holds the title, a **Search settings**
field that reveals every category at once filtered to matching rows, and an
auto-save status chip (`Changes save automatically / Saving… / Saved /
Couldn't save · Retry`) — **there is no Save button**. Each section is a card
kept to `max-w-2xl` (672px) so nothing stretches on a wide window. Switching
category scrolls to top and cross-fades.

Nexfy `SettingsScreen.kt` — one scroll, six sections (Conta, Som, Aparência,
Reprodução e comportamento, Armazenamento, Sobre), no categories, no search,
auto-saving with no Save button, and the page is width-capped at 760dp by
`DesktopShell.kt:679` (`Reading`). → Readable column **HAVE**; auto-save
**HAVE**; category tabs **PARTIAL** (six sections scroll fine today, but this
is the first thing to add as settings grow); **search within settings**
**MISSING** — cheap, and the obvious fix before tabs.

---

# 2. The behaviours

## 2.1 Keyboard

Source: `src/useKeyboardShortcuts.js:11-24` (`KEYBOARD_SHORTCUTS`, which is also
what Settings lists — read directly, confirmed).

| Keys | Action |
|---|---|
| `Space` | Play / pause |
| `Ctrl/⌘ + →` / `←` | Next / previous song |
| `Shift + →` / `←` | Seek ±10 seconds |
| `Ctrl/⌘ + ↑` / `↓` | Volume ±10% |
| `Ctrl/⌘ + S` | Shuffle |
| `Ctrl/⌘ + L` | Lyrics panel |
| `Ctrl/⌘ + F` | Full-screen player |
| `Ctrl/⌘ + K` | Focus search |
| `Ctrl/⌘ + ,` | Settings |
| `Escape` | Close the overlay in front |
| any printable character | **starts a search** |

Three design notes from `useKeyboardShortcuts.js:1-3,26-45`: letters all carry a
modifier *because* a bare letter starts a search; shortcuts are suppressed
inside `input/textarea/select/contenteditable`; `Space` is intercepted even when
a button has focus, so it does not re-click it.

Nexfy (`Main.kt:onPreviewKeyEvent` → `DesktopKeys`/`DesktopBack`,
`Platform.desktop.kt`): media keys, `Ctrl + ←/→` for previous/next, `Ctrl + F`
to focus the strip field, `Escape` to go back through a handler stack
(innermost first: `Tocando agora` → page → tab). **`Space` is deliberately
omitted** — the comment at `Platform.desktop.kt:DesktopKeys` says it would be
taken from the search field as the user types. Lokal solves that same problem
with `isTextField()` and keeps `Space`.

| Item | Verdict |
|---|---|
| Media keys, Ctrl+arrows | **HAVE** |
| `Escape` closes the innermost layer | **HAVE** — Nexfy's `DesktopBack` stack is the better implementation |
| Focus search by shortcut | **HAVE** (`Ctrl+F`; Lokal uses `Ctrl+K` for search and `Ctrl+F` for full screen — Nexfy's mapping is the more conventional one) |
| `Space` = play/pause, guarded by a text-field check | **MISSING** — the stated reason for omitting it is exactly what `isTextField` solves |
| `Shift+arrows` seek ±10s | **MISSING** |
| `Ctrl+↑/↓` volume | **MISSING** |
| `Ctrl+S` shuffle, `Ctrl+L` lyrics, `Ctrl+,` settings | **MISSING** |
| Type-to-search from anywhere | **MISSING** |
| A shortcut list shown in settings | **MISSING** — Nexfy has `Guide.kt`, the natural home |
| Arrow-key navigation within a list | **neither has it** — see §3.8 | **HAVE** (parity) |
| Focus trapped in modals | Lokal's generic `Modal.jsx` **does not trap focus** — no autofocus, no cycling, no restore, Tab escapes it; only `RecapStory` sets `inert` on the app root and restores focus. Nexfy's `GlassSheet` is a Material3 `ModalBottomSheet`, which traps focus for free | **HAVE** — Nexfy is ahead here |
| Live-region announcements while loading / on error | `role="status"` + `aria-live="polite"` on ~35 loading, empty and error lines, an sr-only sort announcement, `role="progressbar"` on the buffering bar, `aria-current="page"` on nav | `contentDescription` on icons; no announced status text | **PARTIAL** |

**No command palette in either app** (no `Mod+P`, no palette component). Lokal's
substitute is type-anything-anywhere → search, which Nexfy could adopt instead
(§2.1). For three destinations a palette would be ceremony.

## 2.2 The right button

`src/components/ContextMenu.jsx` is one component used by **24 files**. It opens
at the pointer, walks with Arrow↑/↓/Home/End/Enter/Space, and closes on Escape,
outside click, resize, window blur or any outside scroll. Items support icons, a
`danger` flag and separators; **leading, trailing and doubled separators are
collapsed** after conditional items are filtered out (`TrackList.jsx:622`), and
the menu **refuses to open at all if every entry turned out to be a separator**.
No submenus, one level everywhere.

Two positioning details worth copying: it measures its own rect and, if it would
overflow, **flips to the other side of the pointer** (floored at an 8px margin)
rather than shrinking or scrolling; and it renders `visibility: hidden` until
that measured position lands, so there is never a visible jump.

Surfaces with a right-click menu include the track row, the player bar itself
(menu for the playing track), the right panel's title/artist, artist names,
album and artist cards, cover art, station rows and the library folder browser.
**The sidebar has none.**

Nexfy: only **one** surface answers the right button —
`design/TrackTable.kt:secondaryClick`, which consumes the press on
`PointerEventPass.Initial` so the row does not also play, and opens
`TrackMenu`. `TrackMenu`/`ShelfMenu` are **`GlassSheet` bottom sheets**, i.e.
the phone's modal sheet, anchored to the bottom of a 1100dp-wide window rather
than to the pointer. Cards (`CoverCard`), library rows and sidebar rows use
`combinedClickable(onLongClick=)`, which a mouse reaches only by press-and-hold.

| Item | Verdict |
|---|---|
| Right-click on a track row | **HAVE** |
| Right-click opens **at the pointer** instead of a bottom sheet | **MISSING** — the single highest-value behaviour gap |
| Keyboard-walkable menu | **MISSING** |
| Right-click on cards / library rows / sidebar rows | **MISSING** (press-and-hold only) |
| Right-click the player bar for the playing track | **PARTIAL** — Nexfy has a `⋯` button there instead |
| Separator collapsing after conditional filtering | **MISSING** — Nexfy's menus are plain `Column`s with no separators at all |

## 2.3 Hover

| Surface | Lokal | Nexfy | Verdict |
|---|---|---|---|
| Track row | number → play glyph; quick-action strip fades in; grip appears; artwork gains a "replace artwork" overlay | number → `PlayArrow`; like + `⋯` fade in via `RowAction` | **HAVE** |
| Card | play pip / 40px accent circle appears; "save as playlist" button | nothing — `CoverCard` has no hover affordance | **MISSING** — a hover play button is the standard way to start a card without opening it |
| Carousel | — | arrows fade in at both ends while the pointer is on the row, and only while that direction can still scroll (`CarouselArrow`) | **HAVE** (Nexfy-only) |
| Seek bar | a 12px thumb appears on hover | `ThinSlider` thickens 4→8dp and blooms on grab; `reach` is 9dp under a pointer | **HAVE** |
| Volume | wheel over the player bar changes volume — `src/volumeWheel.js:useVolumeWheel`, a **non-passive** `wheel` listener that `preventDefault`s so the page does not scroll, `VOLUME_WHEEL_STEP = 0.05`, user-tunable 1–5% (`appearanceFlags.js:30`) | drag only | **MISSING** — cheap and expected |
| Long titles | two components, opposite triggers: `HoverScrollTitle` measures overflow on `onMouseEnter` and scrolls **only while hovered** (text that fits never moves); `MarqueeText` scrolls **whenever it overflows** and **pauses on hover** so a link inside stays clickable. Both set a duration from the distance and drop the animation under `prefers-reduced-motion` | `TextOverflow.Ellipsis` everywhere | **MISSING** |
| Icon buttons | native `title` tooltips | `contentDescription` only — no visual tooltip | **MISSING** |

## 2.4 Loading, empty, error

**Lokal has essentially no skeletons.** `src/pageCache.js`: `useCachedState`
remembers a page's last data across visits (32-entry LRU) so a revisit paints
real content on frame one; `usePageReady` keeps the page **invisible** until it
has data, capped at `PAGE_READY_TIMEOUT_MS = 350`, explicitly to stop the empty
state flashing before the data lands. So a cold page shows **nothing** for up to
350ms, then the finished page. What skeletons exist are a handful of
`animate-pulse` bars; the rest is text ("Loading albums…").

Toasts (`Toaster.jsx`) sit bottom-centre just above the player bar,
`role="status" aria-live="polite"`, in **two lanes**: ephemeral
(`VISIBLE_MS = 2200`, a new message replaces the current one) and
**request-owned loading** toasts that live until the owner calls `close()`, with
an optional percentage. `StreamLoadingToast.jsx` owns the "track is starting"
one and waits a **350ms grace delay** before showing it, so a fast start never
flashes a toast; its percentage counts **completed startup stages, not buffered
bytes**, and one toast spans a whole playback generation so provider fallback
updates it rather than remounting it.

Nexfy, `ui/Loading.kt:rememberLoaded`: identical last-value cache
(`lastValues` keyed by the load key), plus `refresh`, plus an offline branch
that **retries by itself when the network returns** — which Lokal has no
equivalent of. Screens show skeletons (`Skeleton.kt:Bone`, `TrackRowSkeleton`,
`CarouselSkeleton`, `TrackToolsSkeleton`) sized to the content they replace, and
cross-fade real content over the fading skeleton (`skeletonFade`).

| Item | Verdict |
|---|---|
| Last-value cache across visits | **HAVE** |
| Skeletons sized to the content | **HAVE** — and better than Lokal's hide-the-page; keep Nexfy's |
| Hide the page until data arrives | **REJECT** — fights Nexfy's skeleton-first language |
| Auto-retry when the network returns | **HAVE** (Nexfy-only) |
| Toast: one at a time, replaced, ~2.2s | **HAVE** (`NoticeBar`) |
| Progress-bearing toast tied to a long request | **MISSING** — relevant for Nexfy's "manter no aparelho" downloads |
| A grace delay before showing a "starting…" indicator | **PARTIAL** — Nexfy signals startup on the seek line (`ThinSlider(waiting = { s.loading })`) with no delay, so a fast start still blinks it |
| Error state with a retry button | **HAVE** (`LoadError`) |

Empty-state copy, verbatim from source, for comparison with Nexfy's:

| Where | Lokal | Nexfy |
|---|---|---|
| Queue | `Queue is empty` | `A fila está vazia.` |
| Lyrics, nothing playing | `No track playing` | `Nada tocando` |
| No lyrics | `No lyrics found` + **Search manually** / **Try again** | handled by `LyricsState.None` |
| Instrumental | `Instrumental — nothing to sing along to.` | — |
| Offline lyrics | `You're offline. Lyrics will load once you're back online.` | — |
| Unsynced warning | `These lyrics aren't time-synced` | — |
| Nothing playing (bar) | `—` / `No track playing` | `Nada tocando` |
| Search, no results | spelling correction + retry line | `Nada encontrado para "…"` + `Confira a grafia…` |

Lokal distinguishes **instrumental**, **offline** and **not found** as three
different lyric empty states, and hedges estimated timing rather than passing it
off as real sync. → **MISSING** (three-way lyric empty state + the unsynced
hedge). Everything else: **HAVE**.

## 2.5 Long lists

`TrackList.jsx:39-49,201-231,324-352`: lists over `LARGE_LIST_STEP = 200` rows
are windowed with `WINDOW_OVERSCAN = 100` rows either side and fixed-height
spacer divs, recomputed on rAF-throttled scroll and only when the visible band
comes within 50 rows of a window edge. Every row also carries
`content-visibility: auto` + `contain-intrinsic-size`, and the real row height is
measured once from the DOM. Entrance animation only runs under 120 rows.
The queue draws 10 before / 150 after the current index with **Show earlier** /
**Show more** buttons adding 200 each (`QueuePanel.jsx:20-22`). Artists page:
`PAGE_SIZE = 60` with an infinite-scroll sentinel.

Lokal does **not** cache scroll position — `pageCache.js` caches data only.
Scroll is handled in `App.jsx:AnimatedRoutes`, which resets `<main>`'s
`scrollTop` to 0 on navigation **but only when `useNavigationType() !== 'POP'`**,
and only after the outgoing page has faded. So back/forward (including the mouse
side buttons) keeps its place while a fresh navigation starts at the top.

Nexfy: `LazyColumn` composes only what is on screen by construction, and
`TrackTable`'s header is an item of the same list so a playlist of thousands
costs nothing extra (comment at `TrackTable.kt:110`). Scroll position per route
is kept in `NavState.lists` (`LazyListState` per key) — **for the session, not
across launches**.

| Item | Verdict |
|---|---|
| Virtualised long lists | **HAVE** — free in Compose; Lokal's 300 lines of windowing are a React tax |
| Scroll position restored on back | **HAVE** (session) |
| Paged "show more" in the queue | **MISSING** — Nexfy's queue is hard-capped at `next.take(30)` with no way to see further |

## 2.6 What is remembered between launches

Lokal keeps **56 `localStorage` keys** (full list grepped) plus
`window-state.json`. The ones that matter for arrangement:
`lokal-track-columns-v1` (column choice, per profile, global across every list),
`lokal-playlist-sorts-v1` (sort, **per playlist**), `lokal-panel-widths`,
`lokal-sidebar-collapsed`, `lokal-side-card-view`, `lokal-queue` (the queue
itself), `lokal-volume`, `lokal-recent-searches`, `lokal-recent-items`,
`lokal-artists-density`, `lokal-artists-sort`, `lokal-app-language`,
`lokal-onboarding-complete`.

Nexfy `LibraryPrefs.kt` over `Prefs`: pinned, playlist/album sort, plays, kept,
prefetch, shuffle, volume, `wideSidebar`, coverAccent, themeMode,
animatedBackdrop, reduceMotion, lyricsSize, showAddedBy, haptics, swipeBack,
**`sort:<key>` per list**, `shelf`, `libraryGrid`, recent searches.

| Item | Verdict |
|---|---|
| Per-list sort remembered | **HAVE** — `trackSort(key)`, same idea as Lokal's per-playlist sort |
| Volume, shuffle, theme, last shelf, grid/list | **HAVE** |
| Recent searches | **HAVE** |
| Window geometry | **MISSING** (see §1.3) |
| Panel open/closed + selected tab | **neither** — Lokal deliberately resets to Details on every open (`store/player.js:996`, citing Spotify). Nexfy's `panelOpen`/`panelTab` are plain `remember`. | **HAVE** (parity by accident) |
| The queue itself | Lokal restores it; Nexfy's lives in the Rust core's session | **HAVE** |
| Last route / tab | **neither remembers it** | **HAVE** (parity) |

## 2.7 Navigation

Lokal uses a **`MemoryRouter`** (`App.jsx:13`) with real back/forward history.
`NativeHistoryNavigation` (`App.jsx:207-262`) drives it from **mouse buttons
X1/X2** (Chromium reports them as `button 3`/`4` on `mouseup`, captured and
`preventDefault`ed) *and* from Electron's Windows `app-command`
`browser-backward`/`browser-forward` (`electron/main.js:525`), de-duplicating the
cross-source echo some Windows mice produce (same direction, different source,
within 250ms — a second event from the *same* source is a real second press).
It first dispatches a **cancelable** `lokal:history-navigate` event so an
overlay can intercept: the library folder browser uses it to go up one folder
instead of leaving the page (`LibraryFolderBrowser.jsx:146`).

Nexfy `NexfyRoot.kt:NavState` — a stack per tab, `pop()`/`popToRoot()`, no
forward (the comment at `DesktopShell.kt:330` notes a tab's stack only ever
loses its top, so there is never a page ahead), `Escape` and a greyed-rather-
than-hidden back button in the strip.

| Item | Verdict |
|---|---|
| Per-tab back stack | **HAVE** |
| Back button disabled, not hidden, so the strip does not shift | **HAVE** (Nexfy-only nicety) |
| Forward | **REJECT** — Nexfy's stack cannot have a page ahead; a dead arrow is worse than none |
| **Mouse back/forward buttons (X1/X2)** | **MISSING** |
| An overlay intercepting "back" before the router | **HAVE** — `DesktopBack`'s innermost-first handler stack is the same idea, done better |
| Breadcrumbs | neither | **HAVE** (parity) |

---

# 3. The track table

One component, `src/components/TrackList.jsx` (1276 lines), used by nine
screens; per-screen differences are entirely prop-driven.

## 3.1 Columns

`src/trackColumns.js:7` `TRACK_COLUMN_OPTIONS`, in picker order: `selection`,
`number`, `artwork`, `artist`, `source`, `quality`, `added`, `time`, `actions`,
`grip`. **Title** (`minmax(0,3fr)`) and **Album** (`minmax(0,2fr)`) are locked
and not in that list — they are the only flexible columns; everything else is a
fixed rem width. Header and rows share one `--tl-columns` grid template, so
cells can never drift apart.

Columns are fitted **greedily against the list's own width** in the order
`selection → quality → source → extras → album → time → number → added → grip →
actions`, from a budget of `width − 2 − 11 − 1.25 − 0.5` rem (padding, an 11rem
minimum title, the always-present `⋯`, a gap). So as the list narrows they drop
in reverse: **actions, grip, added, number, time, album, source, quality,
selection**. Thresholds with everything on: selection 264px, quality 344, source
384, album 520, time 576, number 616, added 712, grip 736, actions 888.

Degradation is graceful rather than binary: a dropped Album re-renders **inline
under the title** as `Artist · Album` and the Title header relabels itself
`Title / Album`; a dropped Source puts its glyph inline before the title;
dropped extras become `Label: value` micro-text. *Screenshots confirm* that the
album page and the search page show fewer columns than the playlist page.

Nexfy `design/TrackTable.kt`: `TrackColumns(album, date, addedBy)` is computed
**from the data, not the width** (`Collection.kt:281`) — no album column when
every track shares an album, no date column when nothing is dated, faces only
when more than one person added and the setting is on. Fixed widths
`NumberWidth 40 / CoverSize 40 / DateWidth 152 / DurationWidth 72 /
ActionsWidth 72`, `AlbumWeight .42f`, `RowHeight 56.dp`. Below
`TableWidth = 760.dp` the whole table becomes the phone's `TrackRow` column.

| Item | Verdict |
|---|---|
| One grid template shared by header and rows | **HAVE** |
| Columns computed away when they would say nothing | **HAVE** — Nexfy's data-driven rule is cleaner than Lokal's width-driven one and reaches the same result |
| Per-column width budget with a defined drop order | **PARTIAL** — Nexfy has one cliff at 760dp; between 760 and ~900dp the date + album columns are tight |
| Album folding **under the title** instead of vanishing | **MISSING** — the single best idea in Lokal's table |
| Header relabelling when a column folds in | **MISSING** |
| `quality`, `source` columns | **REJECT** — file-tag facets; a stream has one source and a declared bitrate |
| `artwork` / `artist` toggles | **REJECT** — Nexfy's row identity *is* cover + title + artist |

## 3.2 Sorting

Only **three** columns sort — `#`, `Date added`, `Time` — and only when the
caller passes `onSortChange`, which **only the playlist page does**
(`Playlist.jsx:430`). Title, Artist, Album, Quality and Source do not sort.
Clicking the header text (a real `<button>`) toggles; it is **two-state, not
tri-state** (`playlistSorting.js:1`): a new column starts `asc`, the active one
flips asc↔desc forever, and the only way back to manual order is clicking `#`
back to ascending. Active column turns accent with an 11px chevron, announced
through an sr-only live region. Secondary sort is always the original playlist
index, and null values sort last regardless of direction. Sorting happens in the
page, not the table — the table receives pre-sorted rows plus a `trackNumbers`
map so `#` keeps showing original positions. **Reorder is disabled while
sorted.**

Nexfy `Collection.kt:TrackSort` — six options (Ordem original, Adicionadas
recentemente, Título, Artista, Álbum, Duração) behind a **sort button and
dropdown** in `TrackTools`, not header clicks; `Added` is hidden when the list
is undated; `Artist` carries a secondary sort by album then original index;
persisted per list under `sort:<key>`; a caption reads `N de M · por título`;
rows glide to their new place via `reorderSpring`. A re-sorted view plays as an
explicit list, not as the Spotify context (`rowContext`).

| Item | Verdict |
|---|---|
| Sortable by title / artist / album | **HAVE** — Nexfy sorts by *more* than Lokal |
| Sort persisted per list | **HAVE** |
| Secondary sort + stable ties | **HAVE** |
| An explicit "back to original order" | **HAVE** — Nexfy's `Original` entry beats Lokal's two-state trap |
| **Click a column header to sort** | **MISSING** — Nexfy's header labels are inert `Text`; this is the gesture a table invites |
| Sort direction indicator in the header | **MISSING** |
| Rows animating to a new order | **HAVE** (Nexfy-only) |

## 3.3 The column picker

A `Columns3` + "Columns" button above the header's right end opens a
`role="dialog"` popover portalled to `document.body`, `w-64`, which **flips above
the anchor** when there is no room below and clamps its height. Inside: a
heading, two disabled+checked locked rows (Title, Album) each with a lock icon
and `title="Always shown"`, then a flat checkbox list, then the note *"Saved for
all song lists in this profile. Narrow lists tuck Album under Title and fit
other columns as space allows."*, then **Reset to defaults**. It closes on
outside pointerdown, `focusin` outside, Escape (restoring focus to the button),
any outside scroll, or a window resize, and autofocuses the first enabled
checkbox. Persisted globally per profile in `lokal-track-columns-v1`.

**No reordering and no resizing.** (Lokal's radio-station list does have both,
plus row-size and row-gap sliders — `src/components/StationList.jsx`,
`src/radio/stationColumns.js` — and `TrackColumnPicker` is already
parameterised to serve both lists.)

*Screenshot* `d-columns.png` shows the popover with Title/Album locked and
Track number, Artwork, Artist, Source, Quality, Date added, Duration, Quick
actions below — note the shot predates the `selection` column in source.

Nexfy has **no column picker**: `TrackColumns` is derived, never chosen.

| Item | Verdict |
|---|---|
| A column picker at all | **PARTIAL** — Nexfy derives the right columns automatically, which covers most of the need; the gap is only the user who wants *fewer* |
| Locked columns shown as locked rather than hidden | worth copying **if** a picker is ever added |
| Popover that flips, clamps, restores focus, closes on scroll/resize | **MISSING** — Nexfy has no popover primitive of this quality; `DropdownMenu` in `TrackTools` is the closest |
| Choice persisted per profile | n/a |
| Column reorder / drag-resize | **REJECT** — resizable columns fight a layout whose widths are design constants |
| Row-size / row-gap sliders | **REJECT** — density sliders undercut a tuned 56dp row |

## 3.4 The row, at rest and on hover

At rest: grip (invisible), checkbox, number, 32px artwork + title + `artist ·
album` sub-line, album, source glyph, quality pill, added date (`Today` /
`Yesterday` / `Nd ago` / locale date, with the full timestamp as `title`), time,
an invisible action strip, a heart (invisible unless liked), and a
**permanently visible `⋯`**.

On hover: the number becomes a filled play glyph (or pause, on the current
track); the grip fades in with `cursor-grab`; the artwork gains a dark overlay
with a camera "replace artwork" button; the quick-action strip fades in —
Play next, Save to library, Download (if missing), Add to queue, Quick-add,
Lossless, Add to playlist, Edit info, Remove/Delete. The heart stays visible
once liked and plays a 500ms scale-and-fade burst when toggled. **Duration does
not swap to a menu** — `⋯` is its own 13th cell.

Nexfy `TrackTableRow`: hover lifts the ground to `onSurface.copy(.06f)` and the
number becomes `PlayArrow`; `RowAction` fades and scales the like and `⋯`
glyphs in, keeping their place in the row while away; the heart stays out once
lit or while the row plays; unplayable rows sit at `alpha .4f`; the explicit
mark is a `🅴` prefix on the artist line.

| Item | Verdict |
|---|---|
| Number → play glyph on hover | **HAVE** |
| Actions appearing on hover, holding their place | **HAVE** |
| Heart persistent once lit | **HAVE** |
| A like burst animation | **PARTIAL** — Nexfy has `LikeBurst` but only in the player, not on a table row |
| `⋯` always visible rather than hover-only | **REJECT** — Nexfy's comment at `TrackTable.kt:233` is right that a column of glyphs down a long list is noise |
| Relative added-date (`Today` / `3d ago`) | **PARTIAL** — Nexfy's `addedLabel` gives `14 de mar.`, dropping the year only within this year |
| Full timestamp as a hover tooltip | **MISSING** |
| Row-level badges (missing file, ghost, codec error) | **REJECT** — all local-file states |

## 3.5 The `⋯` menu

Same builder for `⋯` and right-click; acts on the whole selection if the row is
in it. In order: **Play**, Play next, Add to queue, Add to playlist…, Start
radio *(single)*, Add to this playlist *(if `onQuickAdd`)*, Like / Remove from
Liked Songs, Download / Save to library, Redownload missing file, Get it in
lossless… — separator — Go to album, Edit info, Open in file manager, Replace
artwork — separator — Remove from this playlist, **Delete from library**
(`danger`). Falsy entries are filtered first, then stray separators collapse.
The source notes (`TrackList.jsx:587`) the menu deliberately duplicates every
hover button so a narrow window, where those are hidden, still reaches them.

Nexfy `screens/TrackMenu.kt`: a header of cover + title + artist, then
Curtir/Remover, Adicionar à fila, Adicionar à Jam *(when a Jam is live)*,
Adicionar à playlist, Iniciar rádio, Remover desta playlist *(own playlist, with
the position so the removal can be undone in place)*, Ir para o álbum, Ir para o
artista.

| Item | Verdict |
|---|---|
| Play / queue / playlist / radio / album / artist / like | **HAVE** |
| Remove from this playlist, undoable in place | **HAVE** — better than Lokal's |
| Menu duplicates the hover actions so a narrow window loses nothing | **HAVE** |
| Separators grouping destructive items | **MISSING** |
| Acting on a multi-selection | **MISSING** (§3.7) |
| Edit info / Replace artwork / Open in file manager / lossless / delete-from-library | **REJECT** — every one needs a file on disk |

## 3.6 The playing row

Lokal: ground `bg-accent/8` which **replaces** the hover tint, so the current
row does not react to hover; title turns accent; the number cell permanently
shows play (when paused) or pause (when playing). **No bars animation anywhere
in the table.** No auto-scroll to the playing row — the scroll-and-flash
mechanism (`ring-2 ring-accent bg-accent/15 animate-pulse` for 2000ms) is driven
by `highlightTrackId` when you arrive from a "playing from…" shortcut, and
re-arms via `highlightRequestKey` so clicking the same shortcut twice flashes
again.

Nexfy: ground `accent.copy(.12f)`, accent title, and `PlayingBars` — an animated
equaliser — in the number cell, kept even under the pointer so clicking restarts
the song (comment at `TrackTable.kt:200`).

| Item | Verdict |
|---|---|
| Playing row tinted + accent title | **HAVE** |
| Animated bars marking it | **HAVE** (Nexfy-only; strictly better than a static glyph) |
| Playing row ignores hover | **HAVE** |
| Scroll to a row and flash it on arrival from a shortcut | **MISSING** — Nexfy's `Peek`/`origin` shared-element machinery is the place this would slot in |

## 3.7 Multi-selection

Lokal has a full one (`src/selection.js`): plain click selects **nothing** and
clears (deliberate, so rows are not selected by accident); `Ctrl/⌘+click`
toggles; `Shift+click` ranges from the anchor, and with `Ctrl` held **adds** the
range; a per-row checkbox and a header select-all checkbox; `Ctrl+A` extends
(only once a selection exists), `Escape` clears, `Delete`/`Backspace` removes —
all suppressed while typing or while a modal is open. Only one selection exists
app-wide: starting one clears every other list's.

`SelectionBar.jsx` appears at **count > 1** (not 1), portalled, floating centred
over the page 16px above the player bar, spring-animated, honouring
`prefers-reduced-motion`, and it **pads the page's bottom by
`max(88, height+32)`px** so the last rows still scroll clear. Actions: Play,
Play next, Add to queue, Add to playlist, Download, Edit, Remove, Delete. It
measures itself before paint and folds through three layouts —
`line` → `compact` (hint dropped) → `stacked` — and carries the hint
*"Ctrl+click to add · Shift+click for a range · right-click for more"*.

Nexfy has **no selection of any kind** (verified: no selection state in
`commonJvmMain` or `desktopMain`).

| Item | Verdict |
|---|---|
| Multi-selection (Ctrl / Shift / checkbox / select-all) | **MISSING** |
| A floating selection bar that reserves its own space | **MISSING** |
| The bar folding to fit instead of wrapping | **MISSING** |
| An app-wide single-selection invariant | **MISSING** |
| Plain click selecting nothing | worth copying with it — on Nexfy a plain click **plays**, so selection must stay on modifiers |

## 3.8 Row interactions

| Gesture | Lokal | Nexfy | Verdict |
|---|---|---|---|
| Single click | selection only — **does not play** | **plays** (`onClick = play`) | **HAVE** — Nexfy's is right for a player without selection |
| Double click | plays, or toggles if already current | plays again (restarts) | **HAVE** |
| Right click | context menu | bottom sheet (§2.2) | **PARTIAL** |
| Middle click | nothing | nothing | **HAVE** (parity) |
| Drag to reorder | yes, but only on a manually-ordered playlist with no active filter; insert-before-target; multi-track aware; dragged row at 50% opacity, target gets an accent top border | **none** | **MISSING** |
| Drag out to a sidebar playlist | yes, via `application/json` | — | **MISSING** |
| Drag out of the app | no (`text/plain` is just the id) | no | **HAVE** |
| Keyboard | no roving tabindex, no arrow navigation, no Enter-to-play; only the focusable controls inside a row | same | **HAVE** (parity — neither is keyboard-navigable; this is a real accessibility gap in both) |

## 3.9 Density

**Neither app has a density setting.** Lokal: `DEFAULT_ROW_HEIGHT = 48px`
(a `text-sm` title + `text-xs` sub-line = 36px content plus `py-1.5`), artwork
32px, padding `px-4 py-1.5`, gap 8px; the real height is measured from the DOM
once for the virtual spacers. Nexfy: `RowHeight = 56.dp`, cover 40dp, inset
`CellInset = 8.dp`, gap 16dp. Lokal's "compact layout" in comments means the
responsive album fold, not a density mode.
→ **HAVE** (parity). Row-size sliders: **REJECT** (§3.3).

---

# 4. The right-hand panel

Source: `src/components/RightSidebar.jsx` (687 lines).

## 4.1 Shape

300px by default, 260–620px draggable in Freeform mode, persisted in
`lokal-panel-widths`. It is a **flex sibling** of `<main>`, so it **pushes** the
page narrower rather than overlaying it — which, combined with page-relative
breakpoints, is what makes the page reflow when it opens. Background is a flat
85%-opacity surface with **no `backdrop-filter`**: a comment at
`RightSidebar.jsx:375` records that nesting one here made Chromium flash dark
bands on any repaint, which is also why the open animation splits into a
width-animating outer element plus an x-translating inner one.

Nexfy `SongPanel` — `Modifier.width(PanelWidth)` = 332dp, a `Panel` (surface +
hairline) inside the row, so it pushes too. Nexfy's glass is Skia, not
`backdrop-filter`, so Lokal's bug does not apply.

| Item | Verdict |
|---|---|
| Pushes the page rather than overlaying | **HAVE** |
| Fixed, non-resizable width | **MISSING** (drag + persist, §1.1) |
| Animated open/close | **PARTIAL** — the sidebar's fold animates (`animateDpAsState`), but the panel itself appears and vanishes without a transition |

## 4.2 Tabs

Lokal has exactly three: `['info','Details'], ['lyrics','Lyrics'],
['queue','Queue']`, as a segmented pill group. The **Details** tab is itself a
three-way card switcher (`info → lyrics → visualization`, cycled by a
hover-revealed button in the cover's corner, persisted in
`lokal-side-card-view`, and **reset per track** unless it is the visualizer).
Details holds: a 300px cover hero (growing to `max(300, 60% of panel height)`
for a tall Canvas), title and artist links, a "FROM PLAYLIST" context block, a
row of buttons (**Full screen**, **Lyrics full screen**, conditionally music
video and Canvas toggle), then rows for Album, Album artist, Year, Genre,
Track #, Bitrate, Quality, Plays. *Screenshot `d-sidebar-details.png` confirms*
the tabs, the collapse chevron, the cover, `PLAYING FROM`, the two buttons and
the key/value rows.

Nexfy `PanelTab`: **four** — `Capa`, `Detalhes`, `A seguir`, `Letra` — in a
`PillSelector`, with a 44dp cover + title strip above it that hides on the Capa
tab because the cover says it larger.

| Item | Verdict |
|---|---|
| Tabbed panel with queue, lyrics and details | **HAVE** |
| A cover-only "sleeve" tab | **HAVE** (Nexfy-only — `CoverPane`, explicitly for a second monitor) |
| Details rows (album, year, track #) | **HAVE** — `DetailsPane`, and Nexfy **omits a row it has no value for** rather than printing a blank |
| `Bitrate`, `Quality`, `Genre`, `Plays` rows | **REJECT** — file-tag data |
| A nested card switcher inside Details | **REJECT** — a tab bar whose tabs contain their own hidden tab bar; Nexfy's four flat tabs say the same thing |
| Buttons for full screen / full-screen lyrics in the panel | **PARTIAL** — Nexfy reaches `Tocando agora` from the bar's `OpenInFull` and lyrics from the panel tab, but the panel offers neither |
| A visualizer pane | **REJECT** — Nexfy's living artwork is its visualizer |

## 4.3 Opening and closing

Lokal: a chevron-right in the panel header closes it; three player-bar buttons
open it (Lyrics, Queue, Now Playing). `Ctrl/⌘+L` is the only key that touches
it, and **nothing** opens/closes the panel itself or switches to Queue.
The open state and the tab are **not persisted**, and `toggleRightSidebar`
deliberately resets to Details on every reopen. Instead there is **auto-open on
play** (`sidePanelOnPlay`, default on, Settings › Appearance › Layout).

Nexfy `DesktopShell.kt:284`: the bar's Lyrics and Queue icons call
`onPanel(tab)`, which **toggles closed if that same tab is already showing** and
otherwise switches to it — one button that both opens and returns.

| Item | Verdict |
|---|---|
| Open/close from the player bar | **HAVE** |
| One button that opens *and* closes the tab it names | **HAVE** — better than Lokal's separate close chevron |
| A key to toggle the panel / lyrics | **MISSING** |
| Auto-open on play | **REJECT** — it overrides the user's explicit close the next time they press play; the source itself pairs it with a refusal to remember the closed state |
| Resetting the tab on every open | **REJECT** — Nexfy returns to the tab you left, which is the kinder default |

## 4.4 Nothing playing

Lokal: Details shows a centred music glyph and the title `Nothing playing`;
every other element is gated away. Lyrics: `No track playing`. Queue:
`Queue is empty`.

Nexfy: the 44dp strip reads `Nada tocando` with an empty cover; `CoverPane`
shows the empty sleeve with `Nada tocando` beneath; `DetailsPane` and
`LyricsPane` each early-return a centred `Nada tocando`; `QueueList` prints
`A fila está vazia.`. The arrangement does not collapse — the panel keeps its
shape on the empty sleeve, as the playing bar does.
→ **HAVE** for all four.

## 4.5 The queue tab

Lokal `QueuePanel.jsx`: **one flat, globally numbered list — no "Now playing" /
"Next up" / "Next from" sections.** The current row is marked inline (accent
tint, `▶` in place of the number, a pulsing dot on the artwork, accent title).
Header reads `Queue (N)`, or `Up Next (Shuffled) (N)`. Rows are 36px artwork +
title + artist, `text-xs`, no duration.

- **Drag to reorder:** yes, HTML5 drag, visible `GripVertical` handle on hover,
  accent top border on the drop target, dragged row at 20% opacity; the payload
  also lets rows be dropped onto sidebar playlists.
- **Remove:** a per-row X on hover, **hidden on the playing row**.
- **Clear:** only `Clear Play Next (N)`, shown when something was inserted via
  Play Next — there is **no true clear-queue**.
- **Click:** plays that item immediately.
- **Context menu: none.** Instead three always-available hover buttons per row:
  Play next, Add to queue, Add to playlist.
- Windowed at 10 before / 150 after, with Show earlier / Show more.

Nexfy `QueueList`: `next.take(30)`, resolved through `tracksInfo`, drawn as
numbered `ShelfRow`s (42dp cover, title, artists), click → `skipTo(entry, i)`.

| Item | Verdict |
|---|---|
| Numbered up-next list, click to jump | **HAVE** |
| Shows what is playing now at the top | **MISSING** — Nexfy's queue starts at the *next* song; the panel's own strip carries the current one, so this is minor |
| `Up Next (Shuffled)` state in the header | **MISSING** |
| Drag to reorder the queue | **MISSING** |
| Remove one item from the queue | **MISSING** |
| Clear the queue | **MISSING** in both; Lokal's partial "Clear Play Next" is not worth copying |
| Per-row hover actions | **MISSING** — Nexfy's queue rows have no actions at all, not even `⋯` |
| Seeing past the first 30 | **MISSING** (§2.5) |
| Sectioned queue (now / next / from) | **REJECT** — Lokal tried a flat list and it reads fine; sections are Spotify's habit, not a requirement |

## 4.6 The lyrics tab

Lokal `LyricsPanel.jsx` (915 lines): nine providers queried in parallel, first
hit by user-ordered priority, cached, with a **source picker**, "Search all
sources again" and a manual search drawer, and an attribution footer
`Lyrics via {source} · {Syllable synced | Line synced | Not synced}`.
Sync is to **word/syllable** level: a 0.3s focus lead, 560ms eased auto-scroll,
staggered per-word lift, and distance-based alpha+blur falloff on unfocused
lines. Scrolling, touching or arrowing switches to **browsing** mode for 3200ms,
suspending auto-follow. Synced lines are `role="button"` with `tabIndex={0}` and
Enter/Space, and clicking seeks — but the seek is skipped if the user has a text
selection. Loading is a 5-bar shimmer. Font size is a hardcoded prop
(`textScale = 1.4`), **not user-controllable**. Full-screen lyrics via an
**Expand Lyrics** footer button. Extras: Translate, Romanize, and (local files
only) save lyrics into the audio file. *Screenshot `d-lyrics.png` confirms* the
full-screen view: current line white, past lines grey, future lines
progressively blurred, with Translate and a source picker in the corners.

Nexfy `LyricsPane` → `player/LyricsView` with `LyricsState.Loading/Found/None/
Failed`, position sampled every frame via `withFrameMillis`, click-a-line to
seek, and a **user-facing size setting** (`LyricsSize` in Settings › Aparência ›
Tamanho da letra). Translation exists (`translateEmail` pref).

| Item | Verdict |
|---|---|
| Timed lyrics, auto-scrolling, click to seek | **HAVE** |
| User-controllable text size | **HAVE** (Nexfy-only — Lokal hardcodes it) |
| Translation | **HAVE** |
| Blur/alpha falloff on distant lines | **PARTIAL** — worth checking `LyricsView` against this; it reads well in the screenshot |
| Browsing mode that suspends auto-follow after a manual scroll | **MISSING** — without it, scrolling back to re-read fights the follow |
| Not seeking when text is selected | **MISSING** (minor) |
| Three-way empty state + unsynced hedge | **MISSING** (§2.4) |
| Source attribution and a manual re-search | **PARTIAL** — Nexfy has one source; a `Failed` state with a retry is the transferable half |
| Nine providers, source picker, embed-into-file | **REJECT** — one provider, no files |
| Full-screen lyrics as a distinct mode | **HAVE** — `NowPlaying`'s `lyrics` toggle lays them over the blurred cover |

## 4.7 What else can hold the slot

Lokal: the visualizer card (five modes), the Canvas/animated-cover player, a
colour backdrop, a like-burst, and a genre popover — all inside Details, none as
their own tab. In Independent mode, Queue and Lyrics become their own 320px
columns beside it. Music video, full-screen player, full-screen lyrics and the
full-screen visualizer are **top-level overlays**, not panel content.

Nexfy: four tabs and nothing else; `DevicesSheet`, `TrackActionsSheet` and
`NowPlaying` are separate layers. → **HAVE**. Independent panels: **REJECT**
(§1.1). Visualizer: **REJECT** (§4.2).

---

# 5. Verdict tally

Counted over the tables above (one row = one item).

| Verdict | Count |
|---|---|
| **HAVE** | 76 |
| **PARTIAL** | 14 |
| **MISSING** | 49 |
| **REJECT** | 19 |

(158 judged items. "HAVE" includes a dozen rows where neither app has the thing
and the parity is deliberate — they are marked as such in place.)

Nexfy's desktop is further along than the count suggests: most `MISSING` rows
are small (a keyboard shortcut, a tooltip, a wheel gesture), and the `REJECT`
rows are almost all Lokal's local-file, Soulseek and quality-analysis machinery,
which has no meaning over a Rust core talking to Spotify.

## The three gaps worth closing first

1. **Right-click should open a menu at the pointer, not a bottom sheet.**
   (§2.2) Nexfy already detects the secondary click correctly
   (`TrackTable.kt:secondaryClick`) and already has the menu's contents
   (`TrackMenu.kt`); what is missing is the surface. Today a right-click on a
   1100dp window throws a phone's modal sheet up from the bottom edge —
   the one place Nexfy's desktop still reads as a ported phone app. It also
   unlocks right-click on cards, library rows and sidebar rows, which currently
   need a press-and-hold no mouse user will discover.

2. **Clicking a column header should sort.** (§3.2) Nexfy sorts by more fields
   than Lokal and remembers the choice per list, but it hides all of it behind
   a sort button while drawing inert `Título` / `Álbum` / `Duração` labels over
   the columns. A table with labelled columns invites a click on them; not
   answering it is the gap users notice fastest. `TrackSort` already exists —
   this is wiring the header to it plus a direction chevron.

3. **Window geometry must survive a restart.** (§1.3) Every launch reopens at
   1100×760 wherever the OS drops it. Lokal's `window-state.json` is the whole
   recipe and is ~40 lines: `{bounds, maximized}`, written atomically via
   tmp+rename, debounced 250ms, validated against the visible work area, storing
   `getNormalBounds()` so a maximized window remembers its restored size.
   Nexfy already has a `Prefs` store to put it in. For a desktop app this is
   the most basic promise there is, and it is the only `MISSING` item a user
   meets before they have played a single song.

### Honourable mention

**Album folding under the title instead of vanishing** (§3.1) is the best single
idea in Lokal's table: between 760dp and ~900dp Nexfy's columns are tight, and
`Artist · Album` on the sub-line with the header relabelled `Título / Álbum`
would buy the whole range back. Cheap, and purely a layout change.
