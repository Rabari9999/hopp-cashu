package space.sovereigncast.hopp.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.feed.ChapterIndex
import space.sovereigncast.hopp.model.Person
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PlaybackItem
import space.sovereigncast.hopp.platform.PlaybackState
import kotlin.math.abs

/** What is loaded in the player, with what the now-playing screen shows. */
data class NowPlaying(
    val item: EpisodeItem,
    val podcastTitle: String,
    val artworkUrl: String?,
    val persons: List<Person> = emptyList(),
    val chapters: ChapterIndex = ChapterIndex(emptyList()),
)

/**
 * The one player of the app, above the platform engine: what is loaded,
 * whether now playing is open, positions saved to the database, an episode
 * marked played when it ends. Chapter navigation goes through [ChapterIndex].
 */
class PlayerController(
    val engine: PlaybackEngine,
    private val episodes: EpisodeRepository,
    private val podcasts: PodcastRepository,
    private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<NowPlaying?>(null)
    val current: StateFlow<NowPlaying?> = _current.asStateFlow()
    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()
    val state: StateFlow<PlaybackState> get() = engine.state

    private var lastSaved = 0L

    init {
        scope.launch { restore() }
        scope.launch {
            var wasPlaying = false
            engine.state.collect { s ->
                val id = s.itemId?.toLongOrNull() ?: return@collect
                val ended = !s.isPlaying && s.durationMs > 0 && s.positionMs >= s.durationMs - 1_500
                when {
                    ended && wasPlaying -> { episodes.setPlayed(id, true); lastSaved = 0 }
                    (s.isPlaying && abs(s.positionMs - lastSaved) >= 5_000) || (wasPlaying && !s.isPlaying) -> {
                        episodes.savePosition(id, s.positionMs); lastSaved = s.positionMs
                    }
                }
                wasPlaying = s.isPlaying
            }
        }
    }

    /** The service may still be playing from before the UI was recreated. */
    private suspend fun restore() {
        val id = engine.state.value.itemId?.toLongOrNull() ?: return
        val item = episodes.item(id) ?: return
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
        scope.launch { open(item, load = !loaded, autoplay = true) }
        _expanded.value = expand
    }

    private suspend fun open(item: EpisodeItem, load: Boolean, autoplay: Boolean) {
        val show = podcasts.podcastMeta(item.podcastId)
        val details = episodes.details(item.id)
        val artwork = item.imageUrl ?: show?.imageUrl
        _current.value = NowPlaying(item, show?.title ?: item.podcastTitle ?: "", artwork, details?.persons.orEmpty())
        if (load) {
            val url = item.enclosureUrl ?: return
            val start = if (item.played) 0 else item.positionMs
            engine.load(PlaybackItem(item.id.toString(), url, item.title, show?.title ?: "", artwork, start), autoplay)
        }
        runCatching { episodes.chapters(item) }.getOrNull()?.let { list ->
            _current.update { if (it?.item?.id == item.id) it.copy(chapters = ChapterIndex(list)) else it }
        }
    }

    fun expand() { if (_current.value != null) _expanded.value = true }
    fun minimize() { _expanded.value = false }
    fun togglePlay() = engine.state.value.let { if (it.isPlaying) engine.pause() else engine.play() }
    fun seekTo(ms: Long) = engine.seekTo(ms.coerceAtLeast(0))
    fun skipBy(deltaMs: Long) = seekTo(engine.state.value.positionMs + deltaMs)
    fun nextChapter() { _current.value?.chapters?.nextStartMs(engine.state.value.positionMs)?.let(engine::seekTo) }
    fun previousChapter() { _current.value?.chapters?.previousStartMs(engine.state.value.positionMs)?.let(engine::seekTo) }

    fun cycleSpeed() {
        val speeds = listOf(1f, 1.2f, 1.5f, 1.8f, 2f, 0.8f)
        val i = speeds.indexOfFirst { abs(it - engine.state.value.speed) < 0.01f }
        engine.setSpeed(speeds[(i + 1) % speeds.size])
    }
}
