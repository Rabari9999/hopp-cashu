package space.sovereigncast.hopp.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.directory.DirectoryRepository
import space.sovereigncast.hopp.directory.DirectoryShow
import space.sovereigncast.hopp.directory.SearchResult
import space.sovereigncast.hopp.directory.feedKey
import space.sovereigncast.hopp.feed.FeedParser
import space.sovereigncast.hopp.model.Feed
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.net.FetchResult
import space.sovereigncast.hopp.data.DownloadRepository
import space.sovereigncast.hopp.data.DownloadState
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.QueueRepository
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.Show
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.model.Podcast

private fun <T> ViewModel.state(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
    flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

/** Pull-to-refresh over all subscriptions, shared by New and Library. */
abstract class RefreshingViewModel(private val podcasts: PodcastRepository) : ViewModel() {
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch { try { podcasts.refreshAll() } finally { _refreshing.value = false } }
    }
}

class InboxViewModel(private val episodes: EpisodeRepository, podcasts: PodcastRepository) : RefreshingViewModel(podcasts) {
    val inbox: StateFlow<List<EpisodeItem>?> = state(episodes.inbox, null)
    val hasShows: StateFlow<Boolean?> = state(kotlinx.coroutines.flow.flow { podcasts.shows.collect { emit(it.isNotEmpty()) } }, null)
    fun dismiss(item: EpisodeItem) = viewModelScope.launch { episodes.dismiss(item.id) }
    fun clear() = viewModelScope.launch { episodes.clearInbox() }
}

class LibraryViewModel(
    podcasts: PodcastRepository,
    episodes: EpisodeRepository,
    queue: QueueRepository,
    downloads: DownloadRepository,
) : RefreshingViewModel(podcasts) {
    val shows: StateFlow<List<Show>?> = state(podcasts.shows, null)
    val inProgress: StateFlow<List<EpisodeItem>> = state(episodes.inProgress, emptyList())
    val queueCount: StateFlow<Int> = state(queue.items.map { it.size }, 0)
    val downloadCount: StateFlow<Int> = state(downloads.downloads.map { list -> list.count { it.downloadState == DownloadState.DONE } }, 0)
}

class QueueViewModel(private val queue: QueueRepository) : ViewModel() {
    val items: StateFlow<List<EpisodeItem>?> = state(queue.items, null)
    fun remove(item: EpisodeItem) = viewModelScope.launch { queue.remove(item.id) }
    fun reorder(ids: List<Long>) = viewModelScope.launch { queue.reorder(ids) }
}

class DownloadsViewModel(private val downloads: DownloadRepository) : ViewModel() {
    val items: StateFlow<List<EpisodeItem>?> = state(downloads.downloads, null)
    val usedBytes: StateFlow<Long> = state(downloads.usedBytes, 0L)
    val progress: StateFlow<Map<Long, Float>> = downloads.progress
    fun remove(item: EpisodeItem) = viewModelScope.launch { downloads.remove(item.id) }
}

/** What every episode row offers in its menu (ADR 0012). */
class EpisodeActionsViewModel(
    private val queue: QueueRepository,
    private val downloads: DownloadRepository,
    private val episodes: EpisodeRepository,
) : ViewModel() {
    val progress: StateFlow<Map<Long, Float>> = downloads.progress
    fun playNext(item: EpisodeItem) = viewModelScope.launch { queue.playNext(item.id) }
    fun playLast(item: EpisodeItem) = viewModelScope.launch { queue.playLast(item.id) }
    fun download(item: EpisodeItem) = viewModelScope.launch { downloads.request(item.id) }
    fun removeDownload(item: EpisodeItem) = viewModelScope.launch { downloads.remove(item.id) }
    fun setPlayed(item: EpisodeItem, played: Boolean) = viewModelScope.launch { episodes.setPlayed(item.id, played) }
}

class SettingsViewModel(private val settings: SettingsRepository, private val podcasts: PodcastRepository) : ViewModel() {
    val current: StateFlow<Settings?> = state(settings.settings, null)
    fun update(change: (Settings) -> Settings) = viewModelScope.launch { settings.update(change) }
    suspend fun exportOpml(): String = podcasts.exportOpml()
}

class ShowViewModel(
    private val podcastId: Long,
    private val podcasts: PodcastRepository,
    private val episodes: EpisodeRepository,
) : ViewModel() {
    val show: StateFlow<Show?> = state(podcasts.show(podcastId), null)
    /** Unsubscribed — here or anywhere else while this page waited in a stack. */
    val gone: StateFlow<Boolean> = state(podcasts.show(podcastId).map { it == null }, false)
    val episodeList: StateFlow<List<EpisodeItem>?> = state(episodes.ofPodcast(podcastId), null)
    private val _meta = MutableStateFlow<Podcast?>(null)
    val meta: StateFlow<Podcast?> = _meta.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { viewModelScope.launch { _meta.value = podcasts.podcastMeta(podcastId) } }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try { runCatching { podcasts.refresh(podcastId) }; _meta.value = podcasts.podcastMeta(podcastId) }
            finally { _refreshing.value = false }
        }
    }

    fun togglePlayed(item: EpisodeItem) = viewModelScope.launch { episodes.setPlayed(item.id, !item.played) }
    fun setAutoDownload(on: Boolean) = viewModelScope.launch { podcasts.setAutoDownload(podcastId, on) }
    fun unsubscribe() = viewModelScope.launch { podcasts.unsubscribe(podcastId) }
}

