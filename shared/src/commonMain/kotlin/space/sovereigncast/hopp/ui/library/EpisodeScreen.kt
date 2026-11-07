package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import space.sovereigncast.hopp.ui.components.TabbedPage
import space.sovereigncast.hopp.ui.value.BoostPill
import space.sovereigncast.hopp.ui.value.BoostSheet
import space.sovereigncast.hopp.ui.value.ValueBarViewModel
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.*
import space.sovereigncast.hopp.resources.episode_play
import space.sovereigncast.hopp.resources.episode_resume
import space.sovereigncast.hopp.resources.episode_shownotes
import space.sovereigncast.hopp.resources.sheet_comments
import space.sovereigncast.hopp.resources.np_pause
import space.sovereigncast.hopp.resources.sheet_chapters
import space.sovereigncast.hopp.resources.sheet_people
import space.sovereigncast.hopp.resources.sheet_transcript
import space.sovereigncast.hopp.resources.episode_clips
import space.sovereigncast.hopp.resources.episode_clip_at
import space.sovereigncast.hopp.resources.episode_play_clip
import space.sovereigncast.hopp.ui.player.TranscriptView
import space.sovereigncast.hopp.ui.components.PersonRow
import space.sovereigncast.hopp.ui.components.ChapterRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import space.sovereigncast.hopp.ui.components.ShareTarget
import space.sovereigncast.hopp.ui.components.ShareSheet
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.IconButton
import space.sovereigncast.hopp.resources.favorite_add
import space.sovereigncast.hopp.resources.favorite_remove
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.ui.components.formatTime
import space.sovereigncast.hopp.ui.components.showNotes
import space.sovereigncast.hopp.ui.theme.Hopp

/**
 * An episode's own page (device feedback 2026-10-08, as in Pocket Casts):
 * tapping an episode opens this rather than playing it — the show, title and
 * facts, one button to play or continue, the people, the show notes with
 * tappable links and time marks, the chapters.
 */
