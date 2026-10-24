# Lokal Mobile — design reference

What this app does with arrangement, motion and gesture, written so that the next
agent does not have to open it again.

**Subject.** Lokal Mobile (public name *Konnekted*, internal Dart package `flick`),
a Flutter/Riverpod/Isar Android player over a Rust (Symphonia/Oboe) engine. Clone
read at commit `1e6881d`. It plays **local files**; online sources are optional.

**Read as.** Arrangement, motion, behaviour. Nexfy keeps its own colours, type and
brand — nothing here is a licence to copy any of those. Every number below was read
from Dart source unless a line says *(screenshot)*.

**Nexfy side.** Judged against `shared/src/commonJvmMain/kotlin/dev/nexfy/app/ui/`
as of branch `desktop`: `screens/Home.kt`, `screens/Library.kt`, `screens/Tabs.kt`,
`screens/Stats.kt`, `player/PlayerSheet.kt`, `design/Components.kt`,
`design/Motion.kt`, `design/Glass.kt`, `design/Lens.kt`, `NexfyRoot.kt`.
These files are **shared with the desktop**; `LocalPointer` (`design/Shell.kt:18`)
is true when a mouse drives the screen, and `LocalShellHeader` is true when the
window frame already carries the header. Anything finger-shaped has to be gated on
one of those.

**Verdict key.** `HAVE` / `PARTIAL` / `MISSING` / `REJECT`. `REJECT` means it
fights Nexfy's nature — Nexfy is a Spotify client over a Rust librespot core, so
local-file scanning, folder pickers, audio-format inspection and bit-perfect
output have nothing to act on here.

---

## 1. Arrangement

### 1.1 The shell and its dock

`lib/features/konnekted/konnekted_shell.dart`, `konnekted_dock.dart`.

Four destinations, lazily built (`KeyedSubtree(key: ValueKey(tab))`, no
`IndexedStack`, no `PageView`): **Home** (`listen`), **Search** (`explore`),
**Library**, **You** (`settings`). Each tab's scroll position is kept by one
`PageStorageBucket`; pushed pages go on a single nested `Navigator`.

The dock floats over the content, which gets its height as bottom padding through
a `MediaQuery` override rather than as a `Scaffold` slot:

```
reservedHeight = max(systemInset, 8) + 6 + 58 + (hasMiniPlayer ? 58 + 8 : 0)
```

- `barHeight = 58`, `miniHeight = 58`, `gap = 8`, margin `fromLTRB(14, 6, 14, 8)`,
  `maxWidth 560`, text scaling clamped to `1.3` inside it.
- Three glass pieces in one `BackdropGroup`: the mini player (full width, on top),
  the tab capsule (`width − 58 − 8`), and a **separate search circle** (`58×58`,
  right-aligned) — Apple Music's iOS 26 split.
- The capsule holds only Home / Library / You. Search is the circle.
- Background behind everything: `LinearGradient(topRight → bottomLeft)` from
  `lerp(bg, artworkDominant ?? accent, .13)` to `bg`, stops `[0, .55]`.

→ **PARTIAL** — Nexfy has a floating glass dock (`NexfyRoot.kt:667` `Dock`) with
three tabs, 16dp side margins over the navigation-bar inset, and reserves space via
`LocalBottomSpace` (104dp, 176dp with a song). It has no separate search circle and
no fourth destination.

### 1.2 Home

`konnekted_home.dart`. `SafeArea(bottom: false)` → `RefreshIndicator` → one
`CustomScrollView`; everything scrolls, nothing is pinned.

Top to bottom:

1. **Top bar** — padding `fromLTRB(20, 12, 16, 0)`. Left: the logo at `32`, which
   opens Appearance. Right: a `44` glass circle for Settings.
