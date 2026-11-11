package space.sovereigncast.hopp.ui.player

import space.sovereigncast.hopp.ui.components.onSecondaryClick
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.IconButton
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.player.SleepTimer
import space.sovereigncast.hopp.resources.skipped_back
import space.sovereigncast.hopp.resources.skipped_end
import space.sovereigncast.hopp.resources.skipped_start
import space.sovereigncast.hopp.resources.queue_empty_text
import space.sovereigncast.hopp.resources.queue_title
import space.sovereigncast.hopp.resources.skip_silence
import space.sovereigncast.hopp.resources.volume_boost
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
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
import space.sovereigncast.hopp.resources.np_effects_refused
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
import space.sovereigncast.hopp.ui.theme.playerGround

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
    SkipNotices(model, snackbar, expanded = true)
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

    // The show notes and the chapters as the sheets show them, and as tabs (UX review 2026-10-30).
    val notesContent: @Composable (Modifier) -> Unit = { m ->
        description?.let { html ->
            val linkStyle = androidx.compose.ui.text.SpanStyle(color = colors.onSurface, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = FontWeight.SemiBold)
            val markStyle = androidx.compose.ui.text.SpanStyle(color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
            // A time mark jumps there and stays: the listener may want the next one.
            val notes = remember(html, linkStyle, markStyle) { space.sovereigncast.hopp.ui.components.showNotes(html, linkStyle, markStyle) { s -> model.seekTo(s * 1000) } }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(notes, m.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    val chaptersContent: @Composable (Modifier, (Long) -> Unit) -> Unit = { m, onPicked ->
        val timeWidth = space.sovereigncast.hopp.ui.components.rememberChapterTimeWidth(chapters.stops)
        LazyColumn(m) {
            itemsIndexed(chapters.stops) { i, ch ->
                ChapterRow(ch, active = i == chapters.currentIndex(position), onSeek = {
                    model.toChapter((ch.startSeconds * 1000).toLong()); onPicked(ch.startSeconds.toLong())
                }, modifier = Modifier.padding(horizontal = 8.dp), withImages = chapters.stops.any { it.imageUrl != null }, timeWidth = timeWidth)
            }
        }
    }
    // Swipe down (or back) minimises to the mini player, as in every player app.
    // As a column beside the content (ADR 0020) back belongs to the content.
    BackHandler(enabled = !inPane) { model.minimize() }
    val dragY = remember { Animatable(0f) }
    // A boost that was paid, from the sheet or the lock screen: once from 0 to 1, for the ground's flare.
    val boostReports: space.sovereigncast.hopp.value.BoostReports = koinInject()
    val boosted = remember { Animatable(0f) }
    LaunchedEffect(boostReports) {
        boostReports.paid.collectLatest {
            // The sheet that sent it says so first, over the ground: the flare waits until the sheet is gone
            // and is seen whole (device feedback 2026-11-10).
            if (showBoost) { androidx.compose.runtime.snapshotFlow { showBoost }.first { !it }; kotlinx.coroutines.delay(SHEET_GONE) }
            boosted.snapTo(0f)
            boosted.animateTo(1f, androidx.compose.animation.core.tween(2400, easing = androidx.compose.animation.core.LinearEasing))
            boosted.snapTo(0f)
        }
    }
    // A comment of the listener's own that went out: once from 0 to 1, for what the ground makes of it.
    val writer: space.sovereigncast.hopp.comments.CommentWriter = koinInject()
    val sent = remember { Animatable(0f) }
    LaunchedEffect(writer) {
        writer.sent.collectLatest {
            if (showComments) { androidx.compose.runtime.snapshotFlow { showComments }.first { !it }; kotlinx.coroutines.delay(SHEET_GONE) }
            sent.snapTo(0f)
            sent.animateTo(1f, androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.LinearEasing))
            sent.snapTo(0f)
        }
    }
    val look = Hopp.colors.player
    val shapes = Hopp.colors.shapes
    // Figures of one width, where the design asks for them: the app's own font has them (no second font to load).
    val figures = if (look?.mono == true) "tnum" else null
    val dismissPx = with(LocalDensity.current) { 120.dp.toPx() }
    Box(
        Modifier.fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            // Over the window, the ground of its own; as a column beside the library, on the window's —
            // unless the design lights now playing itself: then the column has that light too.
            .then(
                if (inPane && Hopp.colors.player == null) Modifier.background(Hopp.colors.page)
                else Modifier.playerGround(state.isPlaying, moving = !space.sovereigncast.hopp.ui.LocalPlatformActions.current.reduceMotion, boost = { boosted.value }, sent = { sent.value }),
            )
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
        // The section shown as a tab where now playing has the height; the chapter's name on the cover opens
        // its tab there, not a sheet over the tabs (review 2026-10-31).
        var pickedSection by rememberSaveable(episode.id) { mutableStateOf(PlayerSection.Notes.name) }
        var sectionsAsTabs by remember { mutableStateOf(false) }
        val cover: @Composable (Modifier) -> Unit = { m ->
            // Cover — the chapter image replaces it while a chapter has one; whole, not cut
            // to the square, and full screen on a tap (device feedback 2026-10-17).
            val picture = chapters.imageAt(position) ?: playing.artworkUrl
            var viewing by remember { mutableStateOf(false) }
            val fullLabel = stringResource(Res.string.image_full)
            // Where the design lights now playing: an oval of light at the cover's foot, half of it behind the cover.
            val light = Hopp.colors.player?.coverLight
            Box(
                m.then(if (light == null) Modifier else Modifier.drawBehind {
                    val foot = Offset(size.width / 2, size.height - 4.dp.toPx())
                    scale(1f, 0.16f, foot) { drawCircle(Brush.radialGradient(listOf(light, Color.Transparent), foot, size.width * 0.52f), size.width * 0.52f, foot) }
                }).clip(shapes.cover).background(colors.surfaceVariant)
                    .then(look?.coverEdge?.let { Modifier.border(1.dp, it, shapes.cover) } ?: Modifier)
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
                // A small cover (beside the title, where tabs take the height): the chapter's name alone.
                if (current != null) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    val compact = maxWidth < 280.dp
                    Row(
                        Modifier.padding(if (compact) 6.dp else 10.dp).fillMaxWidth()
                            .clip(shapes.label).background(Color.Black.copy(alpha = 0.72f)).padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChapterButton(HoppIcons.ChevronLeft, stringResource(Res.string.np_previous_chapter), model::previousChapter)
                        Column(Modifier.weight(1f).clickable { if (sectionsAsTabs) pickedSection = PlayerSection.Chapters.name else showChapters = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(current.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!compact) Text(
                                stringResource(Res.string.np_chapter_of, chapters.currentIndex(position) + 1, chapters.stops.size, formatTime(chapters.remainingInChapterMs(position, duration))),
                                color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                            )
                        }
                        ChapterButton(HoppIcons.ChevronRight, stringResource(Res.string.np_next_chapter), model::nextChapter)
                    }
                }
            }

        }
        // What the chips open as sheets — or the tabs show, where now playing has the height (UX review 2026-10-30).
        val sections = buildList {
            if (description != null) add(PlayerSection.Notes)
            if (chapters.stops.isNotEmpty()) add(PlayerSection.Chapters)
            if (hasTranscript) add(PlayerSection.Transcript)
            if (playing.persons.isNotEmpty()) add(PlayerSection.People)
            if (hasCommunity) add(PlayerSection.Community)
            add(PlayerSection.Comments)
        }
        val openSection = { s: PlayerSection ->
            when (s) {
                PlayerSection.Notes -> showNotesSheet = true
                PlayerSection.Chapters -> showChapters = true
                PlayerSection.Transcript -> { showTranscript = true; model.transcriptOpened() }
                PlayerSection.People -> showPeople = true
                PlayerSection.Community -> showCommunity = true
                PlayerSection.Comments -> showComments = true
            }
        }
        val byline = (listOf(playing.podcastTitle) + playing.persons.map { it.name }).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        // [short]: a phone on its side — one line of title, and the show's name only in the
        // bar above, so the chips under the controls stay in view (device check 2026-10-20).
        // [titled]: false where the title stands beside the cover already; [chips]: false where
        // what they open is a tab below (UX review 2026-10-30); [wrap]: off a phone, the chips
        // take a second line instead of running off the edge, where a mouse could not reach them.
        val details: @Composable ColumnScope.(short: Boolean, titled: Boolean, chips: Boolean, wrap: Boolean) -> Unit = { short, titled, withChips, wrap ->
            if (titled) {
                Text(episode.title, style = MaterialTheme.typography.titleLarge, maxLines = if (short) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (!short) Text(byline, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(10.dp))
            // Where a drag would land, before the finger lets go (device feedback 2026-10-21).
            var dragTo by remember { mutableStateOf<Float?>(null) }
            ChapterProgressBar(
                fraction = if (duration > 0) position.toFloat() / duration else 0f,
                boundaries = chapters.boundaryFractions(duration),
                onSeek = { f -> model.seekTo((f * duration).toLong()) },
                trackColor = Hopp.colors.progressTrack, fillColor = Hopp.colors.progressFill ?: colors.onSurface, onFillColor = colors.background,
                label = stringResource(Res.string.np_progress),
                preview = { f ->
                    val at = (f * duration).toLong()
                    listOfNotNull(formatTime(at), chapters.current(at)?.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
                },
                onPreview = { dragTo = it },
                fillFrom = look?.fillFrom, heat = look?.heat, style = look?.progress ?: space.sovereigncast.hopp.ui.theme.ProgressStyle.Gaps,
                knobColor = look?.knob, notchColor = colors.onSurfaceVariant,
            )
            val shownAt = dragTo?.let { (it * duration).toLong() } ?: position
            val timeStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = if (dragTo != null) FontWeight.Bold else null, fontFeatureSettings = figures)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(shownAt), style = timeStyle, color = colors.onSurfaceVariant)
                Text("−" + formatTime((duration - shownAt).coerceAtLeast(0)), style = timeStyle, color = colors.onSurfaceVariant)
            }

            // Controls: speed, −15 (hold: previous chapter), play, +30 (hold: next chapter), sleep.
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // Tap: the next speed; hold: all speeds and skip silence.
                // The speed itself as its state: the description alone left VoiceOver
                // saying "Playback speed" without the value (device check 2026-10-20).
                val speedText = "${formatSpeed(state.speed)}×"
                Text(
                    speedText,
                    Modifier.clip(RoundedCornerShape(50))
                        .onSecondaryClick { showSpeed = true }
                        .combinedClickable(onClick = { model.cycleSpeed() }, onLongClick = { showSpeed = true })
                        .padding(12.dp).semantics { contentDescription = speedLabel; stateDescription = speedText },
                    fontWeight = FontWeight.Bold, style = androidx.compose.material3.LocalTextStyle.current.copy(fontFeatureSettings = figures),
                )
                val back = settings.skipBackSeconds
                val forward = settings.skipForwardSeconds
                SkipButton(HoppIcons.Replay, "$back", stringResource(Res.string.np_back15, back), model::skipBack, model::previousChapter)
                PlayButton(state, 72.dp, 32.dp) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.togglePlay() }
                SkipButton(HoppIcons.Forward, "$forward", stringResource(Res.string.np_forward30, forward), model::skipForward, model::nextChapter)
                SleepButton(sleep) { showSleep = true }
            }
            // No volume here: the browser's is in the bar along a wide window's foot alone (maintainer 2026-11-02).

            // Value bar (ADR 0019): boost and streaming — where Hopp pays at all.
            val episodeNav = space.sovereigncast.hopp.ui.library.LocalEpisodeNav.current
            if (space.sovereigncast.hopp.ui.LocalPlatformActions.current.canPay) ValueBar(valueVm, episode, onBoost = { showBoost = true }, onOpenWallet = onOpenWallet, onOpenEpisode = { model.minimize(); episodeNav.open(it) }, boost = { boosted.value })

            // One line that scrolls sideways: wrapped onto a second, the chips pushed
            // the latest boosts off a phone held upright (2026-10-16, with Show notes).
            // Only what this episode has (device feedback 2026-10-16) — greyed-out
            // placeholders crowded the line. Comments always: whether there are
            // any only the relays know, and they are asked only once opened.
            if (withChips) {
                Spacer(Modifier.height(12.dp))
                val chipList: @Composable () -> Unit = {
                    sections.forEach { s -> SheetChip(stringResource(s.label), overNostr = s == PlayerSection.Comments) { openSection(s) } }
                }
                if (wrap) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { chipList() }
                else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chipList() }
            }
            state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
        }
        // Below the controls where the height allows: what the chips open, as tabs that stay open
        // while listening — a column beside the content, a tablet upright (UX review 2026-10-30).
        val sectionTabs: @Composable (Modifier) -> Unit = { m ->
            val at = sections.indexOfFirst { it.name == pickedSection }.coerceAtLeast(0)
            val section = sections[at]
            Column(m) {
                // Chips that wrap rather than a tab row that scrolls: six did not fit a column
                // beside the content, and a mouse could not reach the last one.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sections.forEach { s ->
                        val nostr = Hopp.colors.nostr?.takeIf { s == PlayerSection.Comments }
                        FilterChip(
                            selected = s == section, onClick = { pickedSection = s.name }, label = { Text(stringResource(s.label), maxLines = 1) },
                            colors = nostr?.let { androidx.compose.material3.FilterChipDefaults.filterChipColors(containerColor = it.tint, labelColor = it.text, selectedContainerColor = it.fill, selectedLabelColor = it.onFill) }
                                ?: look?.chip?.let { androidx.compose.material3.FilterChipDefaults.filterChipColors(containerColor = it) }
                                ?: androidx.compose.material3.FilterChipDefaults.filterChipColors(),
                            shape = shapes.chip,
                            border = look?.chipEdge?.takeIf { nostr == null && s != section }?.let { androidx.compose.foundation.BorderStroke(1.dp, it) }
                                ?: androidx.compose.material3.FilterChipDefaults.filterChipBorder(enabled = true, selected = s == section),
                        )
                    }
                }
                val body = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)
                when (section) {
                    PlayerSection.Notes -> notesContent(body.padding(bottom = 16.dp))
                    PlayerSection.Chapters -> chaptersContent(body) { }
                    PlayerSection.Transcript -> {
                        LaunchedEffect(episode.id) { model.transcriptOpened() }
                        TranscriptView(episode, position, onSeek = model::seekTo, modifier = body)
                    }
                    PlayerSection.People -> PeopleList(playing.persons, playing.podcastTitle, body)
                    PlayerSection.Community -> CommunityContent(episode.podcastId, body)
                    PlayerSection.Comments -> {
                        val comments: space.sovereigncast.hopp.ui.comments.CommentsViewModel =
                            koinViewModel(key = "comments-${episode.id}") { org.koin.core.parameter.parametersOf(episode.id) }
                        space.sovereigncast.hopp.ui.comments.CommentsPane(
                            comments, onTimestamp = { s -> model.seekTo(s * 1000) },
                            modifier = body.imePadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                        )
                    }
                }
            }
        }
        // Large screens (ADR 0020): side by side wherever the window is wider than
        // tall — a tablet or a phone on its side, where a full-width square cover
        // pushed every control below the edge. Upright, the phone layout as it
        // was, at most a phone's width: no scrolling there, so swiping down still
        // closes the player.
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            val wide = maxWidth > maxHeight && maxWidth >= 480.dp
            val short = wide && maxHeight < 480.dp
            // Height enough for the sections as tabs below the controls (UX review 2026-10-30). 720, not 800: a
            // tablet's 800 dp on its side leave ~730–775 once its bars are drawn, and the tabs never came there
            // though the tests at 800 showed them (review 2026-10-31).
            val tabbed = !wide && maxHeight >= 720.dp && (inPane || maxWidth >= 600.dp)
            SideEffect { sectionsAsTabs = tabbed }
            val smallCover = if (maxHeight >= 1000.dp) 240.dp else 200.dp
            val coverSide = minOf(maxHeight - 64.dp, maxWidth * 0.45f)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = model::minimize) { Icon(HoppIcons.ChevronDown, stringResource(Res.string.np_close)) }
                    // Two lines at most: a long name ran on behind the cover.
                    Text(playing.podcastTitle, Modifier.weight(1f), textAlign = TextAlign.Center, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { sharing = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.Share, stringResource(Res.string.share_title)) }
                    IconButton(onClick = { showQueue = true }) { Icon(HoppIcons.Queue, stringResource(Res.string.queue_title)) }
                }

                if (wide) Row(
                    Modifier.fillMaxSize().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    cover(Modifier.size(coverSide))
                    Column(Modifier.weight(1f, fill = false).widthIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                        details(short, true, true, true)
                    }
                } else if (tabbed) Column(Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 720.dp)) {
                    // The cover beside the title, the controls below, the rest of the height for a tab.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        cover(Modifier.size(smallCover))
                        Column(Modifier.weight(1f)) {
                            Text(episode.title, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(byline, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    details(false, false, false, false)
                    sectionTabs(Modifier.weight(1f).fillMaxWidth())
                } else if (inPane) Column(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
                    // A column beside the content: the controls always in view, the
                    // cover as large as the height they leave allows.
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        cover(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true))
                    }
                    Spacer(Modifier.height(16.dp))
                    details(false, true, true, true)
                } else Column(Modifier.fillMaxWidth().fillMaxHeight().wrapContentWidth().widthIn(max = 560.dp)) {
                    // As wide as the screen where the height allows, smaller where it does not: in
                    // Safari on an iPhone 12 mini its bars leave so little that the value bar was
                    // squeezed unreadable under a full-width cover (maintainer 2026-10-29).
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        cover(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true))
                    }
                    Spacer(Modifier.height(16.dp))
                    details(false, true, true, false)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showBoost && space.sovereigncast.hopp.ui.LocalPlatformActions.current.canPay) {
        BoostSheet(valueVm, episode, playing.podcastTitle, position, onDismiss = { showBoost = false }, onOpenWallet = { showBoost = false; onOpenWallet() })
    }

    if (showTranscript) TranscriptSheet(episode, position, onSeek = model::seekTo) { showTranscript = false }
    if (showPeople) PeopleSheet(playing.persons, playing.podcastTitle) { showPeople = false }
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
            // The playlist (Dennis, maintainer 2026-11-03): this episode as "Now", then what comes after it.
            val now = space.sovereigncast.hopp.ui.library.playlistNow()
            val list = items.orEmpty().filter { it.id != now?.id }
            // "Removed · Undo" in the sheet itself: now playing's own would be under it.
            val undo = remember { SnackbarHostState() }
            Box(Modifier.heightIn(max = 520.dp)) {
                androidx.compose.runtime.CompositionLocalProvider(space.sovereigncast.hopp.ui.library.LocalSnackbar provides undo) {
                QueueList(
                    list, onPlay = { model.play(it); showQueue = false }, onReorder = queue::reorder, onOpen = null,
                    header = {
                        now?.let { n -> item(key = "now") { space.sovereigncast.hopp.ui.library.PlaylistNowCard(n, onOpen = { showQueue = false }, hasNext = list.isNotEmpty()) } }
                        if (list.isNotEmpty() && now != null) item(key = "after") { space.sovereigncast.hopp.ui.library.PlaylistAfterLabel() }
                        if (list.isEmpty()) item(key = "empty") {
                            Text(stringResource(Res.string.queue_empty_text), color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                        }
                    },
                )
                }
                SnackbarHost(undo, Modifier.align(Alignment.BottomCenter))
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
                if (model.engine.supportsSkipSilence || model.engine.supportsVolumeBoost) {
                    val settingsVm: SettingsViewModel = koinViewModel()
                    @Composable fun SwitchLine(label: String, on: Boolean, change: (Boolean) -> Unit) = Row(
                        Modifier.fillMaxWidth().toggleable(on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = change),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, Modifier.weight(1f))
                        Switch(checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
                    }
                    if (model.engine.supportsSkipSilence) SwitchLine(stringResource(Res.string.skip_silence), settings.skipSilence) { on -> settingsVm.update { it.copy(skipSilence = on) } }
                    // Quiet shows louder (maintainer 2026-10-21), beside skipping silence.
                    if (model.engine.supportsVolumeBoost) SwitchLine(stringResource(Res.string.volume_boost), settings.volumeBoost) { on -> settingsVm.update { it.copy(volumeBoost = on) } }
                    // The browser: on, and this show's server does not let the page work with its audio.
                    val refused by (model.engine.effectsRefused ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
                    if (refused && (settings.skipSilence || settings.volumeBoost)) Text(
                        stringResource(Res.string.np_effects_refused), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showNotesSheet && description != null) ModalBottomSheet(onDismissRequest = { showNotesSheet = false }) {
        notesContent(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp))
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            chaptersContent(Modifier.padding(bottom = 24.dp)) { showChapters = false }
        }
    }
}

@Composable
private fun ChapterButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(Hopp.colors.shapes.button).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
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
private fun SheetChip(label: String, overNostr: Boolean = false, onClick: () -> Unit) {
    val nostr = Hopp.colors.nostr?.takeIf { overNostr }
    Surface(
        color = nostr?.tint ?: Hopp.colors.player?.chip ?: MaterialTheme.colorScheme.surfaceVariant, shape = Hopp.colors.shapes.chip,
        border = Hopp.colors.player?.chipEdge?.takeIf { nostr == null }?.let { androidx.compose.foundation.BorderStroke(1.dp, it) },
        modifier = Modifier.clip(Hopp.colors.shapes.chip).clickable(onClick = onClick),
    ) {
        Text(
            label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = nostr?.text ?: MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** How long a closing sheet takes to be out of the way, before what it hid is shown. */
private const val SHEET_GONE = 250L

/** 1.5f → "1,5" — the speed as the listener reads it. */
/** 1.1 → "1.1", in German "1,1" (device check 2026-10-20: an English phone showed "1,1×"). */
/**
 * "Intro skipped (30 s) · Back" (maintainer 2026-10-26): where the listener is — in now playing
 * when it is open ([expanded]), else at the bottom of the app.
 */
@Composable
internal fun SkipNotices(player: PlayerController, snackbar: SnackbarHostState, expanded: Boolean) {
    androidx.compose.runtime.LaunchedEffect(player) {
        player.skipped.collect { s ->
            if (player.expanded.value != expanded) return@collect
            val text = org.jetbrains.compose.resources.getString(if (s.atStart) Res.string.skipped_start else Res.string.skipped_end, s.seconds)
            val back = if (s.atStart) org.jetbrains.compose.resources.getString(Res.string.skipped_back) else null
            val result = snackbar.showSnackbar(text, actionLabel = back, duration = androidx.compose.material3.SnackbarDuration.Short)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) player.undoSkip(s)
        }
    }
}

internal fun formatSpeed(s: Float): String =
    space.sovereigncast.hopp.ui.components.formatTenths(kotlin.math.round(s * 10).toLong())

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

/** What now playing opens below its controls — as chips and sheets, or as tabs where it has the height. */
internal enum class PlayerSection(val label: org.jetbrains.compose.resources.StringResource) {
    Notes(Res.string.episode_shownotes), Chapters(Res.string.sheet_chapters), Transcript(Res.string.sheet_transcript),
    People(Res.string.sheet_people), Community(Res.string.sheet_community), Comments(Res.string.sheet_comments),
}
