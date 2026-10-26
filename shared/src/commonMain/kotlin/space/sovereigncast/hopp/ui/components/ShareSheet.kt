package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.data.DownloadFiles
import space.sovereigncast.hopp.data.DownloadState
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.episodeFileName
import space.sovereigncast.hopp.feed.Chapter
import space.sovereigncast.hopp.feed.Cue
import space.sovereigncast.hopp.resources.*
import space.sovereigncast.hopp.ui.IncomingLinks
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.ui.theme.Hopp

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
 * What a share hands on, named after what arrives (maintainer 2026-10-26, after a listener
 * preferred AntennaPod's choice): Hopp's link, the website the feed names, the audio file's
 * address, the downloaded file itself, or — for a show — its feed address.
 */
enum class ShareKind { Hopp, Website, AudioLink, AudioFile, Feed }

internal object ShareLinks {
    /** [link] if it is a web address — what a feed's `<link>` should be and not always is. */
    fun web(link: String?): String? = link?.trim()?.takeIf { l ->
        val lower = l.lowercase()
        (lower.startsWith("https://") || lower.startsWith("http://")) && l.length > 10 && l.none(Char::isWhitespace)
    }

    /** The audio file's address; from [startSeconds] as a media fragment (`#t=`), which browsers play from. */
    fun audio(url: String, startSeconds: Long?): String =
        url.substringBefore('#') + (startSeconds?.takeIf { it > 0 }?.let { "#t=$it" } ?: "")

    /** The choices there are: only what exists — no website the feed does not name, no file not downloaded. */
    fun kinds(show: Boolean, website: Boolean, audio: Boolean, file: Boolean): List<ShareKind> = buildList {
        add(ShareKind.Hopp)
        if (website) add(ShareKind.Website)
        if (show) add(ShareKind.Feed)
        else {
            if (audio) add(ShareKind.AudioLink)
            if (file) add(ShareKind.AudioFile)
        }
    }

    /** The last choice where it is one here, else Hopp's link. */
    fun first(kinds: List<ShareKind>, last: String?): ShareKind = kinds.firstOrNull { it.name == last } ?: ShareKind.Hopp
}

/**
 * Sharing a show, an episode, a chapter or a line of the transcript through
 * the system's share sheet. Hopp's link goes to www.sovereigncast.space/open
 * — feed, episode GUID and start, nothing else — which opens Hopp where it
 * is installed and offers the feed address for any other app where not; the
 * others hand on the feed's own website, the audio file or its address, the
 * feed address. The text is shown before it goes, and the choice is kept for
 * the next share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(target: ShareTarget, onDismiss: () -> Unit) {
    val podcasts: PodcastRepository = koinInject()
    val episodes: EpisodeRepository = koinInject()
    val settings: SettingsRepository = koinInject()
    val files: DownloadFiles = koinInject()
    val show by remember(target.podcastId) { podcasts.show(target.podcastId) }.collectAsState(null)
    val lastKind by remember { settings.settings }.collectAsState(null)
    val actions = LocalPlatformActions.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var fromHere by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<ShareKind?>(null) }
    val item: EpisodeItem? = when (target) {
        is ShareTarget.Show -> null
        is ShareTarget.Episode -> target.item
        is ShareTarget.AtChapter -> target.item
        is ShareTarget.Quote -> target.item
    }
    val website by produceState<String?>(null, target) {
        value = ShareLinks.web(if (item == null) podcasts.podcastMeta(target.podcastId)?.link else episodes.details(item.id)?.link)
    }
    val s = show ?: return
    val audio = ShareLinks.web(item?.enclosureUrl)
    val file = item?.takeIf { actions.canShareFiles && it.downloadState == DownloadState.DONE }?.downloadPath?.takeIf { files.exists(it) }
    val kinds = ShareLinks.kinds(item == null, website != null, audio != null, file != null)
    val kind = picked?.takeIf { it in kinds } ?: ShareLinks.first(kinds, lastKind?.shareAs)

    val position = (target as? ShareTarget.Episode)?.positionMs?.takeIf { it >= 1_000 }
    val start: Long? = when (target) {
        is ShareTarget.Show -> null
        is ShareTarget.Episode -> position?.takeIf { fromHere }?.div(1000)
        is ShareTarget.AtChapter -> target.chapter.startSeconds.toLong()
        is ShareTarget.Quote -> target.cue.startMs?.div(1000)
    }
    val at = start?.let { formatTime(it * 1000) }
    val told = when (target) {
        is ShareTarget.Show -> stringResource(Res.string.share_text_show, s.title)
        is ShareTarget.Episode -> if (at != null) stringResource(Res.string.share_text_episode_at, target.item.title, s.title, at)
            else stringResource(Res.string.share_text_episode, target.item.title, s.title)
        is ShareTarget.AtChapter -> stringResource(Res.string.share_text_chapter, target.chapter.title, target.item.title, s.title)
        is ShareTarget.Quote -> stringResource(Res.string.share_text_quote, target.cue.text.trim(), target.item.title, s.title, at ?: "")
    }
    // As AntennaPod words it: the website has no start, so the text says it.
    val social = (target as? ShareTarget.Episode)?.let {
        if (at != null) stringResource(Res.string.share_social_episode_at, it.item.title, s.title, at)
        else stringResource(Res.string.share_social_episode, it.item.title, s.title)
    } ?: told
    val text: String? = when (kind) {
        ShareKind.Hopp -> told + "\n" + IncomingLinks.shareLink(s.feedUrl, item?.guid, start)
        ShareKind.Website -> website?.let { social + "\n" + it }
        ShareKind.AudioLink -> audio?.let { ShareLinks.audio(it, start) }
        ShareKind.Feed -> s.feedUrl
        ShareKind.AudioFile -> null
    }
    val fileName = if (file != null && item != null) episodeFileName(s.title, item.title, file.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "") else null
    fun done() {
        scope.launch { settings.update { it.copy(shareAs = kind.name) } }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.share_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                kinds.forEach { k ->
                    val (title, note) = when (k) {
                        ShareKind.Hopp -> stringResource(Res.string.share_kind_hopp) to stringResource(Res.string.share_kind_hopp_note)
                        ShareKind.Website -> stringResource(if (item == null) Res.string.share_kind_website_show else Res.string.share_kind_website_episode) to
                            stringResource(Res.string.share_kind_website_note)
                        ShareKind.AudioLink -> stringResource(Res.string.share_kind_audio) to stringResource(Res.string.share_kind_audio_note)
                        ShareKind.AudioFile -> stringResource(Res.string.share_kind_file) to
                            (file?.let { files.size(Path(it)) }?.takeIf { it > 0 }?.let { stringResource(Res.string.share_kind_file_note_size, formatBytes(it)) }
                                ?: stringResource(Res.string.share_kind_file_note))
                        ShareKind.Feed -> stringResource(Res.string.share_kind_feed) to stringResource(Res.string.share_kind_feed_note)
                    }
                    KindRow(title, note, k == kind) { picked = k }
                }
            }
            val box = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant).padding(12.dp)
            if (kind == ShareKind.AudioFile) Column(box) {
                Text(fileName.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            } else if (text != null) Text(text, box, style = MaterialTheme.typography.bodyMedium)
            if (position != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.share_from, formatTime(position)), style = MaterialTheme.typography.bodyLarge,
                        color = if (kind == ShareKind.AudioFile) colors.onSurfaceVariant else colors.onSurface,
                    )
                    Text(
                        stringResource(when (kind) {
                            ShareKind.Website -> Res.string.share_from_text
                            ShareKind.AudioFile -> Res.string.share_from_file
                            else -> Res.string.share_from_link
                        }),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                }
                Switch(checked = fromHere && kind != ShareKind.AudioFile, onCheckedChange = { fromHere = it }, enabled = kind != ShareKind.AudioFile)
            }
            Text(
                stringResource(when (kind) {
                    ShareKind.Hopp -> Res.string.share_note
                    ShareKind.Website -> Res.string.share_note_website
                    ShareKind.AudioLink -> Res.string.share_note_audio
                    ShareKind.AudioFile -> Res.string.share_note_file
                    ShareKind.Feed -> Res.string.share_note_feed
                }),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            Button(
                onClick = {
                    if (kind == ShareKind.AudioFile) { if (file != null && fileName != null) actions.shareFile(file, fileName) }
                    else text?.let { actions.shareText(it, s.title) }
                    done()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.share_send)) }
            // The same text to paste anywhere — a quote, a chapter, a link (device feedback 2026-10-12).
            if (text != null) OutlinedButton(onClick = { actions.copyText(text, s.title); done() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.share_copy))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun KindRow(title: String, note: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    // The darker orange: ring and frame stand out from the sheet (3:1), not only the fill.
    val value = Hopp.colors.valueText
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) value else MaterialTheme.colorScheme.outlineVariant, shape)
            .background(if (selected) value.copy(alpha = 0.08f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = value))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