2. **Greeting**, `SizedBox(height: 10)` below the bar: headline `fontSize 34`,
   `w600`, `letterSpacing −.7`; `SizedBox(height: 2)`; subheader `fontSize 15` in
   the muted ink, inside an `AnimatedSwitcher(220 ms)` keyed on the greeting so a
   new line cross-fades. Both come from §3 below. *(screenshot confirms "Wide
   rotation" over "Lots of different sounds in your rotation lately.")*
3. **LOCAL / DISCOVERY** segmented capsule, `top: 16`, centred,
   `LokalGlass.capsule(thin, shadow: false, padding: all 4)`. Chip is an
   `AnimatedContainer(260 ms, easeOutCubic)`, padding `h16 v8`, radius `16`,
   selected fill `black .06` (light) / `white .12` (dark), label `size 10`
   uppercase. Choosing Discovery **replaces the whole body below**.
4. **`MADE FOR YOU`** accent label (`+18` above, `+8` below), then the Cover Flow
   stage, then `+14`, then the **FLOW** pill (a `16` sweep-gradient dot + the word).
5. **Your Mixes** — `Wrap(spacing: 14, runSpacing: 18)`, two columns of
   `(maxWidth − 14) / 2`, padding `h24`. Each card: square collage of up to four
   covers, radius `16`; `+10`; title `14 w600`; `+2`; `"<n> songs"` at `12`.
6. Three song shelves: **On repeat, lately** → **Close to your heart** → **Fresh in
   your library**.
7. Tail `SizedBox(height: 28 + bottom inset)`.

Section heading: padding `fromLTRB(24, 26, 16, 12)`, title `22 w700
letterSpacing −.5`, optional `18` accent glyph + `8` gap, a "See all" `TextButton`.

Song shelf: `ListView.separated` horizontal, card width `164` (`142` when the
screen is under `360`), `height = width + scale(14)*2.6 + scale(12)*1.5 + 24`,
padding `h24`, separator `14`, **capped at 12 items**. Card: artwork radius `16`,
`+10`, title `14 w600 h1.3` over two lines, `+3`, artist `12 h1.5`.

→ **PARTIAL** — Nexfy's Home (`screens/Home.kt`) is the same shape: a `RootHeader`,
a `LargeTitle` that shrinks as the list scrolls, then titled rows of `CoverCard`s
(width `156`, radius `16`, cover radius `14`) in a `Carousel` that bleeds `24dp`
past the gutters and snaps to `SnapPosition.Start`. It has seven rows against
Lokal's five, a skeleton per row, and a `LikedTile`. It has no segmented control,
no Cover Flow, no two-column mix grid.

### 1.3 Library

`konnekted_library.dart`, `library/*`.

1. `LokalPageBar` (height `44`, padding `fromLTRB(20, 12, 16, 0)`): `+` glass
   circle → an add sheet; gear → Settings.
2. Large title "Library": padding `fromLTRB(20, 10, 20, 14)`, `fontSize 34`,
   `height 1.1`, `letterSpacing −1`, `w800`. **Long-pressing the title pushes
   `ClassipodScreen`** — a hidden click-wheel iPod view.
3. **Six glass destination tiles** in a `Wrap(spacing: 12, runSpacing: 12)`,
   padding `fromLTRB(20, 6, 20, 4)`, two columns (one under `300` wide), tile
   height `58 × textScale.clamp(1, 2)`, `LokalGlass` radius `16` thin without
   shadow, inner `h14`, icon `24` + `10` + label `16 w600`. In order: Liked,
   Playlists, Albums, Artists, Genres, Downloads. *(screenshot confirms)*
4. **Recently added** shelf — same widget and metrics as Home's shelves.
5. `More` section title (`fromLTRB(20, 22, 12, 8)`, `22 w700`), then seven
   `LokalNavRow`s (`minHeight 52`, padding `h20 v10`, bottom hairline, accent icon
   `24` + `16` + label `17`, chevron `22`): Recently played, Recap, Folders, Linked
   playlists, Radio, Videos, Spotify library.
6. `Songs` section title with See all, then the **whole song browser inline**.
7. Tail `SizedBox(height: 150 + bottom inset)`.

The browser's own header is the only pinned thing in the app:
`SliverPersistentHeader(pinned: true)` at `60 × textScale.clamp(1, 1.6)`, holding a
search field and a sort `PopupMenuButton`. **The field has no debounce** — each
keystroke calls `setState` and re-filters in memory. Sorts: default order,
recently added, title, artist, most played, longest. Under it, filter chips for
*location* (All / Mobile / PC / Other) and *quality/format*
(`"<bits>-bit / <kHz> kHz"`), then two `Play`/`Shuffle` pills (`height 48`,
radius `14`).

Sub-pages (albums, artists, genres, playlists, song lists) are pushed on the
nested navigator with a `PageRouteBuilder`, `210 ms` in and out (`Duration.zero`
under reduced motion), a plain `FadeTransition`. Card grids: columns `4 / 3 / 2`
above `600 / 420 / else`, `crossAxisSpacing 16`, `mainAxisSpacing 18`, extra height
`46 × textScale`, art radius `10`.

→ **PARTIAL** — Nexfy's Library is one `PillSelector` over
Playlists / Albums / Podcasts plus a grid-or-list toggle, a sort button, a local
filter field and `cascade()` row entrance. It has no tile grid of destinations and
no inline browser; its grid is 3 columns on a phone and `gridColumns(width)` above
`640.dp`, which is the same idea as Lokal's breakpoints.
→ The quality / bit-depth / location filter chips: **REJECT** — Nexfy streams from
librespot; there is no file to inspect and no "PC" source to filter by.

### 1.4 Search ("Explore")

`lib/features/search/screens/search_screen.dart`.

The field and the chip shelf are a **fixed `Column` header; only the results
scroll**. Field block: padding `fromLTRB(16, 16, 16, 8)`, radius `24`, a
top-left→bottom-right gradient of two surfaces, a glass border, and
`BoxShadow(0x33000000, blur 16, offset (0, 4))`. Inside, `GlassSearchBar`:
padding `h16 v12`, leading search glyph, text `16`, a clear glyph only when the
field is non-empty. **No focus, size or colour animation at all.**

- **Debounce `300 ms`.** A blank query clears immediately with no timer.
- Chip shelf: `height 38`, horizontal `ListView`, padding `h24`. Chip is an
  `AnimatedContainer(180 ms, easeOutCubic)`, padding `h16 v8`, radius `100`,
  selected = accent at `.18` plus an accent border, label `12 w600`, and once there
  is a query it reads `"<label> · <count>"`. Every tap fires a light haptic.
  Chips: All + seven categories.
- Before a query: a "Recently searched" grid of **track tiles** (not queries),
  columns `(maxWidth / 150).floor().clamp(2, 6)`, tile radius `12`, history capped
  at **20**, persisted. With no history, a centred glyph and three lines.
- Results, always in this order: **songs → artists → albums → album artists →
  folders → year → playlists**, then an online tail. Each group shows a header with
  a count pill and **four rows**, then `"See all (<count>)"`; expansion is local
  state and is not animated. Rows are 48×48 `ListTile`s.
- Online tail: heading "Not in your library", source chips, `results.take(15)`, an
  `8 s` verification timeout before a stream plays.
- **No voice search, no speech input, no barcode or camera entry.**

→ **PARTIAL** — Nexfy's Search debounces at `450 ms` (`Tabs.kt:150`, min two
characters), draws its field as a glass capsule (`height 52`) that lights up in the
accent while focused and runs a `950 ms` linear sweep along its lower edge while a
search is in flight — motion Lokal's field does not have. But the field is item one
of the `LazyColumn` and **scrolls away**, tracks are a flat unbounded list, and
artists/albums/playlists are carousels rather than capped groups. Recent searches
are query chips, not track tiles. Under `LocalPointer` the field is dropped
entirely (the window's title bar owns the query) and results become a single
`WindowResults` grid.
→ Category chips with counts: **MISSING**.
→ Folder and year result groups: **REJECT** — no files, no local year index.

### 1.5 The "You" tab

`konnekted_you.dart`. A **profile and listening page**, not a settings page; it
only links out to Settings. A plain `ListView`, in order:

1. Header: a right-aligned glass gear; `+6`; a `64×64` accent-gradient circle
   avatar (a photo, else the first letter at `28 w700`); `+16`; the name at
   `fontSize 34, height 1.1, letterSpacing −1.2, w800`, `@username` at `14` when it
   differs; `+16`; one or two glass stat cards (radius `18`, value `24 w700`, label
   `size 10` uppercase): **Minutes this week** and **Day streak**.
2. The linked-desktop devices card.
3. **Recap** — a label, a blurb at `13`, then a horizontal strip, `height 136`,
   cards `width 156`, glass radius `20`, inner `all 14`: one card each for weekly,
   monthly, yearly, reading `"<n> min"` at `20 w700` plus the top artist.
4. **Stats** — a Week/Month/Year segmented control, one glass card with three
   figures (Minutes, Plays, Peak hour) at `22 w700` in a `FittedBox`, then **top 3**
   artists, songs and albums, then a hairline and a "Full history" link.
5. **Milestones** — a badge strip, `height 104`, badge `width 76` with a `60`
   glass capsule tinted by tier at `.22`, icon `26`, caption `11 h1.2` over two
   lines. Empty state: "Your first one unlocks at 100 songs."
6. **Accounts** — a `Wrap` of three capsule chips (Last.fm, ListenBrainz, YouTube
   Music) with an `8×8` dot and `CONNECTED` / `CONNECT`.
7. **More** — What's new, Help, About.

Nothing on this screen writes: no sign-in, no sign-out, no avatar picker.

→ **PARTIAL** — Nexfy has no fourth tab. The content exists but is scattered:
`Route.Stats` (`screens/Stats.kt`) already draws minutes, plays, per-day and
per-month charts, a listening streak, peak hours, top songs/artists/albums and
"where you listen from", over a local `listens.jsonl` log; `Home.kt` already shows
`"<n> min ouvidos esta semana"` as a shortcut into it; `Route.Friends` covers
friend activity; Settings is a route off `RootHeader`. Missing: the tab itself, the
profile header, the three recap periods and the milestone badges.

### 1.6 The full player

`lib/features/player/screens/full_player_screen.dart` — the one live player.
`classic_full_player_screen.dart`, `animated_song_scene.dart`, `song_stage.dart`,
`player_layout_sheet.dart` and `player_layout_settings_screen.dart` are **dead
code**: nothing imports or routes to them, so the player's layout presets and their
sliders do not ship. Do not mine that branch.

`PopScope` → `LokalPullToDismiss` → `Scaffold(black)`. Z-stack bottom to top: an
artwork gradient backdrop, an artwork mesh blur, a scrim, optionally a full-bleed
poster, then the chrome. Chrome padding `fromLTRB(23, 0, 23, 14)`;
`compact = maxHeight < 640`.

A fixed column — **nothing scrolls except the lyrics panel and the chip row**:

| Slot | Metrics |
| --- | --- |
| Top bar | `height 58`; back `44×44` circle, centre output pill `maxWidth 215`, cast + overflow |
| Stage | `Expanded`, a `CustomMultiChildLayout` holding cover, info, queue and lyrics slots |
| Seek | `Slider`, `trackHeight 3.5`, `trackGap 5`, gapped track, inactive `white .30`; clocks `11.5 w600` monospace |
| gap | `compact ? 12 : 24`, then `lerp(compact ? 6 : 10, 6, lyricsProgress)` |
| Transport | `height lerp(compact ? 74 : 94, compact ? 48 : 52, l)`, radius `lerp(26, 23, l)`, play glyph `lerp(33, 29, l)`, skip `lerp(29, 26, l)`, primary width `(maxWidth − 16) × .36` |
| Options strip | radius `24`, padding `(12, 6)`, volume slider `trackHeight 4`, `1×26` divider, toggles `36×40` radius `13`, icon `19` |
| Chip row | `height 44`, horizontal list, chip width `(maxWidth − 8) / 4`, icon `19`, text `12 w500`, radius `16`: Queue, Lyrics, Sleep, Cover, Speed, Equalizer |

Title `lerp(compact ? 25 : 29, 18, p) w700 letterSpacing −.55`; artist
`lerp(13, 12, p) w500`; year `lerp(11, 10, p)`. The info block cross-fades on a
song change over `420 ms`. The top bar and the options strip **collapse out** as
the lyrics come in: `Align(heightFactor: 1 − l)` plus `Opacity(1 − l)`.

The stage's cover shrinks to a `52` thumbnail as the queue or the lyrics open;
`infoReserve = textScaler.scale(72)`; `artGap = compact ? 10 : 17`; panel opacity
is `easeOut((q − .15) / .85)` for the queue and `easeOut((l − .12) / .88)` for the
lyrics.

**The layout is not user-configurable.** Two switches only: `fullscreenCover`
(a full-bleed poster whose banner height is measured post-frame and re-set when it
moves more than `.5 px`, with a `ShaderMask` dissolve at stops `[0, .68, 1]`) and
`unboxedPlayerControls` (glyph-only transport centred at 25/50/75 %).

→ **HAVE** — Nexfy's `PlayerSheet.kt` is the same species and in places further
along: a fixed full-screen layout driven by one `f: 0 → 1`, everything animated in
`graphicsLayer` or the draw phase so frames neither recompose nor remeasure; a
living mesh-gradient backdrop; a glass `PlayButton` at `84.dp` with `weight(2f)`
between two side buttons; a six-icon toggle strip (shuffle, repeat, lyrics, queue,
equalizer, devices); a cover that breathes to `.84` when paused.
→ The chip row's Sleep / Cover / Speed entries: **PARTIAL** — Nexfy has
`player/SleepTimer.kt`, no playback-speed control, and moving covers are a
different thing (§2.9).
→ Full-bleed cover mode: **MISSING**.

---

## 2. Motion and gesture

### 2.1 Cover Flow — the carousel

`lib/widgets/lokal/lokal_cover_flow.dart`, class `LokalCoverFlow`. The single most
elaborate thing in the app. On Home it is fed `AlbumGroup`s that each wrap **one
song** (`konnektedSongCover`, key `song:<id>`), so a "cover" is a track, and it
opens on `list.length ~/ 2`, not on the first.

**Geometry.** `size = min(250, maxWidth × .60)`; the stage is `size × 1.40` tall on
a `ColoredBox(Colors.black)` — a dark floor is what makes the reflection read. Each
cover's offset from the facing one is `d`; `ad = |d|`, `near = ad.clamp(0, 1)`,
`hinge = near²(3 − 2·near)` (smoothstep).

```dart
_innerDistance(a) = a >= 1 ? .52 + (a - 1) * .34
                           : .66a - .10a² - .04a³          // C1-continuous
_depthDistance(a) = a >= 1 ? .15 + (a - 1) * .055
                           : .20a - .005a² - .045a³

dx     = side * size * (_innerDistance(ad) + .5 * hinge)
angle  = side * (2·near - near²) * 70°
depth  = -size * _depthDistance(ad)
pivot  = .5 - side * .5 * hinge                            // centre → inner edge
matrix = translate(dx, 0, 0) · [ perspective(3,2) = .20 / size
                               · translate(0, 0, depth)
                               · rotateY(angle + flip·π) ]
origin = Offset(size * pivot, size / 2)
```

Three things in there matter and are easy to get wrong:

- **The facing cover sits flat and forward; its neighbours hinge about their inner
  edge**, so the *outer* edge comes toward the viewer. Positive perspective means a
  negative Y rotation brings the left outer edge forward.
- `dx` is applied to the **projected** cover, outside the perspective division.
  Putting it inside stretches distant panels across their neighbours.
- The inner-distance curve has slope `.34` at `d = 1`, matching the far-field
  spacing, so a drag never lurches as a cover crosses into the stacked region.
  A linear version let tilted panels intersect while still face-on.

**Reflection.** Under each cover, `SizedBox(height: size * .005)`, then a mirror:
`Matrix4.diagonal3Values(1, -1, 1)` inside an `OverflowBox(maxHeight: size)` clipped
to `size × .30`, masked with `BlendMode.dstIn` and
`LinearGradient([0x6AFFFFFF, 0x00FFFFFF], stops: [0, .30])` — the shader is sized to
the whole square but only its first 30 % shows, so it must fade out before the clip.
The front face carries `BoxShadow(0x73000000, blur 40, offset (0, 18))` and radius `6`.

**Snap and drive.** One unbounded `AnimationController` holds the position *in
albums* (2.5 is halfway between the third and fourth).

- Finger travel per album: `_albumWidth = size * .55`.
- Drag: `position -= delta.dx / albumWidth`; past either end the divisor is tripled
  (rubber band).
- Release: project `position + velocity * .30` (about a third of a second of
  fling), round, clamp, then
  `SpringSimulation(SpringDescription(mass: 1, stiffness: 135, damping: 24), …)`
  carrying the release velocity. Velocity in album units is
  `−pixelsPerSecond.dx / albumWidth`.
- **A selection-click haptic for every album the position crosses**, fired from the
  controller listener, not from the gesture.

**Painting.** Only `position ± 5` covers exist (`± 2` in Ultra mode); they are
depth-sorted farthest-first so the facing cover lands on top; `Opacity` is
`1 − (ad − 4).clamp(0, 1)`; each is `Positioned(top: size * .06)` with
`key: ValueKey(album.key)`. Each face is built **once** into a `Map` behind a
`RepaintBoundary` and only its transform animates — rebuilding the image subtree
per tick, and reordering unkeyed covers, were both real bugs
(`docs/mobile-performance-review.md`).

**Hit testing.** A tap in a gap picks the nearest cover by inverting the
*projected* spacing — `_hitDistance(a) = _innerDistance(a) + .17·(2t − t²)` — with a
20-step monotonic binary search. Unprojected matrices give the wrong answer.

**Flip.** Long-pressing the facing cover runs a `450 ms` controller; the back
appears past `.5` and is counter-rotated by π. The back is the album's track list
(`itemExtent 22`, index at `size 10`, title `11.5`) over a `PLAY` pill. Any
movement of the carousel reverses the flip.

**Degraded modes.** Reduced motion or Ultra performance
(`LokalPerformanceScope.enabled = ultraPerformanceMode`, default **false**) flattens
it completely: `dx = d · size · 1.06`, no rotation, no depth, no reflection, no
shadow, `pivot .5`. Note the asymmetry — the *default* `performanceMode` is `true`
and kills the glass shader (§2.8), but leaves Cover Flow fully 3D.

Under the stage, an `AnimatedSwitcher(220 ms, easeOutCubic)` keyed on the album
holds the name (`17 w600`) and artist (`14` muted).

→ **MISSING** — Nexfy has no 3D carousel and no reflection anywhere. Its nearest
relative is `Modifier.carouselDepth()` (`design/Motion.kt:294`), which scales a card
to `1 − .1·enter − .06·leave` and fades it as it crosses either edge of a row,
measured in `onPlaced` and read only while drawing. It also has a cover flip in the
player (`rotationY` + `cameraDistance = 14 × density`, `Motion.turn()` =
`spring(.74, 150)`), which is the same trick at one cover.

### 2.2 The dock when the page scrolls

The shell watches every vertical `ScrollNotification` and follows **the first
non-zero user delta**, including overscroll at the top; ballistic updates cannot
undo a deliberate reversal (`konnekted_shell.dart:82`). `delta > 0` collapses.
Horizontal shelves and the mini player's own gestures are excluded.

Collapsing interpolates three rectangles over `t`:

| Piece | Open | Shut |
| --- | --- | --- |
| Tabs | `(0, 58+8, width−66, 58)` | `(0, 0, 58, 58)` |
| Search | `(width−58, 58+8, 58, 58)` | `(width−58, 0, 58, 58)` |
| Mini | `(0, 0, width, 58)` | `(66, 0, width−132, 58)` |

Overall height lerps `66+58 → 58`. So the dock folds into **one row**: the tab
capsule shrinks to a single circle showing the current tab's icon (`24`, accent,
tap to expand), the search circle stays put, and the mini player **tucks between
them**, losing its artist line and its queue button. A dock with no song never
collapses.

