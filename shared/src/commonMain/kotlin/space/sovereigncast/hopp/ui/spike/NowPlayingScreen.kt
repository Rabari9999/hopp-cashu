package space.sovereigncast.hopp.ui.spike

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.np_back15
import space.sovereigncast.hopp.resources.np_boost
import space.sovereigncast.hopp.resources.np_chapter_of
import space.sovereigncast.hopp.resources.np_close
import space.sovereigncast.hopp.resources.np_forward30
import space.sovereigncast.hopp.resources.np_next_chapter
import space.sovereigncast.hopp.resources.np_pause
import space.sovereigncast.hopp.resources.np_play
import space.sovereigncast.hopp.resources.np_previous_chapter
import space.sovereigncast.hopp.resources.np_progress
import space.sovereigncast.hopp.resources.np_speed
import space.sovereigncast.hopp.resources.np_streaming
import space.sovereigncast.hopp.resources.np_value_later
import space.sovereigncast.hopp.resources.sheet_chapters
import space.sovereigncast.hopp.resources.sheet_community
import space.sovereigncast.hopp.resources.sheet_people
import space.sovereigncast.hopp.resources.sheet_transcript
import space.sovereigncast.hopp.ui.components.ChapterProgressBar
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatTime
import space.sovereigncast.hopp.ui.theme.Hopp

/** Now playing, laid out as in ADR 0019: cover with the chapter control,
 *  title, progress with chapter gaps, controls, value bar, sheets. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(model: PlaybackSpikeModel, ui: SpikeUi) {
    val episode = ui.selected ?: return
    val feed = ui.feed ?: return
    val state by model.engine.state.collectAsState()
    val chapters = ui.chapters
    val position = state.positionMs
    val duration = state.durationMs.takeIf { it > 0 } ?: ((episode.durationSeconds ?: 0) * 1000)
    val current = chapters.current(position)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showChapters by remember { mutableStateOf(false) }
    val valueLater = stringResource(Res.string.np_value_later)
    val speedLabel = stringResource(Res.string.np_speed)
    val colors = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = model::close) { Icon(HoppIcons.ChevronDown, stringResource(Res.string.np_close)) }
                Text(feed.podcast.title ?: "", Modifier.weight(1f), textAlign = TextAlign.Center, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(48.dp))
            }

            // Cover — the chapter image replaces it while a chapter has one.
            var dragX by remember { mutableStateOf(0f) }
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(colors.surfaceVariant)
                    .pointerInput(chapters) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragX < -80.dp.toPx()) model.nextChapter() else if (dragX > 80.dp.toPx()) model.previousChapter()
                                dragX = 0f
                            },
                            onDragCancel = { dragX = 0f },
                        ) { _, dx -> dragX += dx }
                    },
            ) {
                AsyncImage(
                    model = chapters.imageAt(position) ?: episode.imageUrl ?: feed.podcast.imageUrl,
                    contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
                if (current != null) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.72f)).padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChapterButton(HoppIcons.ChevronLeft, stringResource(Res.string.np_previous_chapter), model::previousChapter)
                        Column(Modifier.weight(1f).clickable { showChapters = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(current.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(Res.string.np_chapter_of, chapters.currentIndex(position) + 1, chapters.stops.size, formatTime(chapters.remainingInChapterMs(position, duration))),
                                color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                            )
                        }
                        ChapterButton(HoppIcons.ChevronRight, stringResource(Res.string.np_next_chapter), model::nextChapter)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(episode.title ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                (listOf(feed.podcast.title) + episode.persons.map { it.name }).filterNotNull().distinct().joinToString(" · "),
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(10.dp))
            ChapterProgressBar(
                fraction = if (duration > 0) position.toFloat() / duration else 0f,
                boundaries = chapters.boundaryFractions(duration),
                onSeek = { f -> model.engine.seekTo((f * duration).toLong()) },
                trackColor = colors.surfaceVariant, fillColor = colors.onSurface, gapColor = colors.background,
                label = stringResource(Res.string.np_progress),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text("−" + formatTime((duration - position).coerceAtLeast(0)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }

            // Controls: speed, −15 (hold: previous chapter), play, +30 (hold: next chapter), sleep.
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatSpeed(state.speed)}×",
                    Modifier.clip(RoundedCornerShape(50)).clickable { model.cycleSpeed() }.padding(12.dp)
                        .semantics { contentDescription = speedLabel },
                    fontWeight = FontWeight.Bold,
                )
                SkipButton(HoppIcons.Replay, "15", stringResource(Res.string.np_back15), { model.skipBy(-15_000) }, model::previousChapter)
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(colors.primary).clickable { model.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) HoppIcons.Pause else HoppIcons.Play,
                        stringResource(if (state.isPlaying) Res.string.np_pause else Res.string.np_play),
                        tint = colors.onPrimary, modifier = Modifier.size(32.dp),
                    )
                }
                SkipButton(HoppIcons.Forward, "30", stringResource(Res.string.np_forward30), { model.skipBy(30_000) }, model::nextChapter)
                Text("☾", Modifier.padding(12.dp), color = colors.onSurfaceVariant)
            }

            // Value bar (ADR 0019) — not wired to a wallet in this spike.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(20.dp)).padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Hopp.colors.value)
                        .clickable { scope.launch { snackbar.showSnackbar(valueLater) } }.padding(horizontal = 15.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HoppIcons.Hop, null, tint = Hopp.colors.onValue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(Res.string.np_boost), color = Hopp.colors.onValue, fontWeight = FontWeight.Bold)
                }
                Text(stringResource(Res.string.np_streaming, 10), Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = false, onCheckedChange = { scope.launch { snackbar.showSnackbar(valueLater) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Hopp.colors.value, checkedThumbColor = Hopp.colors.onValue),
                )
            }

            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetChip(stringResource(Res.string.sheet_chapters), enabled = chapters.stops.isNotEmpty()) { showChapters = true }
                SheetChip(stringResource(Res.string.sheet_transcript), enabled = false) {}
                SheetChip(stringResource(Res.string.sheet_people), enabled = false) {}
                SheetChip(stringResource(Res.string.sheet_community), enabled = false) {}
            }
            state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                itemsIndexed(chapters.stops) { i, ch ->
                    val active = i == chapters.currentIndex(position)
                    Row(
                        Modifier.fillMaxWidth().clickable { model.engine.seekTo((ch.startSeconds * 1000).toLong()); showChapters = false }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(formatTime((ch.startSeconds * 1000).toLong()), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Text(ch.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, seconds: String, label: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = label),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(seconds, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SheetChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun formatSpeed(s: Float): String {
    val tenths = kotlin.math.round(s * 10).toInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"
}
