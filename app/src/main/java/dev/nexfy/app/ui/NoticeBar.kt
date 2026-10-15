package dev.nexfy.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.LibraryEdits
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.theme.accentColor
import kotlinx.coroutines.delay

/** How long a notice stays: longer when it offers a way back. */
private const val NOTICE_MS = 3_000L
private const val UNDO_MS = 6_000L

/** The last library change, as a dark pill that rises above the dock. */
@Composable
fun NoticeBar(edits: LibraryEdits, modifier: Modifier) {
    val notice by edits.notice.collectAsState()
    val haptics = rememberHaptics()
    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        delay(if (n.undo != null) UNDO_MS else NOTICE_MS)
        edits.dismiss(n.id)
    }
    AnimatedContent(
        notice,
        modifier,
        transitionSpec = {
            (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { it / 2 } + fadeOut())
        },
        contentKey = { it?.id },
        label = "notice",
    ) { n ->
        if (n == null) return@AnimatedContent
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.shadow(12.dp, CircleShape).clip(CircleShape).background(Color(0xFF1B1B20))
                    .border(1.dp, Color.White.copy(.08f), CircleShape)
                    .padding(start = 18.dp, end = if (n.undo != null) 6.dp else 18.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    n.message, Modifier.padding(vertical = 6.dp).weight(1f, fill = false),
                    color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                n.undo?.let { undo ->
                    Text(
                        "Desfazer",
                        Modifier.padding(start = 10.dp).clip(CircleShape).clickable {
                            haptics.press()
                            edits.dismiss(n.id)
                            undo()
                        }.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = accentColor(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