The fold runs on `SpringDescription.withDampingRatio(mass: 1, stiffness: 400,
ratio: 1)` with `snapToEnd: true` (without it the spring rests a hair short of 0 and
the folded layer swallows the tabs' taps), carrying velocity only when the new
target agrees with the current direction. A `420 ms` controller backs it;
reduced motion sets the value outright.

→ **MISSING** — Nexfy's dock is always at full height. Adding this is §4's first
item. Phone-only: a window has no reason to hide its navigation.

### 2.3 The tab lens you can drag

Inside the capsule, `_inset = 4`, slot `= (width − 8) / 3`.

- At rest the lens sits at `inset + slot × selectedIndex` and moves on
  `AnimatedPositioned(380 ms, Curves.easeOutBack)`.
- **A finger can drag it.** While dragging, the position follows
  `(x − slot/2).clamp(inset, width − inset − slot)` with `Duration.zero`, a
  selection haptic fires each time it enters a new slot, and the tab is only
  committed on release.
- While dragged: the whole capsule swells by `16 / width`
  (`AnimatedScale`, `220 ms`, `easeOutCubic`) — glass you can push on — and the
  lens scales to `1.08` over `200 ms`.
- The lens itself turns from a tinted pane into **clear refracting glass**:
  `LokalLens(height: 10, amount: 14, dispersion: 1).scale(t)` over `220 ms`, its
  accent gradient fading to `.20·(1−t)` / `.12·(1−t)` as the refraction comes up.

→ **HAVE, differently** — Nexfy's `Dock` lens is a **two-spring** affair: the
leading edge runs `spring(.62, 620)` and the trailing edge `spring(.74, 230)`, so
the pill stretches on the way and settles with a slight bounce; it squashes
vertically by `6 %` of the stretch, insets by `2dp·(1 − stretch)`, and the tabs
under it are redrawn through the lens path at `1.1 + .08·stretch`. `PillSelector`
(`design/Components.kt:197`) does the same with `settle(455)` / `settle(169)`.
Nexfy cannot be dragged, which is the only part worth taking.

### 2.4 Swipes

| Where | Gesture | What happens |
| --- | --- | --- |
| Any track row, any search song row | drag **right** | Queue. `LokalSwipeToQueue`: threshold `72 px`, max travel `104 px`, one selection haptic on crossing, release queues at `≥72` **or** `≥36` with velocity `>900`; snaps back over `240 ms` `easeOutCubic`. Reveal is the accent at `.16` with a `27` glyph at `left: 23`. The row keeps its tap and long-press, and the action is also a `CustomSemanticsAction`. |
| Mini player | drag **up** | Opens Now Playing under the finger (§2.5). Direction is latched on the first delta; a downward start is left alone. |
| Mini player | drag **sideways** | `direction = \|v\| ≥ 500 ? v : accumulatedDx`; `≤ −48` next, `≥ 48` previous. |
| Player cover | drag **sideways** | Previous / next. Commits at `\|v\| ≥ 700`, or under that when `\|offset\| > width × .25`. A missing neighbour rubber-bands at `.32 · exp(−\|offset\| / max(width·.6, 1))`. Settle `easeOutCubic`, `(180 + 160 · distance) ms`. Selection haptic on commit. Only at rest. |
| Player cover | **double-tap** | Like — never unlike. Medium haptic plus `LokalLikeBurst`, sized `side × .28`: a heart `0 → 1.25` over the first 40 % of `600 ms` easing back to `1`, fading over the last 30 %, with ten accent dots thrown to `.35 → .95` of the radius by a `_BurstPainter`. |
| Player | drag **up** | Queue reveal, in place (§2.6). |
| Player | drag **down** | Closes, 1:1 (§2.5). |
| Lyrics | drag **vertically** | Browse; auto-follow resumes `3200 ms` after the finger lifts. |
| Shuffle / loop toggle | **long-press** | Mode sheets. The only long-presses in the player. |

→ **HAVE** — Nexfy's `TrackRow` (`design/Components.kt:679`) has the same
swipe-right-to-queue, measured as a fraction instead: `SWIPE_THRESHOLD = .28` of the
row width, travel clamped to `.6`, a tick on arming and a press haptic on firing,
settle `Motion.settle(320f)`, with the accent wash at `offset/width × 2.2` capped at
`.9` and a glyph that scales `.7 → 1`. The player's cover already has a throw
(`±fullW/3` or `±1800 px/s`, `Motion.refuse()` when there is nothing next — a
bouncy `spring(.3, 700)`), and the lyrics already open by dragging the cover up.
→ Double-tap to like: **MISSING**.
→ Mini-player sideways skip: **MISSING**.

### 2.5 Mini player → full player

This is done twice over, and the simpler path is the one that is off.

**The hero is disabled on the normal open.** Both ends declare
`Hero(tag: 'konnekted-player-art')` with a plain `RectTween` and a shuttle that
clips `lerp(21, 28, easeInOutCubic(t))` — but `LokalPlayerRoute` sets
`_heroes = ValueNotifier(sourceArtwork == null)` and the shell always supplies the
mini artwork's rect, so `HeroMode` stays off until the route completes.

**What actually moves the artwork** is `LokalPlayerMorph` / `_RenderArtworkPortal`
(`lib/widgets/lokal/lokal_player_artwork.dart`): it reads the mini artwork's global
`Rect` every frame, computes `to = anchorRect.shift(0, −sheetOffset)` and
`rect = Rect.lerp(from, to, progress)`, scales the anchor by
`rect.width / anchor.width`, lerps the corner radius from `source.shortestSide / 2`
to `28` (`0` in full-cover mode), and suppresses the anchor's own paint while the
art is lifted. Reading the live rect each frame is what lets the drag and the route
share one artwork.

**Route motion** (`LokalPlayerMotion`): `spring =
SpringDescription.withDampingRatio(mass: 1, stiffness: 210, ratio: 1)`,
`carryFraction = .25`, `flickVelocity = 700 px/s`,
`travel = max(startTop, 200)`, `snapToEnd: true`, nominal
`transitionDuration = 360 ms` (zero under reduced motion) which `createSimulation`
then overrides with the spring from wherever the controller currently is.
At progress `p`: backdrop `black` at `.6p`, `translate(0, (1 − p) · startTop)`,
`Opacity((p / .12).clamp(0, 1))` — fully opaque by 12 % — and top corners
`36 · ((1 − p) · 8).clamp(0, 1)`, i.e. a `36 px` card until about `p = .875`, then
square.

**`LokalPlayerPull`** — the drag that opens it. Mounted as an `OverlayEntry` on the
root navigator (pushing a route mid-drag would cancel the finger), wrapped in
`IgnorePointer` + `HeroMode(enabled: false)` + `TickerMode(enabled: false)`.
`dragBy` does `progress -= dyDown / travel`, unclamped. On release:

```
opens = velocityDown <= -700 ? true
      : velocityDown >= +700 ? false
      : progress >= .25            // from the mini player
```

Opening fires a selection click, springs to 1 carrying `−v/travel`, then **in the
same frame** removes the overlay and pushes the real route with `initialProgress`
and a shared `playerKey`, so decoded artwork and player state survive the handover.
Cancelling springs back to 0 and removes the entry; a new drag takes over
mid-flight via `resume()`, which simply stops the controller.

**Closing.** `LokalPullToDismiss` notices it is inside a `LokalPlayerRoute` and
forwards the drag 1:1 to the route, clamped to `.001 … 1.0` — never exactly 0, which
would read as dismissed. On release the same rule runs with `fromOpen: true`, so it
stays open only above `.75`; otherwise a light haptic, `_popVelocity = max(0, v)`,
and `pop()`. A cancelled gesture closes if it is below 75 %. The route calls
`didStartUserGesture()` / `didStopUserGesture()` around the drag, waiting for the
controller to stop first.

→ **HAVE** — Nexfy's `PlayerSheet` is one widget that *is* both players, which is
the stronger design: a single `f: 0 → 1` drives cover size and position, backdrop,
corner radius and controls, with the layout fixed at full screen and never
remeasured. `miniH = 68.dp`, `margin = 16.dp`, `dockH = 82.dp`, corners
`34 · (1 − p)^.45` so it becomes a card at once, backdrop alpha `(p × 1.6)`.
Commit thresholds `OPEN_AT = .2` / `CLOSE_AT = .8` with a haptic as the drag crosses
the commit point, a fling at `Motion.FlingVelocity = 1200 px/s` beating distance,
and `ProgressiveBackGesture` shrinking it to `1 − .12·back`. The pages beneath read
`PlayerReveal.open` while drawing and step back to `1 − .06p` behind a `28p` corner
with a `.4p` black veil.
→ Latching the drag direction on the first delta so a downward start never opens
anything: **HAVE** (`PlayerSheet.kt:665`, the `lift[0]` latch does the same for
lyrics-versus-sheet).

### 2.6 The queue, revealed in place

`lib/widgets/lokal/lokal_queue_reveal.dart`. One `0 … 1` value that a finger moves
directly and a spring settles, so a drag, the queue chip and Back all run the *same*
animation and any of them can take over mid-flight.

- `carryFraction = .3` — "deliberately under half, because nothing else starts the
  gesture by accident".
- `flickVelocity = 450 px/s`; `spring = SpringDescription(mass: 1, stiffness: 380,
  damping: 36)` — close to critically damped.
- `travel = max(min(width, height) × .8, 200)`.
- Release: open at `v ≥ 450`, close at `v ≤ −450`, else `> .7` when already open and
  `≥ .3` when closed. A state change fires a selection click.
- While open, the queue's own header takes over: tap closes, vertical drag feeds
  `−delta.dy` back into the same value.
- The cover shrinks to a `52` thumbnail as the queue rises; the list is the same
  `QueueList` the route uses, with `embedded: true` and a top fade at `[0, .05]`.

→ **MISSING** — Nexfy opens the queue as a `QueueSheet` modal
(`PlayerSheet.kt:353`). Its lyrics reveal is already built exactly this way
(a `lyricsIn` `Animatable` that a drag moves and `Motion.reveal()` —
`spring(.78, 200)` — settles, committing at `.35` or half the fling velocity), so
the pattern is in the codebase; only the queue does not use it.

### 2.7 Synced lyrics — timing and drawing

Renderer `_FlowingLyrics` / `_FlowingLyricsState`, `full_player_screen.dart:1626`.
Source `lib/services/lyrics_service.dart`.

**Where the words come from.** `loadLyricsForSong`: a manual override, then embedded
tags / sidecar files / a paired desktop's JSON, then online (LRCLIB, KuGou and
friends) at an `18 s` timeout per source. Candidates are ranked **word-timed >
line-synced > plain**. An `[offset:±ms]` tag is folded in at parse time. The model is
`LyricsLine { timestamp, text, words?, translation? }` with
`LyricsWord { start, end, text }`.

