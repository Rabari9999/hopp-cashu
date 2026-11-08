package space.sovereigncast.hopp.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.ListeningRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.QueueRepository
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.Show
import space.sovereigncast.hopp.feed.ChapterIndex
import space.sovereigncast.hopp.model.Person
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PlaybackItem
import space.sovereigncast.hopp.platform.PlaybackState
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import space.sovereigncast.hopp.value.ValueService

/** What is loaded in the player, with what the now-playing screen shows. */
data class NowPlaying(
    val item: EpisodeItem,
    val podcastTitle: String,
    val artworkUrl: String?,
    val persons: List<Person> = emptyList(),
    val chapters: ChapterIndex = ChapterIndex(emptyList()),
    /** The show's speed, or the default: what the player says while the episode is shown and not
     *  loaded yet (after a restart) — the engine knows no speed then and said 1×. */
    val speed: Float? = null,
)

/**
 * A clip from a Sovereigncast instance — the halving clip of a Release Boosting
 * round, a Boost2Unlock clip (phase 2) — or a show's trailer ([trailer]). Not an
 * episode of the feed: nothing is saved, marked played or streamed for it.
 */
data class RemoteClip(val url: String, val title: String, val showTitle: String, val artworkUrl: String?, val trailer: Boolean = false)

/** The sleep timer (ADR 0012): minutes, end of episode, end of chapter. */
sealed interface SleepTimer {
    data object Off : SleepTimer
    /** Counts down while playing only — paused time is not listening time. */
    data class Minutes(val remainingMs: Long) : SleepTimer
    data object EndOfEpisode : SleepTimer
    data object EndOfChapter : SleepTimer
}

/** The start or the end of an episode skipped as its show asks ([Show.skipIntroSeconds]). */
data class Skipped(val itemId: Long, val seconds: Int, val atStart: Boolean)

private val monotonic = TimeSource.Monotonic.markNow()

/**
 * The one player of the app, above the platform engine: what is loaded,
 * whether now playing is open, positions saved to the database, an episode
 * marked played when it ends and the next one taken from the queue, the sleep
 * timer, the show's own speed. Chapter navigation goes through [ChapterIndex].
 */