private enum class EpisodeTab(val label: StringResource) {
    Notes(Res.string.episode_shownotes), Chapters(Res.string.sheet_chapters), Transcript(Res.string.sheet_transcript),
    Clips(Res.string.episode_clips), People(Res.string.sheet_people), Comments(Res.string.sheet_comments)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpisodeScreen(id: Long, onShow: (Long) -> Unit, onGone: () -> Unit, onOpenWallet: () -> Unit = {}, startSeconds: Long? = null) {
    val vm: EpisodeViewModel = koinViewModel(key = "episode-$id") { parametersOf(id) }
    val item by vm.item.collectAsState()
    val show by vm.show.collectAsState()
    val details by vm.details.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val chaptersLoaded by vm.chaptersLoaded.collectAsState()
    val timeWidth = space.sovereigncast.hopp.ui.components.rememberChapterTimeWidth(chapters)
    val hasValue by vm.hasValue.collectAsState()
    val gone by vm.gone.collectAsState()
    LaunchedEffect(gone) { if (gone) onGone() }
    val player: PlayerController = koinInject()
    val nav = LocalEpisodeNav.current
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    var boosting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<ShareTarget?>(null) }
    val e = item ?: return
    val playingId by remember(player) { player.state.map { if (it.isPlaying || it.isBuffering) it.itemId else null }.distinctUntilChanged() }.collectAsState(null)
    val playing = playingId == e.id.toString()
    val duration = (e.durationSeconds ?: 0) * 1000
    val linkStyle = SpanStyle(color = colors.onSurface, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)
    val markStyle = SpanStyle(color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
    val notes = remember(details?.description, linkStyle, markStyle) {
        details?.description?.let { showNotes(it, linkStyle, markStyle) { s -> player.playAt(e, s * 1000) } }
    }
    // Show notes, chapters and people as tabs rather than one long column
    // (device feedback 2026-10-08) — only the ones the episode has; a swipe
    // moves between them (2026-10-09).
    // Transcript and clips too (device feedback 2026-10-09) — clips only
    // here: in the player they would interrupt what is playing.
    val showPersons by vm.showPersons.collectAsState()
    // The episode's own people, else the show's (the namespace's fallback).
    val persons = details?.persons.orEmpty().ifEmpty { showPersons }
    val clips = details?.soundbites.orEmpty().filter { it.duration > 0 }
    // All at once, when the details are in (device feedback 2026-10-17): the pager
    // keeps its page by tab, so chapters known from the feed alone, with the show
    // notes arriving a moment later in front of them, kept it on Chapters —
    // depending on which was quicker. Now it always opens on the show notes.
    val tabs = if (details == null) emptyList() else buildList {
        if (!notes.isNullOrEmpty()) add(EpisodeTab.Notes)
        // From what the feed declares, not from what has loaded — a tab that
        // arrives late would push the others aside under the listener's thumb.
        // Chapters may come without a file too (Podlove, the notes); a file is worth the wait, the rest is there at once.
        if (chapters.isNotEmpty() || (e.chaptersUrl != null && !chaptersLoaded)) add(EpisodeTab.Chapters)
        if (details?.transcripts?.isNotEmpty() == true) add(EpisodeTab.Transcript)
        if (clips.isNotEmpty()) add(EpisodeTab.Clips)
        if (persons.isNotEmpty()) add(EpisodeTab.People)
        // Always: only the relays know whether there are any, and they are asked on a tap.
        add(EpisodeTab.Comments)
    }
    val pager = rememberPagerState { tabs.size }

    TabbedPage(
        tabs.map { stringResource(it.label) }, pager, singleLabel = true,
        header = {
            Column {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(e.imageUrl ?: show?.imageUrl, 120.dp, corner = 18.dp)
                    Spacer(Modifier.size(14.dp))
                    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        show?.let { s ->
                            Text(s.title, Modifier.clip(RoundedCornerShape(6.dp)).clickable { onShow(s.id) },
                                color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(e.title, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        val facts = listOfNotNull(
                            formatDate(e.publishedAt).takeIf { it.isNotEmpty() },
                            duration.takeIf { it > 0 }?.let { formatTime(it) },
                            details?.let { d -> listOfNotNull(d.season?.let { "S$it" }, d.episodeNumber?.let { "E${it.toLong()}" }).joinToString("").takeIf { it.isNotEmpty() } },
                            // Downloaded (or on the way), else how large it is (user request
                            // 2026-10-18) — from the feed data loaded here when the row has none yet.
                            downloadLabel(e) ?: episodeSize(if (e.enclosureLength != null) e else e.copy(enclosureLength = details?.enclosure?.length)),
                        )
                        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (playing) player.engine.pause() else nav.play(e)
                        },
                        modifier = Modifier.weight(1f),
                        // Narrower than a button's own padding: on a phone beside Boost and three
                        // icons "Pause" came out as "Pa…" (device check 2026-10-20).
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(if (playing) HoppIcons.Pause else HoppIcons.Play, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        // Begun: only the time left, beside the play icon. "Continue · 1:20:00 left" came out as "Cont…"
                        // beside Boost and three icons (maintainer 2026-11-07); a screen reader still says all of it.
                        val left = if (!playing && e.positionMs > 0 && !e.played && duration > 0) formatTime(duration - e.positionMs) else null
                        val spoken = left?.let { stringResource(Res.string.episode_resume, it) }
                        Text(
                            left ?: stringResource(if (playing) Res.string.np_pause else Res.string.episode_play),
                            if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Boost this episode right here (device feedback 2026-10-09).
                    if (hasValue && space.sovereigncast.hopp.ui.LocalPlatformActions.current.canPay) BoostPill { boosting = true }
                    // A favourite, found again on Home (device feedback 2026-10-11).
                    val actions: EpisodeActionsViewModel = LocalEpisodeActions.current ?: koinViewModel()
                    IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); actions.setFavorite(e, !e.favorite) }) {
                        Icon(
                            if (e.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            stringResource(if (e.favorite) Res.string.favorite_remove else Res.string.favorite_add),
                            tint = if (e.favorite) colors.onSurface else colors.onSurfaceVariant,
                        )
                    }
                    // To others (device feedback 2026-10-12): "from here" only where it was left.
                    IconButton(onClick = { sharing = ShareTarget.Episode(e, e.positionMs.takeIf { it > 0 && !e.played }) }) {
                        Icon(Icons.Rounded.Share, stringResource(Res.string.share_title), tint = colors.onSurfaceVariant)
                    }
                    EpisodeMenu(e, favorite = false)
                }
                // Opened from a shared link that names a time: start there, if wanted.
                if (startSeconds != null) FilledTonalButton(
                    onClick = { player.playAt(e, startSeconds * 1000) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text(stringResource(Res.string.share_play_from, formatTime(startSeconds * 1000))) }
                if (!e.played && e.positionMs > 0 && duration > 0) {
                    LinearProgressIndicator(
                        progress = { (e.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = colors.onSurface, trackColor = Hopp.colors.progressTrack, drawStopIndicator = {},
                    )
                } else Spacer(Modifier.height(8.dp))
            }
        },
    ) { index ->
        if (tabs.getOrNull(index) == EpisodeTab.Transcript) {
            // The position only while this episode is the one loaded — then it reads along.
            val position by remember(player, e.id) {
                player.state.map { s -> s.positionMs.takeIf { s.itemId == e.id.toString() } }.distinctUntilChanged()
            }.collectAsState(null)
            TranscriptView(e, position, onSeek = { ms -> player.playAt(e, ms) }, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 8.dp),
                onShare = { cue -> sharing = ShareTarget.Quote(e, cue) })
            return@TabbedPage
        }
        // Above the keyboard, for an answer to the last comment (device feedback 2026-10-16).
        LazyColumn(Modifier.fillMaxSize().imePadding()) {
            when (tabs.getOrNull(index)) {
                EpisodeTab.Notes -> item {
                    SelectionContainer {
                        Text(notes!!, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                EpisodeTab.Chapters -> if (!chaptersLoaded) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    item { Text(stringResource(Res.string.share_long_press), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                    // By index: start times from a feed can repeat, and a repeated key crashes (code review 2026-10-17, H7).
                    itemsIndexed(chapters, key = { i, _ -> "ch$i" }) { _, ch ->
                        ChapterRow(ch, active = false, onSeek = { player.playAt(e, (ch.startSeconds * 1000).toLong()) }, withImages = chapters.any { it.imageUrl != null },
                            onShare = { sharing = ShareTarget.AtChapter(e, ch) }, timeWidth = timeWidth)
                    }
                }
                EpisodeTab.Clips -> itemsIndexed(clips, key = { i, _ -> "clip$i" }) { _, c ->
                    val start = (c.startTime * 1000).toLong()
                    val length = (c.duration * 1000).toLong()
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(Res.string.episode_play_clip)) { player.playClip(e, start, length) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(HoppIcons.Play, null, Modifier.size(20.dp), tint = Hopp.colors.valueText)
                        Column(Modifier.weight(1f)) {
                            Text(c.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.episode_clip_at, formatTime(start)), style = MaterialTheme.typography.bodyMedium)
                            Text(formatTime(start) + " · " + formatTime(length), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                EpisodeTab.Transcript -> Unit
                EpisodeTab.People -> items(persons) { p -> PersonRow(p, show = show?.title) }
                EpisodeTab.Comments -> item {
                    val comments: space.sovereigncast.hopp.ui.comments.CommentsViewModel = koinViewModel(key = "comments-${e.id}") { parametersOf(e.id) }
                    space.sovereigncast.hopp.ui.comments.CommentsPane(comments, onTimestamp = { s -> player.playAt(e, s * 1000) }, modifier = Modifier.padding(16.dp))
                }
                null -> Unit
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    sharing?.let { t -> ShareSheet(t) { sharing = null } }
    if (boosting) {
        val valueVm: ValueBarViewModel = koinViewModel()
        // Where the listener is: the player's position while this episode is loaded.
        val position = player.state.value.takeIf { it.itemId == e.id.toString() }?.positionMs ?: e.positionMs
        BoostSheet(
            valueVm, item = e, showTitle = show?.title.orEmpty(), positionMs = position,
            onDismiss = { boosting = false }, onOpenWallet = { boosting = false; onOpenWallet() },
        )
    }
}