**Filling in missing word ends** (`playbackLines`, resolved once per song): enhanced
LRC gives word *starts* only, so each word's end becomes the next word's start, and
the last word's end is `start + 0.85 × (lineEnd − start)` — the sweep deliberately
finishes before the line does. `lineEnd` is the next line's timestamp, or `+5 s` for
the last. A line with no word timings gets one whole-line window capped at `6 s`.

**The clock is extrapolated, not polled.** The engine's position feed is a
`Timer.periodic` at `50 ms` while playing (`250 ms` paused) — far too coarse for a
word wipe. So one `Ticker` runs at display rate and the position notifier only
**re-anchors** a `(anchorPos, anchorAt)` pair; every frame reads

```
posNow = anchorPos + clamp(wallClockElapsed, 0, 600) * playbackSpeed
```

— at most `600 ms` of extrapolation before it must hear from the engine again. In
performance mode the ticker is throttled to `33 333 µs` (~30 fps). Repainting stops
entirely when `!playing` and the layout has settled.

**Choosing the line.** `_indexAt(ms)` binary-searches the last `timestamp <= ms`.
**Scrolling uses a `300 ms` lead** (`_indexAt(ms + 300)`); the emphasis uses the
un-led index — so the list starts moving to the next line slightly before that line
lights up.

