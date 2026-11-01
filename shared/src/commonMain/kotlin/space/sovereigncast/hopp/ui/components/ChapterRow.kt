package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.image_full
import space.sovereigncast.hopp.feed.Chapter
import space.sovereigncast.hopp.ui.theme.Hopp

/**
 * A chapter as ADR 0012 lists it: its image, time and title, the current one
 * marked; a tap jumps there, and the chapter's link (device feedback
 * 2026-10-09) opens on its own — "github.com ↗" under the title.
 */
@Composable
fun ChapterRow(
    ch: Chapter, active: Boolean, onSeek: () -> Unit, modifier: Modifier = Modifier, withImages: Boolean = ch.imageUrl != null,
    /** A long press shares the chapter (device feedback 2026-10-12). */
    onShare: (() -> Unit)? = null,
    /** The list's widest time ([rememberChapterTimeWidth]): the times end on one line, the titles start on one. */
    timeWidth: Dp = Dp.Unspecified,
) {
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    Row(
        modifier.fillMaxWidth().onSecondaryClick(onShare).combinedClickable(onClick = onSeek, onLongClick = onShare)
            .then(if (active) Modifier.background(colors.surfaceVariant) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // When some chapters have a picture, the others keep its place, so the times line up.
        // Whole, not cut to the square; a tap shows it full screen (device feedback 2026-10-17).
        if (ch.imageUrl != null) {
            var viewing by remember { mutableStateOf(false) }
            FittedImage(
                ch.imageUrl, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = stringResource(Res.string.image_full)) { viewing = true },
                blur = 8.dp,
            )
            if (viewing) ImageViewer(ch.imageUrl) { viewing = false }
        } else if (withImages) Spacer(Modifier.size(48.dp))
        // Times right-aligned in a column of their own, the titles beside them in one line
        // (maintainer 2026-11-01: "1:05:10" pushed its title further in than "4:11").
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                formatTime((ch.startSeconds * 1000).toLong()),
                Modifier.alignByBaseline().then(if (timeWidth.isSpecified) Modifier.width(timeWidth) else Modifier),
                color = Hopp.colors.valueText, style = chapterTimeStyle(), textAlign = TextAlign.End, maxLines = 1,
            )
            Column(Modifier.weight(1f).alignByBaseline()) {
                Text(ch.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                val url = ch.url
                val host = url?.let { linkHost(it) }
                if (url != null && host != null) Text(
                    "$host ↗",
                    Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp)).clickable { runCatching { uri.openUri(url) } }.padding(vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onSurface,
                )
            }
        }
    }
}

/** A chapter's time: bold, with digits of one width so the column's times line up. */
@Composable
private fun chapterTimeStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

/** The width of the widest time among [chapters], for [ChapterRow]'s time column. */
@Composable
fun rememberChapterTimeWidth(chapters: List<Chapter>): Dp {
    val measurer = rememberTextMeasurer()
    val style = chapterTimeStyle()
    val density = LocalDensity.current
    return remember(chapters, style, density) {
        val widest = chapters.maxOfOrNull { measurer.measure(formatTime((it.startSeconds * 1000).toLong()), style, maxLines = 1).size.width } ?: 0
        with(density) { widest.toDp() }
    }
}
