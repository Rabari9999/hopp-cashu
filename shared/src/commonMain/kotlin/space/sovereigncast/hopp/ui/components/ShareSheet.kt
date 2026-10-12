package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.feed.Chapter
import space.sovereigncast.hopp.feed.Cue
import space.sovereigncast.hopp.resources.*
import space.sovereigncast.hopp.ui.IncomingLinks
import space.sovereigncast.hopp.ui.LocalPlatformActions

/** What is being shared (device feedback 2026-10-12). */
sealed interface ShareTarget {
    val podcastId: Long
    data class Show(override val podcastId: Long) : ShareTarget
    /** An episode; [positionMs] offers "from here" — off unless switched on. */
    data class Episode(val item: EpisodeItem, val positionMs: Long? = null) : ShareTarget { override val podcastId get() = item.podcastId }
    data class AtChapter(val item: EpisodeItem, val chapter: Chapter) : ShareTarget { override val podcastId get() = item.podcastId }
    data class Quote(val item: EpisodeItem, val cue: Cue) : ShareTarget { override val podcastId get() = item.podcastId }
}

/**
 * Sharing a show, an episode, a chapter or a line of the transcript through
 * the system's share sheet: a short text and a link to
 * www.sovereigncast.space/open — feed, episode GUID and start, nothing else
 * — which opens Hopp where it is installed and offers the feed address for
 * any other app where not. The text is shown before it goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(target: ShareTarget, onDismiss: () -> Unit) {
    val podcasts: PodcastRepository = koinInject()
    val show by remember(target.podcastId) { podcasts.show(target.podcastId) }.collectAsState(null)
    val actions = LocalPlatformActions.current
    val colors = MaterialTheme.colorScheme
    var fromHere by remember { mutableStateOf(false) }
    val s = show ?: return
    val position = (target as? ShareTarget.Episode)?.positionMs?.takeIf { it >= 1_000 }
    val start: Long? = when (target) {
        is ShareTarget.Show -> null
        is ShareTarget.Episode -> position?.takeIf { fromHere }?.div(1000)
        is ShareTarget.AtChapter -> target.chapter.startSeconds.toLong()
        is ShareTarget.Quote -> target.cue.startMs?.div(1000)
    }
    val guid = when (target) {
        is ShareTarget.Show -> null
        is ShareTarget.Episode -> target.item.guid
        is ShareTarget.AtChapter -> target.item.guid
        is ShareTarget.Quote -> target.item.guid
    }
    val at = start?.let { formatTime(it * 1000) }
    val text = when (target) {
        is ShareTarget.Show -> stringResource(Res.string.share_text_show, s.title)
        is ShareTarget.Episode -> if (at != null) stringResource(Res.string.share_text_episode_at, target.item.title, s.title, at)
            else stringResource(Res.string.share_text_episode, target.item.title, s.title)
        is ShareTarget.AtChapter -> stringResource(Res.string.share_text_chapter, target.chapter.title, target.item.title, s.title)
        is ShareTarget.Quote -> stringResource(Res.string.share_text_quote, target.cue.text.trim(), target.item.title, s.title, at ?: "")
    } + "\n" + IncomingLinks.shareLink(s.feedUrl, guid, start)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.share_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant).padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            if (position != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.share_from, formatTime(position)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = fromHere, onCheckedChange = { fromHere = it })
            }
            Text(stringResource(Res.string.share_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Button(onClick = { actions.shareText(text, s.title); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.share_send)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