**Smoothing**, per tick with `dt` clamped to `0.05 s` — exponential approach, not
tweens, so it is frame-rate independent and any target change is picked up at once:

```
_offset += (target - _offset) * (1 - exp(-dt * 7.0))     // scroll position
_focus  += (a      - _focus ) * (1 - exp(-dt * 10.0))    // which line is lit
```

**Drawing**, per line, with `d = |i − _focus|`:

| Property | Value |
| --- | --- |
| opacity | `d < 1 ? 1 − .66d : .34 − .18 · ((d − 1) / 3).clamp(0, 1)` |
| scale | `1 − .1 · min(d, 1)`, anchored `centerLeft` |
| blur | `d < 1 ? 0 : min((d − 1) × .9, 2.8)`, applied only above `.3`, via `ImageFilter.blur(tileMode: decal)`; **off entirely in performance mode** |
| base style | `fontSize 31`, `height 1.2`, `w800`, white; gap between lines `26` |
| translation sub-line | `fontSize 18`, `height 1.25`, `w600`, gap `6`, alpha `.72` on the active line; revealed by `heightFactor` + `Opacity` + a `6 px` rise |

**The word wipe** (`_LyricPainter`, `lyric_motion_line.dart:182`). Per word,
`p = (position − word.start) / span`; the fill is a gradient drawn into a
`saveLayer` with `BlendMode.dstIn`, feathered by
`min(.45, fontSize × .6 / wordWidth)` with `edge = p(1 + 2·feather) − feather`.
Reduced motion and performance mode swap the gradient for a hard `clipRect`. Each
word also *lifts* by `.06 × smoothstep(…)` of the font size. Words of **seven
characters or fewer** that are held long enough (thresholds from `≥ 1.05 s` to
`≥ max(.9, n × .2)` depending on length) additionally grow letter by letter:
per-letter phase `letter × span × .09`, window `span × 1.5`, attack `.25`, hold to
`.3`, release to `.75`, swell `.04 + earned × .08` where
`earned = clamp((span/1000 − .4) / 2.6, 0, 1)²`. Box direction is RTL-aware
throughout.

