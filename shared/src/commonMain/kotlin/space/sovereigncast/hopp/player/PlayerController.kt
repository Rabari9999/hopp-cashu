package space.sovereigncast.hopp.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.QueueRepository
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.feed.ChapterIndex
import space.sovereigncast.hopp.model.Person
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PlaybackItem
import space.sovereigncast.hopp.platform.PlaybackState
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import space.sovereigncast.hopp.value.ValueService

/** What is loaded in the player, with what the now-playing screen shows. */
data class NowPlaying(
    val item: EpisodeItem,
    val podcastTitle: String,
    val artworkUrl: String?,
    val persons: List<Person> = emptyList(),
    val chapters: ChapterIndex = ChapterIndex(emptyList()),
)

/** The sleep timer (ADR 0012): minutes, end of episode, end of chapter. */
sealed interface SleepTimer {
    data object Off : SleepTimer
    /** Counts down while playing only — paused time is not listening time. */
    data class Minutes(val remainingMs: Long) : SleepTimer
    data object EndOfEpisode : SleepTimer
    data object EndOfChapter : SleepTimer
}

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
    settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    /** Streaming sats (ADR 0014); null in tests that do not pay. */
    private val value: ValueService? = null,
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
    val state: StateFlow<PlaybackState> get() = engine.state

    private var lastSaved = 0L
    /** Set while this controller loads an episode: until the engine reports
     *  it, the engine's old item is not a switch made by someone else. */
    private var expectedId: Long? = null
    private var lastTickMs: Long? = null
    private var lastChapter = -1
    private var lastPosition = 0L
    private var heardId: Long? = null
    private var heardAt = 0L
    /** A clip being played (podcast:soundbite): it pauses at [Clip.endMs] and
     *  goes back to where the listener was. */
    private class Clip(val itemId: Long, val startMs: Long, val endMs: Long, val returnTo: Long) { var reached = false }
    private var clip: Clip? = null

    init {
        scope.launch { settingsRepository.settings.collect { _settings.value = it } }
        // Streaming is paid in batches; offline debt is caught up here too.
        if (value != null) scope.launch {
            while (true) { value.settleDueIn(SETTLE_EVERY.inWholeSeconds); delay(SETTLE_EVERY); runCatching { value.settle() } }
        }
        scope.launch {
            var wasPlaying = false
            engine.state.collect { s ->
                val id = s.itemId?.toLongOrNull() ?: return@collect
                if (expectedId == id) expectedId = null
                // Started elsewhere — Android Auto, a resumed notification, or
                // the service still playing from before the UI was recreated.
                if (expectedId == null && _current.value?.item?.id != id) follow(id)
                tickSleep(s)
                tickClip(id, s)
                hear(id, s)
                val ended = !s.isPlaying && s.durationMs > 0 && s.positionMs >= s.durationMs - 1_500
                when {
                    ended && wasPlaying -> { episodes.setPlayed(id, true); lastSaved = 0; onEnded() }
                    (s.isPlaying && abs(s.positionMs - lastSaved) >= 5_000) || (wasPlaying && !s.isPlaying) -> {
                        episodes.savePosition(id, s.positionMs); lastSaved = s.positionMs
                    }
                }
                wasPlaying = s.isPlaying
            }
        }
    }

    /** Media time heard since the last report — a jump (seek, chapter) is
     *  not listening and accrues nothing (ADR 0014). */
    private fun hear(id: Long, s: PlaybackState) {
        val delta = s.positionMs - heardAt
        val heard = s.isPlaying && heardId == id && delta > 0 && delta <= MAX_TICK_MS * s.speed.coerceAtLeast(1f)
        heardId = id
        heardAt = s.positionMs
        if (!heard || value == null) return
        val item = _current.value?.item?.takeIf { it.id == id } ?: return
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
        scope.launch { queue.remove(item.id); open(item, load = !loaded, autoplay = true) }
        _expanded.value = expand
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

    /** From a time mark in the show notes or a chapter: there, playing. */
    fun playAt(item: EpisodeItem, positionMs: Long) {
        if (engine.state.value.itemId == item.id.toString() && _current.value?.item?.id == item.id) {
            engine.seekTo(positionMs); engine.play(); return
        }
        scope.launch { queue.remove(item.id); open(item.copy(positionMs = positionMs, played = false), load = true, autoplay = true) }
    }

    private suspend fun open(item: EpisodeItem, load: Boolean, autoplay: Boolean) {
        val meta = podcasts.podcastMeta(item.podcastId)
        val show = podcasts.show(item.podcastId).first()
        val details = episodes.details(item.id)
        val artwork = item.imageUrl ?: meta?.imageUrl
        _current.value = NowPlaying(item, meta?.title ?: item.podcastTitle ?: "", artwork, details?.persons.orEmpty().ifEmpty { meta?.persons.orEmpty() })
        lastChapter = -1
        if (load) {
            val url = item.playableUrl ?: return
            val start = if (item.played) 0 else item.positionMs
            expectedId = item.id
            engine.load(PlaybackItem(item.id.toString(), url, item.title, meta?.title ?: "", artwork, start), autoplay)
            // Each show keeps its speed (ADR 0012).
            engine.setSpeed(show?.playbackSpeed ?: 1f)
        }
        runCatching { episodes.chapters(item) }.getOrNull()?.let { list ->
            _current.update { if (it?.item?.id == item.id) it.copy(chapters = ChapterIndex(list)) else it }
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
    fun togglePlay() = engine.state.value.let { if (it.isPlaying) engine.pause() else engine.play() }
    fun seekTo(ms: Long) = engine.seekTo(ms.coerceAtLeast(0))
    fun skipBy(deltaMs: Long) = seekTo(engine.state.value.positionMs + deltaMs)
    fun skipBack() = skipBy(-_settings.value.skipBackSeconds * 1000L)
    fun skipForward() = skipBy(_settings.value.skipForwardSeconds * 1000L)
    fun nextChapter() { _current.value?.chapters?.nextStartMs(engine.state.value.positionMs)?.let(engine::seekTo) }
    fun previousChapter() { _current.value?.chapters?.previousStartMs(engine.state.value.positionMs)?.let(engine::seekTo) }

    /** Headset, notification and car "next": the next chapter, past the last
     *  one the next episode in the queue. */
    fun next() {
        _current.value?.chapters?.nextStartMs(engine.state.value.positionMs)?.let { engine.seekTo(it); return }
        scope.launch {
            engine.state.value.itemId?.toLongOrNull()?.let { episodes.savePosition(it, engine.state.value.positionMs) }
            advance()
        }
    }

    /** "Previous": the previous chapter, else the start of the episode. */
    fun previous() {
        _current.value?.chapters?.previousStartMs(engine.state.value.positionMs)?.let { engine.seekTo(it); return }
        engine.seekTo(0)
    }

    fun setSpeed(speed: Float) {
        engine.setSpeed(speed)
        _current.value?.item?.podcastId?.let { scope.launch { podcasts.setSpeed(it, speed) } }
    }

    fun cycleSpeed() {
        val i = SPEEDS.indexOfFirst { abs(it - engine.state.value.speed) < 0.01f }
        setSpeed(SPEEDS[(i + 1) % SPEEDS.size])
    }

    companion object {
        /** Longer than any engine report gap while playing — a bigger jump is a seek. */
        const val MAX_TICK_MS = 5_000L
        val SETTLE_EVERY = 5.minutes
        val SPEEDS = listOf(1f, 1.2f, 1.5f, 1.8f, 2f, 0.8f)
        val SPEED_CHOICES = listOf(0.8f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.8f, 2f, 2.5f, 3f)
    }
}