sealed interface AddState {
    data object Idle : AddState
    data object Working : AddState
    data class Imported(val subscribed: Int, val already: Int, val failed: Int) : AddState
}

sealed interface SearchUi {
    data object Idle : SearchUi
    data object Searching : SearchUi
    data class Done(val result: SearchResult) : SearchUi
}

/** A term, not an address: search. An address goes to the show page. */
internal fun searchable(q: String) = q.length >= 2 && "://" !in q && '/' !in q

/** Discover (ADR 0008): search as you type, the V4V filter, a feed address
 *  opened directly, the subscribed shows' recommendations, OPML import. */
@OptIn(FlowPreview::class)
class DiscoverViewModel(private val podcasts: PodcastRepository, private val directory: DirectoryRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _valueOnly = MutableStateFlow(false)
    val valueOnly: StateFlow<Boolean> = _valueOnly.asStateFlow()
    private val _search = MutableStateFlow<SearchUi>(SearchUi.Idle)
    val search: StateFlow<SearchUi> = _search.asStateFlow()
    private val _podroll = MutableStateFlow<List<DirectoryShow>>(emptyList())
    val podroll: StateFlow<List<DirectoryShow>> = _podroll.asStateFlow()
    private val _import = MutableStateFlow<AddState>(AddState.Idle)
    val import: StateFlow<AddState> = _import.asStateFlow()
    /** Feed keys of what is subscribed — results show it. */
    val subscribed: StateFlow<Set<String>> = state(podcasts.shows.map { list -> list.map { feedKey(it.feedUrl) }.toSet() }, emptySet())

    init {
        viewModelScope.launch {
            combine(_query, _valueOnly) { q, v -> q.trim() to v }
                .debounce(400)
                .distinctUntilChanged()
                .collectLatest { (q, v) ->
                    if (!searchable(q)) { _search.value = SearchUi.Idle; return@collectLatest }
                    _search.value = SearchUi.Searching
                    _search.value = SearchUi.Done(directory.search(q, v))
                }
        }
        viewModelScope.launch { _podroll.value = runCatching { directory.podroll() }.getOrDefault(emptyList()) }
    }

    fun setQuery(q: String) { _query.value = q }
    fun setValueOnly(on: Boolean) { _valueOnly.value = on }

    fun importOpml(text: String) {
        _import.value = AddState.Working
        viewModelScope.launch {
            val results = podcasts.importOpml(text).map { it.second }
            _import.value = AddState.Imported(
                subscribed = results.count { it is SubscribeResult.Subscribed },
                already = results.count { it is SubscribeResult.AlreadySubscribed },
                failed = results.count { it is SubscribeResult.Failed },
            )
        }
    }

    suspend fun exportOpml(): String = podcasts.exportOpml()
}

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Loaded(val feed: Feed, val feedUrl: String) : PreviewState
    data class Failed(val reason: String) : PreviewState
}

/**
 * The show page before subscribing (ADR 0008): the real feed, fetched
 * directly — the directory's copy can be old and knows nothing of P2.0 tags.
 */
class ShowPreviewViewModel(input: String, private val fetcher: FeedFetcher, private val podcasts: PodcastRepository) : ViewModel() {
    private val url = FeedFetcher.normalize(input) ?: input
    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val state: StateFlow<PreviewState> = _state.asStateFlow()
    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Already subscribed — under this address or the one it moved to. */
    val subscribedId: StateFlow<Long?> = state(
        combine(podcasts.shows, _state) { shows, st ->
            val keys = setOfNotNull(feedKey(url), (st as? PreviewState.Loaded)?.feedUrl?.let(::feedKey))
            shows.firstOrNull { feedKey(it.feedUrl) in keys }?.id
        },
        null,
    )

    init {
        viewModelScope.launch {
            _state.value = try {
                when (val r = fetcher.fetch(url)) {
                    is FetchResult.Fetched -> {
                        val feed = withContext(Dispatchers.Default) { FeedParser.parse(r.bytes) }
                        if (feed.podcast.title == null && feed.episodes.isEmpty()) PreviewState.Failed("not a podcast feed")
                        else PreviewState.Loaded(feed, r.movedTo ?: url)
                    }
                    else -> PreviewState.Failed("empty response")
                }
            } catch (e: Exception) {
                PreviewState.Failed(e.message ?: "network error")
            }
        }
    }

    fun subscribe(onDone: (Long) -> Unit) {
        if (_working.value) return
        _working.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = podcasts.subscribe((_state.value as? PreviewState.Loaded)?.feedUrl ?: url)) {
                is SubscribeResult.Subscribed -> onDone(r.podcastId)
                is SubscribeResult.AlreadySubscribed -> onDone(r.podcastId)
                is SubscribeResult.Failed -> _error.value = r.reason
            }
            _working.value = false
        }
    }
}