**There is no `ScrollController`.** The lines are a `Stack` of `Positioned` widgets
at `anchorY + _tops[i] − _offset`, where `anchorY = viewportHeight × 0.28` — the
playing line sits a little above a third of the way down. Lines outside `±80 px` are
culled, with a binary search for the first visible one. The whole panel is a
`ClipRect` under a `ShaderMask` fading at stops `[0, .07, .9, 1]`.

**Browsing.** A vertical drag pauses following and moves `_offset` directly,
clamped to `[−(44 + 26 + height × 0.3), max(_tops.last, 0)]`. Auto-follow resumes on
a `3200 ms` timer after the finger lifts. **Tapping a line** cancels that timer, sets
following, and seeks — with no animation, because the `_offset` spring glides there
by itself.

**Before the first line**, three `14×14` dots `10` apart fill up as
`p = position / firstLineTimestamp` runs out:
`alpha = .28 + .72 × clamp(p × 3 − k, 0, 1)` for dot `k`.

**Unsynced lyrics** fall back to one `SingleChildScrollView` of joined text at
`fontSize 26`, `height 1.35`, `w800`.

**Opening** runs `_lyricsProgress` over `420 ms` on
`Curves.easeInOutCubicEmphasized`, fires a selection click, and closes the queue in
the same gesture. As it rises, the player's top bar and options strip collapse out
(§1.6) and the cover shrinks to a `52` thumbnail.

→ **HAVE** — Nexfy's `LyricsView` (`player/PlayerSheet.kt:1456`) is the same design
and in two respects richer: it animates `d` itself with `Motion.settle(169f)` rather
than easing a focus scalar; it adds a **wave entrance** keyed to the reveal gesture
(`wave = ((enter() − d × .08) × 2.2).coerceIn(0, 1)`, `k = 1 − (1 − wave)³`, a
`30dp` rise) so the lyric arrives outward from the words being sung rather than as a
block; it carries a translation (`pt-BR`) fetched once per song; and the unsung part
of the active line settles to `ink × (1 − .62 · focus)` instead of plain dim. Scale
`(.88 + .12·focus)`, alpha `(1 − .2d).coerceIn(.3, 1) × (.55 + .45·focus)`, blur
above `d > 1.2` at `(d − 1.2) × .9` dp, edge fade `[0, .12, .8, 1]`, line gap `18dp`,
sizes `30 / 24 / 22 / 19` by full-screen and synced, all scaled by
`LocalLook.lyricsScale`. Tapping a line seeks. The word sweep reads a per-frame
clock (`withFrameMillis`) and leads by `50 ms` — "the eye leads the ear".
→ **PARTIAL**, one real gap: Nexfy picks the active line from `position`, which
`rememberPosition` ticks at **`250 ms`** (lead `+250 ms`), so line changes quantise
to a quarter-second, while Lokal's anchored extrapolation makes them exact. Nexfy
already proves the fix inside the same file — the word sweep's per-frame clock — so
it is a few lines, not a rewrite.
→ Nexfy scrolls with a real `LazyColumn` and `animateScrollToItem`; Lokal's hand-laid
`Stack` buys exact control and culling. Not worth changing.

### 2.8 Shaders

Two fragment shaders, both declared in `pubspec.yaml` and loaded once at start-up by
`LokalGlassShaders.load()` in `lib/widgets/lokal/lokal_glass.dart:23`. **Neither is
used by the player** — they are the dock, the chips, the sheets and the toasts.

**`shaders/lokal_glass_lens.frag`** — the refraction, ported from Kyant0's
AndroidLiquidGlass (Apache-2.0). Loaded only when
`ui.ImageFilter.isShaderFilterSupported`. Uniforms: `u_size`, `u_shape_size`,
`u_shape_offset`, `u_radius`, `u_height`, `u_amount`, `u_depth`, `u_dispersion`,
`u_texture`. Functions `sdRoundedRect`, `gradSdRoundedRect`, `circleMap`,
`sampleAt`, `main`.

- Signed distance to the rounded rect; outside the band (`−sd ≥ u_height`) it
  passes the backdrop through untouched.
