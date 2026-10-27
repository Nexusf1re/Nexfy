package dev.nexfy.app.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.theme.accentColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import uniffi.nexfy_core.Track
import uniffi.nexfy_core.UserFace

/**
 * Which columns a list can fill. The header and every row of one list share
 * the same value, so a label can never stand over a column that is not
 * there: a list without dates (an album, an artist's songs) simply has no
 * date column, instead of a column of dashes.
 */
data class TrackColumns(
    /** The songs come from more than one album, so naming it says something. */
    val album: Boolean,
    /** The entries carry an added-at date (playlists, liked songs). */
    val date: Boolean,
    /** Several people add to this playlist and their faces are being shown. */
    val addedBy: Boolean = false,
    /**
     * The album has no room for a column of its own and is folded under the
     * title instead, after the artists, with the header relabelled to say so:
     * the date keeps its column rather than being the first thing to go.
     */
    val albumBelow: Boolean = false,
    /** The date in its short form, "14/03/24", for a narrow page. */
    val shortDate: Boolean = false,
)

// The fixed columns, in dp, and the share of the rest the album takes. The
// flexible space splits in these proportions at every window size, so the
// title column — the widest, and the one that can always ellipsize — is
// where most of a resize lands.
private val NumberWidth = 40.dp
private val CoverSize = 40.dp
private val CoverGap = 12.dp
private val FaceWidth = 34.dp
// Wide enough for the long form, "24 de dez. de 2024": the title column has
// room to give, and a date that reads "de 20…" says nothing at all.
private val DateWidth = 152.dp
private val ShortDateWidth = 96.dp
private val DurationWidth = 72.dp
private val ActionsWidth = 72.dp
private val ColumnGap = 16.dp
private const val AlbumWeight = .42f
private val RowHeight = 56.dp

/** Inset of the cells from the band's edge, shared by the header and the rows. */
private val CellInset = 8.dp

private val brazil = Locale.forLanguageTag("pt-BR")
private val dayMonth = DateTimeFormatter.ofPattern("d 'de' MMM", brazil)
private val dayMonthYear = DateTimeFormatter.ofPattern("d 'de' MMM 'de' yyyy", brazil)
private val shortDay = DateTimeFormatter.ofPattern("dd/MM/yy", brazil)

/** "14 de mar.", with the year when it is not this one; [short], "14/03/24". */
private fun addedLabel(ms: Long, short: Boolean = false): String {
    val day = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return day.format(
        when {
            short -> shortDay
            day.year == LocalDate.now().year -> dayMonth
            else -> dayMonthYear
        },
    )
}

private val TrackColumns.dateWidth get() = if (shortDate) ShortDateWidth else DateWidth

/**
 * The head of a track table: the column labels over the app's hairline.
 * The rows are [TrackTableRow], kept as items of the same lazy list so a
 * playlist of thousands of songs still only composes what is on screen.
 */
@Composable
fun TrackTable(columns: TrackColumns, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = CellInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("#", Modifier.width(NumberWidth), TextAlign.Center)
            // Over the cover and the title, which share one cell.
            Spacer(Modifier.width(CoverSize + CoverGap))
            Label(if (columns.albumBelow) "Título / Álbum" else "Título", Modifier.weight(1f))
            if (columns.album) Label("Álbum", Modifier.weight(AlbumWeight).padding(start = ColumnGap))
            // The faces need no word: the photo says who added it.
            if (columns.addedBy) Spacer(Modifier.width(FaceWidth))
            if (columns.date) {
                Label(if (columns.shortDate) "Adicionada" else "Adicionada em", Modifier.width(columns.dateWidth).padding(start = ColumnGap))
            }
            Label("Duração", Modifier.width(DurationWidth), TextAlign.End)
            Spacer(Modifier.width(ActionsWidth))
        }
        HorizontalDivider(color = c.outlineVariant)
    }
}

