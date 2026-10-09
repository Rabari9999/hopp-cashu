package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import space.sovereigncast.hopp.resources.episode_play
import space.sovereigncast.hopp.resources.episode_resume
import space.sovereigncast.hopp.resources.episode_shownotes
import space.sovereigncast.hopp.resources.np_pause
import space.sovereigncast.hopp.resources.sheet_chapters
import space.sovereigncast.hopp.resources.sheet_people
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
    Notes(Res.string.episode_shownotes), Chapters(Res.string.sheet_chapters), People(Res.string.sheet_people)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EpisodeScreen(id: Long, onShow: (Long) -> Unit, onGone: () -> Unit) {
    val vm: EpisodeViewModel = koinViewModel(key = "episode-$id") { parametersOf(id) }
    val item by vm.item.collectAsState()
    val show by vm.show.collectAsState()
    val details by vm.details.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val gone by vm.gone.collectAsState()
    LaunchedEffect(gone) { if (gone) onGone() }
    val player: PlayerController = koinInject()
    val nav = LocalEpisodeNav.current
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val e = item ?: return
    val playingId by remember(player) { player.state.map { if (it.isPlaying) it.itemId else null }.distinctUntilChanged() }.collectAsState(null)
    val playing = playingId == e.id.toString()
    val duration = (e.durationSeconds ?: 0) * 1000
    val linkStyle = SpanStyle(color = colors.onSurface, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)
    val markStyle = SpanStyle(color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val notes = remember(details?.description, linkStyle, markStyle) {
        details?.description?.let { showNotes(it, linkStyle, markStyle) { s -> player.playAt(e, s * 1000) } }
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(e.imageUrl ?: show?.imageUrl, 120.dp, corner = 18.dp)
                Spacer(Modifier.size(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    show?.let { s ->
                        Text(s.title, Modifier.clip(RoundedCornerShape(6.dp)).clickable { onShow(s.id) },
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(e.title, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    val facts = listOfNotNull(
                        formatDate(e.publishedAt).takeIf { it.isNotEmpty() },
                        duration.takeIf { it > 0 }?.let { formatTime(it) },
                        details?.let { d -> listOfNotNull(d.season?.let { "S$it" }, d.episodeNumber?.let { "E${it.toLong()}" }).joinToString("").takeIf { it.isNotEmpty() } },
                        downloadLabel(e),
                    )
                    Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (playing) player.engine.pause() else nav.play(e)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(if (playing) HoppIcons.Pause else HoppIcons.Play, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when {
                            playing -> stringResource(Res.string.np_pause)
                            e.positionMs > 0 && !e.played && duration > 0 -> stringResource(Res.string.episode_resume, formatTime(duration - e.positionMs))
                            else -> stringResource(Res.string.episode_play)
                        },
                    )
                }
                EpisodeMenu(e)
            }
            if (!e.played && e.positionMs > 0 && duration > 0) {
                LinearProgressIndicator(
                    progress = { (e.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = colors.onSurface, trackColor = Hopp.colors.progressTrack, drawStopIndicator = {},
                )
            }
        }
        // Show notes, chapters and people as tabs rather than one long column
        // (device feedback 2026-10-08) — only the ones the episode has.
        val persons = details?.persons.orEmpty()
        val tabs = buildList {
            if (!notes.isNullOrEmpty()) add(EpisodeTab.Notes)
            if (chapters.isNotEmpty()) add(EpisodeTab.Chapters)
            if (persons.isNotEmpty()) add(EpisodeTab.People)
        }
        val current = tabs.getOrNull(selectedTab.coerceAtMost(tabs.lastIndex.coerceAtLeast(0)))
        if (tabs.size > 1) stickyHeader {
            SecondaryTabRow(selectedTabIndex = tabs.indexOf(current).coerceAtLeast(0), containerColor = colors.background) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = t == current, onClick = { selectedTab = i }, text = { Text(stringResource(t.label)) })
                }
            }
        } else if (current != null) item {
            Text(stringResource(current.label), Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.titleMedium)
        }
        when (current) {
            EpisodeTab.Notes -> item {
                SelectionContainer {
                    Text(notes!!, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            EpisodeTab.Chapters -> items(chapters, key = { "ch${it.startSeconds}" }) { ch ->
                Row(
                    Modifier.fillMaxWidth().clickable { player.playAt(e, (ch.startSeconds * 1000).toLong()) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(formatTime((ch.startSeconds * 1000).toLong()), color = Hopp.colors.valueText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(ch.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
            EpisodeTab.People -> item {
                FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    persons.forEach { p -> AssistChip(onClick = {}, label = { Text(listOfNotNull(p.name, p.role).joinToString(" · ")) }) }
                }
            }
            null -> Unit
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