- Inside the band it samples **from further in**, so the edge magnifies what is
  behind: `d = circleMap(1 − (−sd / u_height)) × −u_amount`, where
  `circleMap(x) = 1 − sqrt(1 − x²)` — a **circular** profile, which is what makes it
  read as a solid lens rather than frosted plastic.
- The gradient is taken at `radius × 1.5` and blended with the direction to the
  centre by `u_depth`: `1` bulges the whole face like a magnifier, `0` bends only
  the edge.
- Dispersion splits the sample into **seven** taps (red, orange, yellow, green,
  cyan, blue, purple) weighted `/3.5`, `/3.0`, `/7.0`, with intensity scaled by
  `(x·y) / (halfW·halfH)` so the fringe appears at the corners.
- It detects whether the engine handed it the shape's own clip or the whole screen
  by comparing `u_size` to `u_shape_size + 8`, and flips `uv.y` on GLES.

**`shaders/lokal_glass_rim.frag`** — the specular edge, drawn as the shader of a thin
stroke along the shape. Uniforms `u_size`, `u_radius`, `u_color`, `u_angle`,
`u_falloff`; `intensity = pow(|dot(grad, (cos θ, sin θ))|, u_falloff)`, so the rim is
brightest where its normal faces the light **and at its opposite**.

Three thicknesses feed them (`LokalGlassThickness`): `thin` (blur `2`, tint `.22`,
lens `height 12 / amount 24`), `regular` (blur `8`, tint `.40`, lens `48 / 48`,
`fraction .5`), `sheet` (blur `12`, tint `.70`, lens `24 / 16`). With the lens,
glass stays clear — a light blur and a 40 % tint, the lens doing the work; without
it, a heavier frost (`sigma 14 / 18 / 24`) so text keeps its contrast; and in
performance mode a flat tint plus 20 % more opacity. **`performanceMode` defaults to
`true`**, so most installs never see the lens at all.

→ **HAVE** — Nexfy's `design/Lens.kt` runs the same idea as one AGSL/SkSL source
shared by Android and desktop: rounded-rect SDF, a falloff band, a shift toward the
centre, and a chromatic split. It is simpler on purpose — a cubic `k³` falloff
instead of a circular profile, **three** taps instead of seven (`shift × 1.15` red,
`shift` green, `shift × 0.85` blue), no magnifier mode — and it adds something
Lokal's does not have: it lifts vibrancy `1.2×` and then pulls the backdrop
`calm` of the way toward the theme base, so icons and text on the glass always read.
`GlassLevel` carries `depth / band / blur / calm / tint` the way
`LokalGlassThickness` carries its five.
→ The rim as a **shader**: **REJECT** — Nexfy draws it as two gradient outlines
(`glassSheen` + `glassRim`, `Glass.kt:146`). A second shader program for a 1dp
stroke is not worth the pipeline.
→ `u_depth` (whole-face magnifier) and 7-tap dispersion: **MISSING**, and only
worth it on the dock lens, which is the one place Lokal uses them.

### 2.9 Smaller motion worth knowing

- **Moving covers.** `MotionArtView` plays looping *video* artwork (Apple Music
  Motion Art, Spotify Canvas, Tidal, community art) with
  `VideoPlayerOptions.mixWithOthers` so ExoPlayer never takes audio focus, falling
  back to a Ken Burns pan and then to a still. Suppressed during exclusive
  bit-perfect output. The mini player's `42` circle runs it too.
  → **MISSING** — Nexfy's `LivingArtwork` (`design/Artwork.kt:158`) is a 36-second
  mesh-gradient orbit built from the cover's palette, drawn into a quarter-size
  layer and scaled `4×`, stopped by battery saver or reduced motion. Different
  animal; video canvases would be a core-side fetch, not a UI change.
- **Artwork colour.** k-means with `k = 5` over the cover decoded at `32×32`
  (alpha `< 32` dropped, farthest-point seeding, 8 iterations), sorted by
  `saturation × .75 + lightness × .25`. `FullPlayerVisuals.fromPalette` then picks
  the swatch maximising `saturation × (1 − |lightness − .43|)` among luminances in
  `(.015, .85)`, nudges hue `−7°` in `[10, 65]`, and derives tones at lightness
  `.105 / .255 / .31 / .37` plus a play-button colour at `.865`. Palette changes
  cross-fade over `650 ms` by interpolating the *resolved* visuals, so hues never
  jump mid-transition.
  → **HAVE** — `design/CoverPixels.kt` and `rememberCoverPalette`; commit
  `0891658` ("pick a cover's colours the way Palette did") is this exact problem.
- **Tab re-tap** fires a double-pulse haptic and pops the nested navigator to root.
  → **HAVE** — Nexfy pops to root, then animates the list to item 0
  (`NexfyRoot.kt:583`), which is one step better.
- **Back**, three deep: pop the nested route, else return to Home, else require a
  second back within `2 s` with a "Press back again to exit" toast.
  → **PARTIAL** — Nexfy pops the stack and returns to Home
  (`NexfyRoot.kt:485`); there is no double-back-to-exit. Phone-only.
- **Reduced motion** is honoured everywhere by `MediaQuery.disableAnimationsOf`,
  which also zeroes `AppConstants.animationFast/Normal` globally.
  → **HAVE** — `Motion.reduced` plus `systemAnimationsOff` / `powerSaveMode`; every
  token in `design/Motion.kt` already follows it so screens never check.
- **Text scaling** is budgeted by hand: `58 × scale.clamp(1, 2)` for library tiles,
  `60 × scale.clamp(1, 1.6)` for pinned headers, and the dock clamps its own
  scaling to `1.3` on the grounds that fixed chrome should not grow — "the full
  player carries large text beyond it".
  → **MISSING** — worth stealing as a habit, not as a number.

---

## 3. The home greeting

`lib/features/konnekted/lokal_home_greeting.dart` (80 lines), fed by
`konnektedGreetingProvider` in `konnekted_data.dart:70`. Ported from desktop Lokal's
`src/homeGreeting.mjs`. **It is a statement about the user's listening, not the time
of day** — the time of day is only the fallback.

**Input.** `RecentlyPlayedRepository.getRecentHistory(limit: 40)` — the local Isar
play log (`RecentlyPlayedEntry { song, playedAt, listenedMs }`, up to 50 000 rows
kept). Recomputed on a **debounced** `watchHistory()` stream: the first change goes
through at once, then at most one every `500 ms`, because a desktop library merge
arrives in bursts.

**Preparation.** Keep entries whose `playedAt` is after the epoch and not more than
5 minutes in the future; sort newest first; truncate to 40.

**The ladder** — first rule that fires wins. Each returns a sentence; the headline
is then derived by matching that sentence.