/** A column label: the small spaced capitals the collection header already uses. */
@Composable
private fun Label(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) = Text(
    text.uppercase(),
    modifier,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize = 11.sp,
    letterSpacing = 1.sp,
    fontWeight = FontWeight.Medium,
    textAlign = align,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

/**
 * One song as a table row: its number, the cover with the title and the
 * artists, then whatever [columns] says the list carries. Under the pointer
 * the band lifts and the number turns into a play glyph; the playing song
 * keeps the accent tint and the bars it has everywhere else.
 */
@Composable
fun TrackTableRow(
    track: Track,
    number: Int,
    columns: TrackColumns,
    onClick: () -> Unit,
    /**
     * Asked for the song's menu. The point is where in the window it was
     * asked for, so a menu can open there; the ⋯ reports its own corner.
     */
    onMenu: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    /** The song is in the user's library (false until the answer arrives). */
    liked: Boolean = false,
    /** Toggles the like, where the list offers it. */
    onLike: (() -> Unit)? = null,
    /** Who added it, in playlists several people add to. */
    addedBy: UserFace? = null,
) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val press = remember { MutableInteractionSource() }
    val hover = remember { MutableInteractionSource() }
    val over by hover.collectIsHoveredAsState()
    val ground by animateColorAsState(
        when {
            playing -> accent.copy(.12f)
            over -> c.onSurface.copy(.06f)
            else -> Color.Transparent
        },
        label = "trackRowGround",
    )
    Row(
        modifier.fillMaxWidth().height(RowHeight)
            .weightedPress(press, 10.dp).clip(RoundedCornerShape(10.dp)).background(ground)
            .hoverable(hover)
            // Outside the click, so the right button is answered on the way
            // in and the row never plays as well. Not even installed where a
            // finger drives the UI, which has no second button to press.
            .then(if (LocalPointer.current) Modifier.secondaryClickAt(onMenu) else Modifier)
            .clickable(press, null, onClick = onClick)
            .alpha(if (track.playable) 1f else .4f)
            .padding(horizontal = CellInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(NumberWidth), contentAlignment = Alignment.Center) {
            when {
                // The playing song is marked the same way here as on a phone,
                // even under the pointer: clicking it starts it over.
                playing -> PlayingBars(accent, Modifier.size(16.dp))
                over -> Icon(Icons.Rounded.PlayArrow, null, tint = c.onSurface, modifier = Modifier.size(20.dp))
                else -> Text("$number", color = c.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
        Cover(track.imageUrl, Modifier.size(CoverSize), 8.dp)
        Spacer(Modifier.width(CoverGap))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (playing) accent else c.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val artists = if (track.explicit) "🅴 ${track.artists}" else track.artists
            Text(
                if (columns.albumBelow && track.album.isNotBlank()) "$artists · ${track.album}" else artists,
                color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (columns.album) {
            Cell(track.album, Modifier.weight(AlbumWeight).padding(start = ColumnGap))
        }
        if (columns.addedBy) {
            Box(Modifier.width(FaceWidth), contentAlignment = Alignment.Center) {
                addedBy?.let { UserAvatar(it, Modifier.size(24.dp)) }
            }
        }
        if (columns.date) {
            Cell(
                track.addedAtMs?.let { addedLabel(it, columns.shortDate) }.orEmpty(),
                Modifier.width(columns.dateWidth).padding(start = ColumnGap),
            )
        }
        Cell(durationLabel(track.durationMs.toLong()), Modifier.width(DurationWidth), TextAlign.End)
        // A quiet row shows neither glyph: a column of them down a long list
        // is noise. They come out under the pointer, and stay out on the
        // playing row and wherever the heart is already lit.
        Row(Modifier.width(ActionsWidth), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            onLike?.let { like ->
                RowAction(liked || over || playing) {
                    RowIcon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (liked) "Remover das curtidas" else "Curtir",
                        if (liked) accent else c.onSurfaceVariant,
                        like,
                    )
                }
            }
            RowAction(over || playing) {
                MenuIcon(Icons.Rounded.MoreHoriz, "Mais opções", c.onSurfaceVariant, onMenu)
            }
        }
    }
}

/** A plain cell: one line of the quieter text, clipped to its column. */
@Composable
private fun Cell(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) = Text(
    text,
    modifier,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize = 13.sp,
    textAlign = align,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

/**
 * A glyph that fades in under the pointer, in the same breath as the
 * carousel's arrows, and keeps its place in the row while it is away.
 */
@Composable
private fun RowAction(shown: Boolean, content: @Composable () -> Unit) = Box(
    Modifier.size(32.dp),
    contentAlignment = Alignment.Center,
) {
    AnimatedVisibility(
        shown,
        enter = fadeIn(Motion.enter(Motion.Short)) + scaleIn(Motion.enter(Motion.Short), initialScale = .85f),
        exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = .85f),
        label = "rowAction",
    ) { content() }
}

/** A small round button at the end of a row. */
@Composable
private fun RowIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) = Box(
    Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Icon(icon, description, tint = tint, modifier = Modifier.size(18.dp)) }

/**
 * The same glyph, reporting the corner it sits in: a menu asked for by a
 * button belongs under that button, not wherever the pointer last was.
 */
@Composable
private fun MenuIcon(icon: ImageVector, description: String, tint: Color, onClick: (Offset) -> Unit) {
    // Not state: it changes on every scroll and nothing needs redrawing for it.
    val place = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .onGloballyPositioned { place[0] = it }
            .clickable {
                val coords = place[0]
                onClick(
                    if (coords != null && coords.isAttached) {
                        coords.localToWindow(Offset(0f, coords.size.height.toFloat()))
                    } else {
                        Offset.Zero
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(18.dp)) }
}

