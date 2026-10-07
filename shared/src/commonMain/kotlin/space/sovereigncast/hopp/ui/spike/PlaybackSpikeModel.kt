package space.sovereigncast.hopp.ui.spike

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.feed.ChapterIndex
import space.sovereigncast.hopp.feed.ChaptersParser
import space.sovereigncast.hopp.feed.FeedParser
import space.sovereigncast.hopp.model.Episode
import space.sovereigncast.hopp.model.Feed
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PlaybackItem
import space.sovereigncast.hopp.platform.PositionStore

data class SpikeUi(
    val loading: Boolean = true,
    val error: String? = null,
    val feed: Feed? = null,
    val selected: Episode? = null,
    val chapters: ChapterIndex = ChapterIndex(emptyList()),
    val chaptersError: String? = null,
)

/**
 * Phase-0 playback spike (ADR 0018): the real Nodesignal feed, pick an
 * episode, play it with chapters. Throwaway glue — the real app gets view
 * models over the repositories of ADR 0013.
 */
class PlaybackSpikeModel(
    private val http: HttpClient,
    val engine: PlaybackEngine,
    private val positions: PositionStore,
    private val scope: CoroutineScope,
    private val feedUrl: String = NODESIGNAL_FEED,
) {
    private val _ui = MutableStateFlow(SpikeUi())
    val ui: StateFlow<SpikeUi> = _ui.asStateFlow()
    private var lastSaved = 0L

    init {
        loadFeed()
        // Save the position every few seconds while playing and on every pause.
        scope.launch {
            var wasPlaying = false
            engine.state.collect { s ->
                val id = s.itemId ?: return@collect
                if ((s.isPlaying && kotlin.math.abs(s.positionMs - lastSaved) >= 5_000) || (wasPlaying && !s.isPlaying)) {
                    positions.put(id, s.positionMs); lastSaved = s.positionMs
                }
                wasPlaying = s.isPlaying
            }
        }
    }

    fun loadFeed() {
        _ui.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val bytes: ByteArray = http.get(feedUrl).body()
                val feed = withContext(Dispatchers.Default) { FeedParser.parse(bytes) }
                _ui.update { it.copy(loading = false, feed = feed) }
                // The service may still be playing from before the activity was recreated.
                engine.state.value.itemId?.let { id -> feed.episodes.firstOrNull { it.guid == id }?.let { select(it, autoplay = false, reload = false) } }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: e::class.simpleName) }
            }
        }
    }

    fun select(episode: Episode, autoplay: Boolean = true, reload: Boolean = true) {
        val feed = _ui.value.feed ?: return
        _ui.update { it.copy(selected = episode, chapters = ChapterIndex(emptyList()), chaptersError = null) }
        if (reload) {
            val url = episode.enclosure?.url ?: return
            engine.load(
                PlaybackItem(
                    id = episode.guid, url = url, title = episode.title ?: "",
                    artist = feed.podcast.title ?: "", artworkUrl = episode.imageUrl ?: feed.podcast.imageUrl,
                    startPositionMs = positions.get(episode.guid),
                ),
                playWhenReady = autoplay,
            )
        }
        episode.chaptersUrl?.let { url ->
            scope.launch {
                try {
                    val chapters = withContext(Dispatchers.Default) { ChaptersParser.parse(http.get(url).bodyAsText()) }
                    _ui.update { if (it.selected?.guid == episode.guid) it.copy(chapters = ChapterIndex(chapters)) else it }
                } catch (e: Exception) {
                    _ui.update { it.copy(chaptersError = e.message) }
                }
            }
        }
    }

    fun close() = _ui.update { it.copy(selected = null) }

    fun togglePlay() = engine.state.value.let { if (it.isPlaying) engine.pause() else engine.play() }
    fun skipBy(deltaMs: Long) = engine.seekTo((engine.state.value.positionMs + deltaMs).coerceAtLeast(0))
    fun nextChapter() { _ui.value.chapters.nextStartMs(engine.state.value.positionMs)?.let(engine::seekTo) }
    fun previousChapter() { _ui.value.chapters.previousStartMs(engine.state.value.positionMs)?.let(engine::seekTo) }

    fun cycleSpeed() {
        val speeds = listOf(1f, 1.2f, 1.5f, 1.8f, 2f, 0.8f)
        val i = speeds.indexOfFirst { kotlin.math.abs(it - engine.state.value.speed) < 0.01f }
        engine.setSpeed(speeds[(i + 1) % speeds.size])
    }

    companion object {
        const val NODESIGNAL_FEED = "https://serve.podhome.fm/rss/e8df0b13-47de-544a-99b7-ec7cbd960a16"
    }
}
