package space.sovereigncast.hopp.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.feed.Cue
import space.sovereigncast.hopp.feed.indexAt
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.sheet_transcript
import space.sovereigncast.hopp.resources.transcript_failed
import space.sovereigncast.hopp.resources.transcript_follow
import space.sovereigncast.hopp.resources.transcript_no_match
import space.sovereigncast.hopp.resources.transcript_search
import space.sovereigncast.hopp.ui.components.formatTime
import space.sovereigncast.hopp.ui.theme.Hopp

/** The player's transcript sheet (ADR 0012) — see [TranscriptView]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptSheet(item: EpisodeItem, positionMs: Long, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 20.dp)) {
            Text(stringResource(Res.string.sheet_transcript), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            TranscriptView(item, positionMs, onSeek)
        }
    }
}

/**
 * A transcript (ADR 0012): it follows playback with the current line
 * marked — [positionMs] null while the episode is not the one playing — a
 * tap jumps there, and the field above searches it. Scrolling by hand stops
 * the following until "Follow along" brings it back. In the player's sheet
 * and as the episode page's tab (device feedback 2026-10-09).
 */
@Composable
fun TranscriptView(item: EpisodeItem, positionMs: Long?, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val episodes: EpisodeRepository = koinInject()
    val loaded by produceState<Result<List<Cue>>?>(null, item.id) { value = runCatching { episodes.transcript(item) } }
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    val list = rememberLazyListState()
    val byHand = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) follow = false
                return Offset.Zero
            }
        }
    }
    Column(modifier) {
        val result = loaded
        val cues = result?.getOrNull()
        when {
            result == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            cues.isNullOrEmpty() -> Text(stringResource(Res.string.transcript_failed), color = colors.onSurfaceVariant)
            else -> {
                val timed = cues.first().startMs != null
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text(stringResource(Res.string.transcript_search)) },
                )
                val q = query.trim()
                val shown = remember(cues, q) {
                    cues.withIndex().filter { q.isEmpty() || it.value.text.contains(q, ignoreCase = true) }
                }
                val current = if (timed && positionMs != null) cues.indexAt(positionMs) else -1
                LaunchedEffect(current, follow, q) {
                    if (follow && q.isEmpty() && current >= 0) list.animateScrollToItem((current - 1).coerceAtLeast(0))
                }
                if (current >= 0 && !follow && q.isEmpty()) TextButton(onClick = { follow = true }) { Text(stringResource(Res.string.transcript_follow)) }
                if (shown.isEmpty()) Text(stringResource(Res.string.transcript_no_match), Modifier.padding(vertical = 16.dp), color = colors.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().weight(1f).nestedScroll(byHand), state = list) {
                    itemsIndexed(shown, key = { _, c -> c.index }) { pos, (i, cue) ->
                        val active = i == current
                        val speakerChanged = cue.speaker != null && cue.speaker != shown.getOrNull(pos - 1)?.value?.speaker
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .then(if (active) Modifier.background(colors.surfaceVariant) else Modifier)
                                .then(if (cue.startMs != null) Modifier.clickable { follow = true; onSeek(cue.startMs) } else Modifier)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            if (speakerChanged || (q.isNotEmpty() && cue.speaker != null)) {
                                Text(cue.speaker!!, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
                            }
                            // Times where it helps to know: while searching, and when not playing along.
                            if ((q.isNotEmpty() || positionMs == null) && cue.startMs != null) {
                                Text(formatTime(cue.startMs), style = MaterialTheme.typography.labelSmall, color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                highlight(cue.text, q, Hopp.colors.valueText),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (active || current < 0 || q.isNotEmpty()) colors.onSurface else colors.onSurfaceVariant,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

private fun highlight(text: String, query: String, color: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    if (query.isEmpty()) { append(text); return@buildAnnotatedString }
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) { append(text.substring(from)); break }
        append(text.substring(from, at))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(text.substring(at, at + query.length)) }
        from = at + query.length
    }
}
