package space.sovereigncast.hopp.ui.player

import space.sovereigncast.hopp.ui.components.onSecondaryClick
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.IconButton
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.player.SleepTimer
import space.sovereigncast.hopp.resources.queue_empty_text
import space.sovereigncast.hopp.resources.queue_title
import space.sovereigncast.hopp.resources.skip_silence
import space.sovereigncast.hopp.resources.sleep_active_chapter
import space.sovereigncast.hopp.resources.sleep_active_episode
import space.sovereigncast.hopp.resources.sleep_end_chapter
import space.sovereigncast.hopp.resources.sleep_end_episode
import space.sovereigncast.hopp.resources.sleep_minutes
import space.sovereigncast.hopp.resources.sleep_off
import space.sovereigncast.hopp.resources.sleep_title
import space.sovereigncast.hopp.resources.speed_note
import space.sovereigncast.hopp.resources.speed_title
import space.sovereigncast.hopp.ui.library.QueueList
import space.sovereigncast.hopp.ui.library.QueueViewModel
import space.sovereigncast.hopp.ui.library.SettingsViewModel
import space.sovereigncast.hopp.ui.value.BoostSheet
import space.sovereigncast.hopp.ui.components.ChapterRow
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.sovereigncast.SovereigncastRepository
import org.koin.compose.koinInject
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.map
import space.sovereigncast.hopp.ui.value.ValueBar
import space.sovereigncast.hopp.ui.value.ValueBarViewModel
import kotlin.math.abs

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import space.sovereigncast.hopp.resources.share_title
import space.sovereigncast.hopp.resources.np_back15
import space.sovereigncast.hopp.resources.np_chapter_of
import space.sovereigncast.hopp.resources.np_close
import space.sovereigncast.hopp.resources.np_forward30
import space.sovereigncast.hopp.resources.np_next_chapter
import space.sovereigncast.hopp.resources.np_pause
import space.sovereigncast.hopp.resources.np_play
import space.sovereigncast.hopp.resources.np_previous_chapter
import space.sovereigncast.hopp.resources.np_progress
import space.sovereigncast.hopp.resources.np_speed
import space.sovereigncast.hopp.resources.sheet_chapters
import space.sovereigncast.hopp.resources.episode_shownotes
import space.sovereigncast.hopp.resources.sheet_community
import space.sovereigncast.hopp.resources.sheet_comments
import space.sovereigncast.hopp.resources.sheet_people
import space.sovereigncast.hopp.resources.sheet_transcript
import space.sovereigncast.hopp.ui.components.ChapterProgressBar
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.FittedImage
import space.sovereigncast.hopp.ui.components.ImageViewer
import space.sovereigncast.hopp.resources.image_full
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.onClick
import space.sovereigncast.hopp.ui.components.formatTime
import space.sovereigncast.hopp.ui.theme.Hopp