class PlayerController(
    val engine: PlaybackEngine,
    private val episodes: EpisodeRepository,
    private val podcasts: PodcastRepository,
    private val queue: QueueRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    /** Streaming sats (ADR 0014); null in tests that do not pay. */
    private val value: ValueService? = null,
    /** How much listening one streaming batch covers; shorter in tests. */
    private val settleEvery: kotlin.time.Duration = SETTLE_EVERY,
    /** The listening statistics; null in tests that do not count. */
    private val listening: ListeningRepository? = null,
    private val nowMs: () -> Long = { monotonic.elapsedNow().inWholeMilliseconds },
) {
    private val _current = MutableStateFlow<NowPlaying?>(null)
    val current: StateFlow<NowPlaying?> = _current.asStateFlow()
    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()
    private val _sleep = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleep: StateFlow<SleepTimer> = _sleep.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    /** The engine's state — or, for an episode shown but not loaded yet (restored after a restart),
     *  where it was left, so the player does not say 0:00. */
    val state: StateFlow<PlaybackState> = kotlinx.coroutines.flow.combine(engine.state, _current) { s, c ->
        val item = c?.item
        if (item == null || s.itemId != null) s
        else PlaybackState(positionMs = item.positionMs, durationMs = (item.durationSeconds ?: 0) * 1000L, speed = c.speed ?: s.speed)
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, engine.state.value)

    private var lastSaved = 0L
    /** Set while this controller loads an episode: until the engine reports
     *  it, the engine's old item is not a switch made by someone else. */
    private var expectedId: Long? = null
    private var lastTickMs: Long? = null
    private var lastChapter = -1
    private var lastPosition = 0L
    private var heardId: Long? = null
    private var heardAt = 0L
    /** When [hear] last ran, on the clock: listening time is the clock's, not the episode's. */
    private var heardClockAt = 0L
    /** A clip being played (podcast:soundbite): it pauses at [Clip.endMs] and
     *  goes back to where the listener was. */
    private class Clip(val itemId: Long, val startMs: Long, val endMs: Long, val returnTo: Long) { var reached = false }
    private var clip: Clip? = null
    private val _remoteClip = MutableStateFlow<RemoteClip?>(null)
    /** The instance's clip playing instead of the episode, until it ends or is closed. */
    val remoteClip: StateFlow<RemoteClip?> = _remoteClip.asStateFlow()
    /** The episode to go back to after a [RemoteClip], at the position it was left at. */
    private var afterRemoteClip: EpisodeItem? = null
    private var remoteClipWasPlaying = false
    /** Until the engine reports the clip, its old item is not someone else's. */
    private var remoteClipLoading = false
    /** The show of the episode loaded, as it is now: its speed and what it skips. */
    private var show: Show? = null
    private var showWatch: kotlinx.coroutines.Job? = null
    /** The speed this player last gave the engine. A show's speed saved later is taken over only when the
     *  player was at the one saved before — a change made elsewhere (the show's page, sync); its own taps
     *  arrive back from the database late, and one from before the last tap set the engine back (review
     *  2026-10-31: CI saw 1.1, 1.1, 1.1 … for ten taps). */
    private var wantedSpeed = 1f
    private fun speedTo(speed: Float) { wantedSpeed = speed; engine.setSpeed(speed) }
    /** A show's speed to save, the newest only, one write at a time: each tap launched its own, and the
     *  one that came last was not always the last tap's (review 2026-10-31). */
    private val speedSaves = kotlinx.coroutines.channels.Channel<Pair<Long, Float?>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val _skipped = kotlinx.coroutines.flow.MutableSharedFlow<Skipped>(extraBufferCapacity = 4)
    /** A start or end skipped (maintainer 2026-10-26): the player says so, with a way back. */
    val skipped: kotlinx.coroutines.flow.SharedFlow<Skipped> = _skipped

    init {
        scope.launch { for ((podcastId, speed) in speedSaves) podcasts.setSpeed(podcastId, speed) }
        scope.launch {
            settingsRepository.settings.collect {
                val before = _settings.value.defaultSpeed
                _settings.value = it
                engine.setSkipIntervals(it.skipBackSeconds, it.skipForwardSeconds)
                // A new default speed counts at once for the show playing, unless it has its own.
                val podcastId = _current.value?.item?.podcastId
                if (it.defaultSpeed != before && podcastId != null && podcasts.show(podcastId).first()?.playbackSpeed == null) {
                    speedTo(it.defaultSpeed)
                }
            }
        }
        // After a restart (device feedback 2026-10-22: a reboot left the player empty): the episode
        // last listened to, paused where it was — unless something is playing already.
        scope.launch {
            val last = episodes.inProgress.first().firstOrNull() ?: return@launch
            if (_current.value == null && engine.state.value.itemId == null) open(last, load = false, autoplay = false)
        }
        // What is still owed from before — offline, or the app closed mid-batch —
        // is caught up soon after the start; from then on batches follow listening.
        if (value != null) scope.launch { delay(CATCH_UP_AFTER); runCatching { value.settle() } }
        scope.launch {
            var wasPlaying = false
            engine.state.collect { s ->
                if (s.itemId == REMOTE_CLIP_ID) {
                    remoteClipLoading = false
                    val over = !s.isPlaying && s.durationMs > 0 && s.positionMs >= s.durationMs - 1_500
                    if (over && remoteClipWasPlaying) closeRemoteClip()
                    remoteClipWasPlaying = s.isPlaying
                    return@collect
                }
                // Something else is playing now: the clip is over, and nothing to go back to.
                if (_remoteClip.value != null && !remoteClipLoading && s.itemId != null) { _remoteClip.value = null; afterRemoteClip = null }
                val id = s.itemId?.toLongOrNull() ?: return@collect
                if (expectedId == id) expectedId = null
                // Started elsewhere — Android Auto, a resumed notification, or
                // the service still playing from before the UI was recreated.
                if (expectedId == null && _current.value?.item?.id != id) follow(id)
                tickSleep(s)
                tickClip(id, s)
                checkIntro(id, s)
                // Before hear(): whether listening, not a jump, crossed into the show's skipped end.
                val outro = outroReached(id, s)
                hear(id, s)
                if (s.isPlaying) startBatch()
                // Started here, however it was — a tap, the lock screen, Android Auto: what plays now, everywhere.
                if (s.isPlaying && !wasPlaying && clip?.itemId != id) scope.launch { runCatching { queue.noteNow(id) } }
                val ended = !s.isPlaying && s.durationMs > 0 && s.positionMs >= s.durationMs - 1_500
                when {
                    (ended && wasPlaying) || outro != null -> {
                        // The end skipped: as if heard to it — paused here, the next one from the queue.
                        if (outro != null) {
                            engine.pause(); _skipped.tryEmit(Skipped(id, outro, atStart = false))
                            _current.value?.item?.takeIf { it.id == id }?.let { item -> listening?.let { l -> scope.launch { l.skipped(item, outro * 1000L) } } }
                        }
                        finishedHere = id
                        episodes.setPlayed(id, true); lastSaved = 0
                        // Heard to its end: its streamed parts of a sat go out now, rounded up.
                        value?.let { v -> scope.launch { delay(ROUND_UP_DELAY); runCatching { v.settle(roundUp = setOf(id)) } } }
                        onEnded()
                    }
                    (s.isPlaying && abs(s.positionMs - lastSaved) >= 5_000) || (wasPlaying && !s.isPlaying) -> {
                        episodes.savePosition(id, s.positionMs, paused = !s.isPlaying); lastSaved = s.positionMs
                    }
                }
                // Paused or over: what was counted goes into the statistics, and to sync before long.
                if (wasPlaying && (!s.isPlaying || outro != null)) listening?.let { l -> scope.launch { runCatching { l.flush(note = true) } } }
                // After a skipped end the pause that follows is not one to save over "played".
                wasPlaying = s.isPlaying && outro == null
            }
        }
    }

    private var batch: kotlinx.coroutines.Job? = null

    /** An intro skipped in an episode of unknown length, until its length is known. */
    private var introUnchecked: Skipped? = null

    /** A trailer of three minutes with an intro of ten skipped began at its end and sat there: once
     *  the length is known, an episode too short for the skip goes back to its start. */
    private fun checkIntro(id: Long, s: PlaybackState) {
        val skipped = introUnchecked?.takeIf { it.itemId == id } ?: return
        if (s.durationMs <= 0) return
        introUnchecked = null
        if (s.durationMs < skipped.seconds * 1000L + MIN_LEFT_MS) engine.seekTo(0)
    }

    /**
     * Streaming is paid every [settleEvery] of listening, counted from when
     * listening starts (device feedback 2026-10-11) — not at whatever point an
     * app-wide timer had reached. After a pause the batch already running
     * still goes out; then the timer rests until listening resumes.
     */
    private fun startBatch() {
        val value = value ?: return
        if (batch?.isActive == true) return
        batch = scope.launch {
            do {
                value.settleDueIn(settleEvery.inWholeSeconds)
                delay(settleEvery)
                runCatching { value.settle() }
            } while (engine.state.value.isPlaying)
            value.settleDueIn(null)
        }
    }

    /**
     * The seconds of the show's end skipped, when listening has just crossed into them — not a
     * jump there, nor an episode too short for its show's skips ([longEnough]); else null.
     */
    private fun outroReached(id: Long, s: PlaybackState): Int? {
        val show = show ?: return null
        val item = _current.value?.item?.takeIf { it.id == id && it.podcastId == show.id } ?: return null
        if (show.skipOutroSeconds <= 0 || !s.isPlaying || clip != null || s.durationMs <= 0 || !longEnough(show, s.durationMs)) return null
        val at = s.durationMs - show.skipOutroSeconds * 1000L
        val delta = s.positionMs - heardAt
        val listened = heardId == item.id && delta > 0 && delta <= MAX_TICK_MS * s.speed.coerceAtLeast(1f)
        return show.skipOutroSeconds.takeIf { listened && heardAt < at && s.positionMs >= at }
    }

    /** Media time heard since the last report — a jump (seek, chapter) is
     *  not listening and accrues nothing (ADR 0014). */
    private fun hear(id: Long, s: PlaybackState) {
        val delta = s.positionMs - heardAt
        val heard = s.isPlaying && heardId == id && delta > 0 && delta <= MAX_TICK_MS * s.speed.coerceAtLeast(1f)
        val clock = nowMs()
        val clockDelta = (clock - heardClockAt).coerceIn(0, MAX_TICK_MS)
        heardClockAt = clock
        heardId = id
        heardAt = s.positionMs
        if (!heard) return
        val item = _current.value?.item?.takeIf { it.id == id } ?: return
        // The statistics: the clock's time, and the episode's own (speed and skipped silence beyond it).
        listening?.let { l -> scope.launch { runCatching { l.heard(item, clockDelta.coerceAtMost(delta), delta) } } }
        if (value == null) return
        scope.launch { runCatching { value.accrue(item, s.positionMs, delta) } }
    }

    private suspend fun follow(id: Long) {
        val item = episodes.item(id) ?: return
        queue.remove(id)
        open(item, load = false, autoplay = false)
    }

    /** Opens an episode. The one already loaded is only shown again — reloading
     *  would restart from the last saved position, seconds behind. */
    fun play(item: EpisodeItem, expand: Boolean = true) {
        val loaded = engine.state.value.itemId == item.id.toString()
        if (loaded && _current.value?.item?.id == item.id) {
            if (!engine.state.value.isPlaying) engine.play()
            _expanded.value = expand
            return
        }
        scope.launch { keepInterrupted(item.id); queue.remove(item.id); open(item, load = !loaded, autoplay = true) }
        _expanded.value = expand
    }

    /**
     * Another episode starts: the one playing until now, begun and not finished, goes back
     * to the top of "Up next" at its position — and plays on after the new one (device
     * feedback 2026-10-22: it was gone from the queue).
     */
    private suspend fun keepInterrupted(next: Long) {
        val now = _current.value?.item ?: return
        if (now.id == next || now.played || clip?.itemId == next) return
        val s = engine.state.value
        val loaded = s.itemId == now.id.toString()
        val pos = if (loaded) s.positionMs else now.positionMs
        val ended = loaded && s.durationMs > 0 && pos >= s.durationMs - 1_500
        if (pos < STARTED_MS || ended) return
        if (loaded) episodes.savePosition(now.id, pos, paused = true)
        queue.playNext(now.id)
    }

    /**
     * A clip of the episode (podcast:soundbite, device feedback 2026-10-09):
     * from its start to its end, then paused and back at the listener's own
     * position — a preview does not move where they are.
     */
    fun playClip(item: EpisodeItem, startMs: Long, durationMs: Long) {
        val loaded = engine.state.value.itemId == item.id.toString()
        val returnTo = if (loaded) engine.state.value.positionMs else item.positionMs
        clip = Clip(item.id, startMs, startMs + durationMs, returnTo)
        playAt(item, startMs)
    }

    /**
     * The instance's clip in Hopp's own player (phase 2; until then it opened in
     * the browser). The episode loaded before waits at its position and comes
     * back, paused, when the clip ends or is closed — as a soundbite does.
     */
    fun playRemoteClip(clip: RemoteClip) {
        val s = engine.state.value
        val item = _current.value?.item
        if (_remoteClip.value == null) afterRemoteClip = item?.let {
            if (s.itemId == it.id.toString()) it.copy(positionMs = s.positionMs, played = false) else it
        }
        if (item != null && s.itemId == item.id.toString()) scope.launch { episodes.savePosition(item.id, s.positionMs) }
        _remoteClip.value = clip
        remoteClipWasPlaying = false
        remoteClipLoading = true
        expectedId = null
        engine.load(PlaybackItem(REMOTE_CLIP_ID, clip.url, clip.title, clip.showTitle, clip.artworkUrl, 0), true)
    }

    /** Ends the clip: back to the episode from before, paused where it was. */
    fun closeRemoteClip() {
        if (_remoteClip.value == null) return
        _remoteClip.value = null
        val back = afterRemoteClip
        afterRemoteClip = null
        if (back != null) scope.launch { open(back, load = true, autoplay = false, skipIntro = false) } else engine.pause()
    }

    private fun tickClip(id: Long, s: PlaybackState) {
        val c = clip ?: return
        val inside = s.positionMs >= c.startMs - 2_000 && s.positionMs <= c.endMs + 5_000
        when {
            c.itemId != id -> if (c.reached) clip = null // another episode took over
            // Until the player has got there, a position elsewhere is the load, not the listener.
            !c.reached -> c.reached = inside && s.positionMs < c.endMs
            // Moved away by hand: no longer the clip.
            !inside -> clip = null
            s.positionMs >= c.endMs -> { clip = null; engine.pause(); engine.seekTo(c.returnTo) }
        }
    }

    /** From a time mark in the show notes or a chapter: there, playing — the show's skipped start does not apply. */
    fun playAt(item: EpisodeItem, positionMs: Long) {
        if (engine.state.value.itemId == item.id.toString() && _current.value?.item?.id == item.id) {
            engine.seekTo(positionMs); engine.play(); return
        }
        scope.launch { keepInterrupted(item.id); queue.remove(item.id); open(item.copy(positionMs = positionMs, played = false), load = true, autoplay = true, skipIntro = false) }
    }

    /** The start just skipped, heard after all: back to the beginning. */
    fun undoSkip(skipped: Skipped) {
        if (skipped.atStart && engine.state.value.itemId == skipped.itemId.toString()) engine.seekTo(0)
    }

    /** [skipIntro]: false where the listener chose the place — a time mark, a chapter, back from a clip. */
    private suspend fun open(item: EpisodeItem, load: Boolean, autoplay: Boolean, skipIntro: Boolean = true) {
        watchPlayed(item.id)
        val meta = podcasts.podcastMeta(item.podcastId)
        val show = podcasts.show(item.podcastId).first()
        watchShow(item.podcastId, show)
        val details = episodes.details(item.id)
        val artwork = item.imageUrl ?: meta?.imageUrl
        val speed = show?.playbackSpeed ?: settingsRepository.current().defaultSpeed
        _current.value = NowPlaying(item, meta?.title ?: item.podcastTitle ?: "", artwork, details?.persons.orEmpty().ifEmpty { meta?.persons.orEmpty() }, speed = speed)
        lastChapter = -1
        if (load) {
            val url = item.playableUrl ?: return
            var start = if (item.played) 0 else item.positionMs
            // The show's skipped start (maintainer 2026-10-26): only before it is over, never into a short episode.
            val intro = show?.skipIntroSeconds ?: 0
            if (skipIntro && intro > 0 && start < intro * 1000L && longEnough(show!!, (item.durationSeconds ?: 0) * 1000L)) {
                start = intro * 1000L
                _skipped.tryEmit(Skipped(item.id, intro, atStart = true))
                listening?.skipped(item, intro * 1000L)
                // Of unknown length: checked again once the engine knows it (review 2026-10-27).
                introUnchecked = if ((item.durationSeconds ?: 0) <= 0) Skipped(item.id, intro, atStart = true) else null
            } else introUnchecked = null
            expectedId = item.id
            engine.load(PlaybackItem(item.id.toString(), url, item.title, meta?.title ?: "", artwork, start), autoplay)
            // Each show keeps its speed (ADR 0012); one never set follows the default.
            speedTo(speed)
        }
        runCatching { episodes.chapters(item) }.getOrNull()?.let { list ->
            _current.update { if (it?.item?.id == item.id) it.copy(chapters = ChapterIndex(list)) else it }
        }
    }

    private var playedWatch: kotlinx.coroutines.Job? = null
    /** The episode this player heard to its end and marked played itself — it stays until the next one. */
    private var finishedHere: Long? = null

    /**
     * The episode in the player marked played — on its own, "this and older", a show's or every show's
     * at once, or by sync — leaves the player, and the player goes (maintainer 2026-11-01: it stayed).
     * "Up next" it has left already (EpisodeRepository.leaveQueue); what comes next is the listener's to start.
     */
    private fun watchPlayed(id: Long) {
        finishedHere = null
        playedWatch?.cancel()
        playedWatch = scope.launch {
            episodes.itemFlow(id).map { it?.played == true }.distinctUntilChanged().drop(1).collect { played ->
                if (played && finishedHere != id && _current.value?.item?.id == id) close()
            }
        }
    }

    /** Follows the show playing: a speed or a skip changed on its page counts at once. */
    private fun watchShow(podcastId: Long, now: Show?) {
        if (show?.id == podcastId && showWatch?.isActive == true) return
        show = now
        showWatch?.cancel()
        showWatch = scope.launch {
            podcasts.show(podcastId).collect { s ->
                val before = show
                show = s
                val loaded = _current.value?.item?.takeIf { it.podcastId == podcastId }?.id?.toString() == engine.state.value.itemId
                val beforeSpeed = before?.playbackSpeed ?: _settings.value.defaultSpeed
                if (s != null && before != null && before.playbackSpeed != s.playbackSpeed && loaded && abs(beforeSpeed - wantedSpeed) < 0.01f) {
                    speedTo(s.playbackSpeed ?: _settings.value.defaultSpeed)
                }
                if (s != null) _current.update { if (it?.item?.podcastId == podcastId) it.copy(speed = s.playbackSpeed ?: _settings.value.defaultSpeed) else it }
            }
        }
    }

    private suspend fun onEnded() {
        when (_sleep.value) {
            SleepTimer.EndOfEpisode, SleepTimer.EndOfChapter -> { _sleep.value = SleepTimer.Off; return }
            else -> advance()
        }
    }

    /** The next one from the queue, if there is one. */
    private suspend fun advance(): Boolean {
        val next = queue.pop()?.let { episodes.item(it) } ?: return false
        open(next, load = true, autoplay = true)
        return true
    }

    private fun tickSleep(s: PlaybackState) {
        when (val timer = _sleep.value) {
            is SleepTimer.Minutes -> {
                val now = nowMs()
                if (!s.isPlaying) { lastTickMs = null; return }
                val last = lastTickMs
                lastTickMs = now
                if (last == null) return
                val left = timer.remainingMs - (now - last)
                if (left <= 0) { engine.pause(); _sleep.value = SleepTimer.Off; lastTickMs = null }
                else _sleep.value = SleepTimer.Minutes(left)
            }
            SleepTimer.EndOfChapter -> {
                val chapters = _current.value?.chapters ?: return
                val index = chapters.currentIndex(s.positionMs)
                // Only a chapter reached by listening counts — not one jumped to.
                val reached = lastChapter >= 0 && index == lastChapter + 1 && s.positionMs - lastPosition in 0..3_000
                if (reached && s.isPlaying) {
                    engine.pause()
                    chapters.stops.getOrNull(index)?.let { engine.seekTo((it.startSeconds * 1000).toLong()) }
                    _sleep.value = SleepTimer.Off
                }
            }
            else -> Unit
        }
        _current.value?.chapters?.let { lastChapter = it.currentIndex(s.positionMs) }
        lastPosition = s.positionMs
    }

    fun setSleep(timer: SleepTimer) {
        lastTickMs = null
        _sleep.value = if (timer == SleepTimer.EndOfChapter && _current.value?.chapters?.stops.isNullOrEmpty()) SleepTimer.EndOfEpisode else timer
    }

    fun expand() { if (_current.value != null) _expanded.value = true }
    fun minimize() { _expanded.value = false }
    /** Loading counts as playing: a tap while a stream starts stops it, it does not ask again. */
    fun togglePlay() {
        val s = engine.state.value
        // Restored after a restart and not loaded yet: loaded now, from where it was.
        val shown = _current.value?.item
        if (shown != null && s.itemId != shown.id.toString() && _remoteClip.value == null) {
            // As the database has it now: a position from another device may have come since it was shown (delta audit 3 2026-10-22, ND-03).
            val expand = _expanded.value
            scope.launch { play(episodes.item(shown.id) ?: shown, expand) }
            return
        }
        if (s.isPlaying || s.isBuffering) engine.pause() else engine.play()
    }
    /** Whether episode [id] is playing here now — sync leaves its position alone until it pauses. */
    fun isPlaying(id: Long): Boolean = engine.state.value.let { it.isPlaying && it.itemId == id.toString() }

    /**
     * Progress from another device (sync, ADR 0021 5) for the episode loaded
     * here and paused — or only shown, after a restart (delta audit 3 2026-10-22,
     * ND-03: it played from the old place and saved that over the new one):
     * playing on starts where the listener stopped there, not where the player
     * still stands. One playing here is left alone.
     */
    fun adoptProgress(id: Long, positionMs: Long, played: Boolean) {
        val s = engine.state.value
        val loaded = s.itemId == id.toString()
        if (loaded && s.isPlaying) return
        val shown = _current.value?.takeIf { it.item.id == id }
        if (!loaded && shown == null) return
        // Heard to its end there (maintainer 2026-10-31: gone from "new", still in the player here): out of
        // the player too, and the player away. What comes next from "Up next" is the listener's to start.
        if (played) { close(); return }
        if (loaded) { engine.seekTo(positionMs); lastSaved = positionMs }
        shown?.let { _current.value = it.copy(item = it.item.copy(positionMs = positionMs, played = false)) }
    }

    /**
     * What plays now on another device (sync, maintainer 2026-11-06): shown here, paused where it is, and started
     * only by the listener. What plays here is never interrupted; it stays until it is done. The episode here until
     * now is in "Up next" if the other device had it; if not, it goes there, begun as it is.
     */
    suspend fun adoptNow(id: Long, atSeconds: Long) {
        val s = engine.state.value
        if (s.isPlaying || s.isBuffering || clip != null || _remoteClip.value != null || _current.value?.item?.id == id) return
        val item = episodes.item(id)?.takeUnless { it.played } ?: return
        episodes.noteListenedElsewhere(id, atSeconds)
        val before = _current.value?.item?.id
        if (before != null && !queue.has(before)) keepInterrupted(id)
        expectedId = null
        introUnchecked = null
        lastSaved = 0
        engine.unload()
        open(item, load = false, autoplay = false)
    }

    /** Nothing in the player: the episode unloaded, the bar and the screen gone. */
    private fun close() {
        playedWatch?.cancel()
        expectedId = null
        clip = null
        introUnchecked = null
        lastSaved = 0
        if (_sleep.value == SleepTimer.EndOfEpisode || _sleep.value == SleepTimer.EndOfChapter) _sleep.value = SleepTimer.Off
        engine.unload()
        _current.value = null
        _expanded.value = false
    }

    fun seekTo(ms: Long) {
        // Shown, not loaded (after a restart): loaded there, paused.
        val shown = _current.value?.item
        if (shown != null && engine.state.value.itemId == null && _remoteClip.value == null) {
            scope.launch { open(shown.copy(positionMs = ms.coerceAtLeast(0), played = false), load = true, autoplay = false, skipIntro = false) }
            return
        }
        engine.seekTo(ms.coerceAtLeast(0))
    }
    fun skipBy(deltaMs: Long) = seekTo(state.value.positionMs + deltaMs)
    fun skipBack() = skipBy(-_settings.value.skipBackSeconds * 1000L)
    fun skipForward() = skipBy(_settings.value.skipForwardSeconds * 1000L)
    fun nextChapter() { _current.value?.chapters?.nextStartMs(engine.state.value.positionMs)?.let { toChapter(it) } }
    fun previousChapter() { _current.value?.chapters?.previousStartMs(engine.state.value.positionMs)?.let { toChapter(it) } }

    /** A transcript opened to read along — counted for the statistics. */
    fun transcriptOpened() { listening?.let { l -> scope.launch { l.counted(transcripts = 1) } } }

    /** A chapter's start, gone to by the listener — counted for the statistics. */
    fun toChapter(startMs: Long) {
        engine.seekTo(startMs)
        listening?.let { l -> scope.launch { l.counted(chapters = 1) } }
    }

    /** "Next chapter" in the notification, the car and the widget: the next chapter, past the
     *  last one the next episode in the queue. (A headset's or steering wheel's "next" skips
     *  forward instead, maintainer 2026-10-27.) */
    fun next() {
        _current.value?.chapters?.nextStartMs(engine.state.value.positionMs)?.let { toChapter(it); return }
        scope.launch {
            engine.state.value.itemId?.toLongOrNull()?.let { episodes.savePosition(it, engine.state.value.positionMs) }
            advance()
        }
    }

    /** "Skip" on the playlist's "Now" (maintainer 2026-11-04): left where it is, its place kept — the next in
     *  the playlist plays. */
    fun skipEpisode() {
        scope.launch {
            engine.state.value.itemId?.toLongOrNull()?.let { episodes.savePosition(it, engine.state.value.positionMs) }
            advance()
        }
    }

    /** "Previous": the previous chapter, else the start of the episode. */
    fun previous() {
        _current.value?.chapters?.previousStartMs(engine.state.value.positionMs)?.let { toChapter(it); return }
        engine.seekTo(0)
    }

    /** The speed for the show playing; the default speed means: none of its own,
     *  so it follows the default when that changes. */
    fun setSpeed(speed: Float) {
        speedTo(speed)
        val own = speed.takeUnless { abs(it - _settings.value.defaultSpeed) < 0.01f }
        _current.value?.item?.podcastId?.let { speedSaves.trySend(it to own) }
    }

    /** The next faster of all [SPEED_CHOICES] — the ones the long press offers
     *  too (device feedback 2026-10-19) — and after the fastest the slowest. */
    fun cycleSpeed() {
        // Not loaded yet (after a restart): from the show's speed the player shows, not the engine's 1×.
        val e = engine.state.value
        val now = if (e.itemId == null) _current.value?.speed ?: e.speed else e.speed
        setSpeed(SPEED_CHOICES.firstOrNull { it > now + 0.01f } ?: SPEED_CHOICES.first())
    }

    companion object {
        /** From here on an episode counts as begun: one left earlier is not put back in "Up next". */
        const val STARTED_MS = 10_000L
        /** The engine's item id for a [RemoteClip] — not a number, so the
         *  episode bookkeeping above never takes it for one. */
        const val REMOTE_CLIP_ID = "sovereigncast-clip"

        /** Longer than any engine report gap while playing — a bigger jump is a seek. */
        const val MAX_TICK_MS = 5_000L
        val SETTLE_EVERY = 3.minutes
        val CATCH_UP_AFTER = 30.seconds
        /** After an episode's end, so its last ticks have accrued first. */
        val ROUND_UP_DELAY = 2.seconds
        val SPEED_CHOICES = listOf(0.8f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.8f, 2f, 2.5f, 3f)
        /** What must be left of an episode besides its show's skips: a trailer is not cut to nothing. */
        const val MIN_LEFT_MS = 120_000L

        /** Whether an episode [durationMs] long takes its show's skips; one of unknown length takes
         *  the start only — its end is known once it plays. */
        fun longEnough(show: Show, durationMs: Long): Boolean =
            durationMs <= 0 || durationMs >= (show.skipIntroSeconds + show.skipOutroSeconds) * 1000L + MIN_LEFT_MS
    }
}