| # | Rule | Window | Test | Headline / line |
| --- | --- | --- | --- | --- |
| 0 | too little | all | fewer than **5** plays | the salutation + a time-of-day line |
| 1 | repeat | newest **8** | any one song id appears **≥ 3** times | **On repeat** — "The repeat button has been getting a workout." |
| 2 | calm | all 40 | of the entries that have genres, **≥ 6** of them, and **≥ 55 %** match a 12-word calm list | **Taking it easy** — "Your mellow side has been getting more play." |
| 3 | energetic | all 40 | same, against a 13-word energy list | **High energy** — "High-energy listening has been winning lately." |
| 4 | night | newest **30** | **≥ 6** hours and **≥ 50 %** fall at `h ≥ 22` or `h < 5` | **Night owl** — "Your after-dark listening rhythm is holding steady." |
| 5 | morning | newest **30** | same at `5 ≤ h < 9` | **Early bird** — "You have a soundtrack for the early hours." |
| 6 | streak | all 40 | **≥ 4** distinct calendar days within the last 7 | **In a groove** — "Your listening streak has some momentum." |
| 7 | **variety** | all 40 | **≥ 8** non-blank artist names and `distinct / total ≥ .8` | **Wide rotation** — "Lots of different sounds in your rotation lately." |
| 8 | fallback | — | — | the salutation + a time-of-day line |

So **"Wide rotation" means: of your last 40 plays, at least 8 named an artist, and
at least 80 % of those artist names were different from each other** — you have not
been circling one or two artists. It is the last rule before the fallback, which is
why it is the one you see on a varied library.

Details that matter:

- Genres are the song's own `genre` tag, split on `[,;|]`, lowercased, matched by
  **substring** (`'lo-fi'` catches `'lo-fi hip hop'`). Rules 2–3 only consider
  entries that *have* a genre, and need at least 6 of them, so an untagged library
  skips straight past.
- Hours are local wall-clock hours of the play, not of now.
- Artist names are `trim().toLowerCase()`; blanks are dropped before the count, so
  the `≥ 8` is 8 *named* plays.
- The salutation is `< 12` morning, `< 18` afternoon, else evening; the fallback
  line has a fourth band at `≥ 23` ("Keeping the late hours company?").
- **Only the pattern is ever named — never a track, artist, album or genre — and
  nothing is stored.** The headline is produced by a `switch` on the returned
  sentence, which couples the two strings; a new line needs both edited together.

→ **MISSING** — Nexfy's `greeting()` (`screens/Tabs.kt:104`) is three branches on
the clock: `5..11` "Bom dia", `12..17` "Boa tarde", else "Boa noite", rendered as
`"{greeting}, {firstName}"` with no second line. The data to do better is already
there: `services.listens` is a local `listens.jsonl` of
`Listen { uri, title, artists, artistIds, album, at, ms, contextUri, durationMs }`,
and `Home.kt` already reads it for the stats shortcut.
**Rules 0, 1, 4, 5, 6, 7 port verbatim** against that log — `at` is the wall-clock
start and `artists` is the name string. **Rules 2 and 3 cannot run**: there is no
genre on a `Listen`, and `uniffi.nexfy_core` exposes no `genres` field at all
(`grep genres shared/.../nexfy_core.kt` → nothing), so the mood rules would need a
new core call for artist genres before they could exist.

---

## 4. Verdicts at a glance

`HAVE` 10 · `PARTIAL` 8 · `MISSING` 11 · `REJECT` 3 (32 items).

The three `REJECT`s, so nobody relitigates them: the library's quality/bit-depth and
location filter chips; folder and year search groups; and the rim as a second
fragment shader. Everything else the clone does with folder pickers, M3U import,
library scanning, USB/UAC2 output and bit-perfect labelling is out of scope rather
than rejected — Nexfy has no files to inspect, so those were never items.

---

## 5. What it would cost Nexfy

Ranked. "Desktop" means the change lands in `commonJvmMain` and the window sees it
too unless gated.

1. **The greeting's second line.** *Cheapest real win.* A new
   `shared/.../ui/screens/Greeting.kt` holding the ladder as a pure function over
   `List<Listen>`, called from `Home.kt:154` beside `greeting()`, with the subheader
   drawn under the `LargeTitle` inside an `AnimatedContent`. Six of the eight rules
   port as-is; drop the two genre rules or precede them with a core call for artist
   genres. No new state, no core change, ~120 lines. **Touches the desktop** — and
   should, the line is about listening, not about a phone. Keep Lokal's discipline:
   name the pattern, never the artist.
2. **The dock collapsing on scroll.** `NexfyRoot.kt` `Dock` plus one scroll
   observer. Nexfy has no equivalent of `NotificationListener`, so the signal has to
   come from the `LazyListState` the root already owns per tab — a
   `derivedStateOf` on `firstVisibleItemScrollOffset` deltas, or a
   `NestedScrollConnection` wrapped around `ScreenContent`. Then animate the dock's
   height and the tabs' width with the springs it already has, and shrink
   `LocalBottomSpace` in step so content does not jump. Moderately invasive: it
   changes the one layout every screen pads against. **Gate on `!LocalPointer`** —
   a window should not hide its navigation under a trackpad flick.
3. **The queue as an in-place reveal.** Replace `QueueSheet` with a reveal driven by
   one `Animatable` inside `PlayerSheet.kt`, as `lyricsIn` already is: the cover
   shrinks, the queue rises, and the chip, the drag and Back all move the same
   value. Contained to one file, because the pattern and its springs
   (`Motion.reveal()`) are already there — the work is making the cover's vertical
   drag arbitrate three destinations (sheet, lyrics, queue) instead of two. Shared
   with the desktop, and fine there: a window can reveal a queue too.
4. **Cover Flow.** A new `design/CoverFlow.kt`. The geometry above transfers
   directly to `graphicsLayer { rotationY; cameraDistance; translationX;
   transformOrigin }` with an `Animatable` position in album units. Lokal's
   spring converts to `dampingRatio = 24 / (2·sqrt(135)) ≈ 1.03` at
   `stiffness = 135` — just past critical, so it never overshoots, which is
   exactly `Motion.settle(135f)`. The
   reflection is a second draw of the same painter under a vertical
   `BlendMode.DstIn` gradient — cheap in Compose. Budget real time for two things
   Lokal got wrong first: **key and cache the faces** so reordering never remounts
   an image, and invert the *projected* spacing for hit testing. Expect it to be
   phone-shaped — a window would want `PointerCarousel`'s arrows instead — so gate
   the whole shelf on `!LocalPointer` and keep today's `Carousel` under a mouse.
   The largest item here; do not start it before 1–3.
5. **Double-tap the cover to like.** `PlayerSheet.kt`: add
   `onDoubleTap` to the cover's gesture block and a burst overlay. One-way (like,
   never unlike) is the right call — a double-tap is too easy to fire by accident
   to let it remove a save. Half a day, one file, harmless on the desktop.
6. **A fourth "You" destination.** Mostly re-arrangement: Nexfy already has stats,
   streak, friends and settings as routes; a tab would give them a home and a
   profile header. Touches `NexfyRoot.kt`'s `Tab` enum, the dock (three tabs become
   four, so the lens slot arithmetic changes), and every `Tab` `when`. Worth doing
   only alongside item 2, since both rework the dock. Recap periods and milestone
   badges are new features, not re-arrangement — price them separately.