/** Now playing, laid out as in ADR 0019: cover with the chapter control,
 *  title, progress with chapter gaps, controls, value bar, sheets. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun NowPlayingScreen(model: PlayerController, inPane: Boolean = false, onOpenWallet: () -> Unit = {}) {
    val now by model.current.collectAsState()
    val playing = now ?: return
    val episode = playing.item
    val state by model.state.collectAsState()
    val chapters = playing.chapters
    val position = state.positionMs
    val duration = state.durationMs.takeIf { it > 0 } ?: ((episode.durationSeconds ?: 0) * 1000)
    val current = chapters.current(position)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showChapters by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    val sleep by model.sleep.collectAsState()
    val settings by model.settings.collectAsState()
    val valueVm: ValueBarViewModel = koinViewModel()
    val haptics = LocalHapticFeedback.current
    var showBoost by remember { mutableStateOf(false) }
    var showTranscript by remember { mutableStateOf(false) }
    var showPeople by remember { mutableStateOf(false) }
    var showCommunity by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    val episodeRepo: EpisodeRepository = koinInject()
    val hasTranscript by produceState(false, episode.id) { value = runCatching { episodeRepo.hasTranscript(episode.id) }.getOrDefault(false) }
    // The show notes without leaving the player (device feedback 2026-10-16): a sheet like the others.
    var showNotesSheet by remember { mutableStateOf(false) }
    val description by produceState<String?>(null, episode.id) { value = runCatching { episodeRepo.details(episode.id)?.description }.getOrNull()?.takeIf { it.isNotBlank() } }
    val sc: SovereigncastRepository = koinInject()
    val hasCommunity by remember(episode.podcastId) { sc.layer(episode.podcastId).map { it != null } }.collectAsState(false)
    val speedLabel = stringResource(Res.string.np_speed)
    val colors = MaterialTheme.colorScheme

    // Swipe down (or back) minimises to the mini player, as in every player app.
    // As a column beside the content (ADR 0020) back belongs to the content.
    BackHandler(enabled = !inPane) { model.minimize() }
    val dragY = remember { Animatable(0f) }
    val dismissPx = with(LocalDensity.current) { 120.dp.toPx() }
    Box(
        Modifier.fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            .background(colors.background)
            .safeDrawingPadding()
            .pointerInput(inPane) {
                if (inPane) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dragY.value > dismissPx) { model.minimize(); dragY.snapTo(0f) } else dragY.animateTo(0f)
                        }
                    },
                    onDragCancel = { scope.launch { dragY.animateTo(0f) } },
                ) { change, dy -> change.consume(); scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) } }
            },
    ) {
        // The cover's chapter swipe, kept while the layout switches.
        var dragX by remember { mutableStateOf(0f) }
        val cover: @Composable (Modifier) -> Unit = { m ->
            // Cover — the chapter image replaces it while a chapter has one; whole, not cut
            // to the square, and full screen on a tap (device feedback 2026-10-17).
            val picture = chapters.imageAt(position) ?: playing.artworkUrl
            var viewing by remember { mutableStateOf(false) }
            val fullLabel = stringResource(Res.string.image_full)
            Box(
                m.clip(RoundedCornerShape(24.dp)).background(colors.surfaceVariant)
                    .pointerInput(chapters) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragX < -80.dp.toPx()) model.nextChapter() else if (dragX > 80.dp.toPx()) model.previousChapter()
                                dragX = 0f
                            },
                            onDragCancel = { dragX = 0f },
                        ) { _, dx -> dragX += dx }
                    }
                    .pointerInput(picture) { detectTapGestures(onTap = { if (picture != null) viewing = true }) }
                    .semantics { onClick(fullLabel) { viewing = picture != null; true } },
            ) {
                FittedImage(picture, Modifier.fillMaxSize())
                if (viewing) ImageViewer(picture) { viewing = false }
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

        }
        val details: @Composable ColumnScope.() -> Unit = {
            Text(episode.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                (listOf(playing.podcastTitle) + playing.persons.map { it.name }).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(10.dp))
            ChapterProgressBar(
                fraction = if (duration > 0) position.toFloat() / duration else 0f,
                boundaries = chapters.boundaryFractions(duration),
                onSeek = { f -> model.seekTo((f * duration).toLong()) },
                trackColor = Hopp.colors.progressTrack, fillColor = colors.onSurface, gapColor = colors.background,
                label = stringResource(Res.string.np_progress),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text("−" + formatTime((duration - position).coerceAtLeast(0)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }

            // Controls: speed, −15 (hold: previous chapter), play, +30 (hold: next chapter), sleep.
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // Tap: the next speed; hold: all speeds and skip silence.
                Text(
                    "${formatSpeed(state.speed)}×",
                    Modifier.clip(RoundedCornerShape(50))
                        .onSecondaryClick { showSpeed = true }
                        .combinedClickable(onClick = { model.cycleSpeed() }, onLongClick = { showSpeed = true })
                        .padding(12.dp).semantics { contentDescription = speedLabel },
                    fontWeight = FontWeight.Bold,
                )
                val back = settings.skipBackSeconds
                val forward = settings.skipForwardSeconds
                SkipButton(HoppIcons.Replay, "$back", stringResource(Res.string.np_back15, back), model::skipBack, model::previousChapter)
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(colors.primary).clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.togglePlay()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    PlayPause(state, colors.onPrimary, 32.dp)
                }
                SkipButton(HoppIcons.Forward, "$forward", stringResource(Res.string.np_forward30, forward), model::skipForward, model::nextChapter)
                SleepButton(sleep) { showSleep = true }
            }

            // Value bar (ADR 0019): boost and streaming.
            val episodeNav = space.sovereigncast.hopp.ui.library.LocalEpisodeNav.current
            ValueBar(valueVm, episode, onBoost = { showBoost = true }, onOpenWallet = onOpenWallet, onOpenEpisode = { model.minimize(); episodeNav.open(it) })

            Spacer(Modifier.height(12.dp))
            // One line that scrolls sideways: wrapped onto a second, the chips pushed
            // the latest boosts off a phone held upright (2026-10-16, with Show notes).
            // Only what this episode has (device feedback 2026-10-16) — greyed-out
            // placeholders crowded the line. Comments always: whether there are
            // any only the relays know, and they are asked only once opened.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (description != null) SheetChip(stringResource(Res.string.episode_shownotes)) { showNotesSheet = true }
                if (chapters.stops.isNotEmpty()) SheetChip(stringResource(Res.string.sheet_chapters)) { showChapters = true }
                if (hasTranscript) SheetChip(stringResource(Res.string.sheet_transcript)) { showTranscript = true }
                if (playing.persons.isNotEmpty()) SheetChip(stringResource(Res.string.sheet_people)) { showPeople = true }
                if (hasCommunity) SheetChip(stringResource(Res.string.sheet_community)) { showCommunity = true }
                SheetChip(stringResource(Res.string.sheet_comments)) { showComments = true }
            }
            state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
        }
        // Large screens (ADR 0020): side by side wherever the window is wider than
        // tall — a tablet or a phone on its side, where a full-width square cover
        // pushed every control below the edge. Upright, the phone layout as it
        // was, at most a phone's width: no scrolling there, so swiping down still
        // closes the player.
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            val wide = maxWidth > maxHeight && maxWidth >= 480.dp
            val coverSide = minOf(maxHeight - 64.dp, maxWidth * 0.45f)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = model::minimize) { Icon(HoppIcons.ChevronDown, stringResource(Res.string.np_close)) }
                    Text(playing.podcastTitle, Modifier.weight(1f), textAlign = TextAlign.Center, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { sharing = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.Share, stringResource(Res.string.share_title)) }
                    IconButton(onClick = { showQueue = true }) { Icon(HoppIcons.Queue, stringResource(Res.string.queue_title)) }
                }

                if (wide) Row(
                    Modifier.fillMaxSize().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    cover(Modifier.size(coverSide))
                    Column(Modifier.weight(1f, fill = false).widthIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                        details()
                    }
                } else if (inPane) Column(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
                    // A column beside the content: the controls always in view, the
                    // cover as large as the height they leave allows.
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        cover(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true))
                    }
                    Spacer(Modifier.height(16.dp))
                    details()
                } else Column(Modifier.fillMaxWidth().fillMaxHeight().wrapContentWidth().widthIn(max = 560.dp)) {
                    cover(Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(16.dp))
                    details()
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showBoost) {
        BoostSheet(valueVm, episode, playing.podcastTitle, position, onDismiss = { showBoost = false }, onOpenWallet = { showBoost = false; onOpenWallet() })
    }

    if (showTranscript) TranscriptSheet(episode, position, onSeek = model::seekTo) { showTranscript = false }
    if (showPeople) PeopleSheet(playing.persons) { showPeople = false }
    if (showCommunity) CommunitySheet(episode.podcastId) { showCommunity = false }
    if (showComments) ModalBottomSheet(onDismissRequest = { showComments = false }) {
        val comments: space.sovereigncast.hopp.ui.comments.CommentsViewModel =
            koinViewModel(key = "comments-${episode.id}") { org.koin.core.parameter.parametersOf(episode.id) }
        Text(stringResource(Res.string.sheet_comments), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        space.sovereigncast.hopp.ui.comments.CommentsPane(
            comments, onTimestamp = { s -> model.seekTo(s * 1000) },
            // Above the keyboard: the list ends where it begins, so the last comment's answer stays in view.
            modifier = Modifier.imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        )
    }

    // The episode playing, "from here" a switch away (device feedback 2026-10-12).

    if (sharing) space.sovereigncast.hopp.ui.components.ShareSheet(space.sovereigncast.hopp.ui.components.ShareTarget.Episode(episode, position)) { sharing = false }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            val queue: QueueViewModel = koinViewModel()
            val items by queue.items.collectAsState()
            Text(stringResource(Res.string.queue_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val list = items.orEmpty()
            if (list.isEmpty()) {
                Text(stringResource(Res.string.queue_empty_text), color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
            } else {
                Box(Modifier.heightIn(max = 480.dp)) {
                    QueueList(list, onPlay = { model.play(it); showQueue = false }, onRemove = queue::remove, onReorder = queue::reorder)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSleep) {
        ModalBottomSheet(onDismissRequest = { showSleep = false }) {
            Text(stringResource(Res.string.sleep_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val choose = { t: SleepTimer -> model.setSleep(t); showSleep = false }
            SheetOption(stringResource(Res.string.sleep_off), sleep == SleepTimer.Off) { choose(SleepTimer.Off) }
            listOf(5, 15, 30, 45, 60).forEach { m ->
                SheetOption(stringResource(Res.string.sleep_minutes, m), false) { choose(SleepTimer.Minutes(m * 60_000L)) }
            }
            if (chapters.stops.isNotEmpty()) {
                SheetOption(stringResource(Res.string.sleep_end_chapter), sleep == SleepTimer.EndOfChapter) { choose(SleepTimer.EndOfChapter) }
            }
            SheetOption(stringResource(Res.string.sleep_end_episode), sleep == SleepTimer.EndOfEpisode) { choose(SleepTimer.EndOfEpisode) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSpeed) {
        ModalBottomSheet(onDismissRequest = { showSpeed = false }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.speed_title), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerController.SPEED_CHOICES.forEach { v ->
                        FilterChip(selected = abs(v - state.speed) < 0.01f, onClick = { model.setSpeed(v) }, label = { Text("${formatSpeed(v)}×") })
                    }
                }
                Text(stringResource(Res.string.speed_note, formatSpeed(settings.defaultSpeed)), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (model.engine.supportsSkipSilence) {
                    val settingsVm: SettingsViewModel = koinViewModel()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.skip_silence), Modifier.weight(1f))
                        Switch(checked = settings.skipSilence, onCheckedChange = { on -> settingsVm.update { it.copy(skipSilence = on) } })
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    description?.let { html ->
        if (showNotesSheet) ModalBottomSheet(onDismissRequest = { showNotesSheet = false }) {
            val linkStyle = androidx.compose.ui.text.SpanStyle(color = colors.onSurface, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = FontWeight.SemiBold)
            val markStyle = androidx.compose.ui.text.SpanStyle(color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
            // A time mark jumps there and stays: the listener may want the next one.
            val notes = remember(html, linkStyle, markStyle) { space.sovereigncast.hopp.ui.components.showNotes(html, linkStyle, markStyle) { s -> model.seekTo(s * 1000) } }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(notes, Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                itemsIndexed(chapters.stops) { i, ch ->
                    ChapterRow(ch, active = i == chapters.currentIndex(position), onSeek = {
                        model.seekTo((ch.startSeconds * 1000).toLong()); showChapters = false
                    }, modifier = Modifier.padding(horizontal = 8.dp), withImages = chapters.stops.any { it.imageUrl != null })
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
        Modifier.size(56.dp).clip(CircleShape).onSecondaryClick(onLongClick).combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = label),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(seconds, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SheetChip(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Text(
            label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 1.5f → "1,5" — the speed as the listener reads it. */
internal fun formatSpeed(s: Float): String {
    val tenths = kotlin.math.round(s * 10).toInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"
}

/** The moon, with what is left when the timer runs. */
@Composable
private fun SleepButton(timer: SleepTimer, onClick: () -> Unit) {
    val active = timer != SleepTimer.Off
    val label = stringResource(Res.string.sleep_title)
    Column(
        Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(HoppIcons.Moon, null, Modifier.size(22.dp), tint = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        when (timer) {
            is SleepTimer.Minutes -> Text(formatTime(timer.remainingMs), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            SleepTimer.EndOfChapter -> Text(stringResource(Res.string.sleep_active_chapter), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            SleepTimer.EndOfEpisode -> Text(stringResource(Res.string.sleep_active_episode), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            SleepTimer.Off -> Unit
        }
    }
}

@Composable
private fun SheetOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
}
