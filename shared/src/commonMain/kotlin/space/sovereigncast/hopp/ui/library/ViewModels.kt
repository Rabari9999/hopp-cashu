package space.sovereigncast.hopp.ui.library

import kotlinx.coroutines.flow.mapLatest
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
        viewModelScope.launch { try { podcasts.refreshAll(); afterRefresh() } finally { _refreshing.value = false } }
    }
    /** Whatever else a pull to refresh should bring up to date. */
    protected open suspend fun afterRefresh() = Unit
}

class InboxViewModel(private val episodes: EpisodeRepository, podcasts: PodcastRepository) : RefreshingViewModel(podcasts) {
    val inbox: StateFlow<List<EpisodeItem>?> = state(episodes.inbox, null)
    val hasShows: StateFlow<Boolean?> = state(kotlinx.coroutines.flow.flow { podcasts.shows.collect { emit(it.isNotEmpty()) } }, null)
    fun dismiss(item: EpisodeItem) = viewModelScope.launch { episodes.dismiss(item.id) }
    fun clear() = viewModelScope.launch { episodes.clearInbox() }
}

/** Home (device feedback 2026-10-08): what is new, what to continue, what
 *  was heard and boosted last — the listener's own front page. */
class HomeViewModel(
    private val episodes: EpisodeRepository,
    private val podcasts: PodcastRepository,
    value: space.sovereigncast.hopp.value.ValueService,
    private val settings: SettingsRepository,
    private val sc: space.sovereigncast.hopp.sovereigncast.SovereigncastRepository,
) : RefreshingViewModel(podcasts) {
    private val _actions = MutableStateFlow<List<space.sovereigncast.hopp.sovereigncast.ShowAction>>(emptyList())
    /** What runs at the listener's Sovereigncast shows (device feedback 2026-10-10). */
    val actions: StateFlow<List<space.sovereigncast.hopp.sovereigncast.ShowAction>> = _actions.asStateFlow()

    init {
        // New subscriptions get checked against their instance; whenever the set
        // of verified shows changes — here or on a show page — the list follows.
        viewModelScope.launch { podcasts.shows.map { l -> l.map { it.id }.toSet() }.distinctUntilChanged().collectLatest { sc.verifyAll() } }
        viewModelScope.launch { sc.verifiedShows().distinctUntilChanged().collectLatest { loadActions() } }
    }

    private suspend fun loadActions() { runCatching { sc.activeActions() }.onSuccess { _actions.value = it } }
    override suspend fun afterRefresh() { sc.verifyAll(); loadActions() }

    /** Sections folded away — kept across starts (device feedback 2026-10-09). */
    val collapsed: StateFlow<Set<String>> = state(settings.settings.map { it.homeCollapsed }, emptySet())
    /** The listener's order of Home's sections (empty: Hopp's own) and what they hid. */
    val savedOrder: StateFlow<List<String>> = state(settings.settings.map { it.homeOrder }, emptyList())
    val hidden: StateFlow<Set<String>> = state(settings.settings.map { it.homeHidden }, emptySet())
    /** Who Home greets: the name the listener boosts with, if they gave one (device feedback
     *  2026-10-13). A Nostr profile's name is to take its place once Hopp signs in with Nostr
     *  (phase 2) — this is the one place to change. */
    val greetName: StateFlow<String?> = state(settings.settings.map { it.senderName.trim().takeIf { n -> n.isNotEmpty() } }, null)
    fun toggle(section: String) = viewModelScope.launch {
        settings.update { s -> s.copy(homeCollapsed = if (section in s.homeCollapsed) s.homeCollapsed - section else s.homeCollapsed + section) }
    }
    val inbox: StateFlow<List<EpisodeItem>?> = state(episodes.inbox, null)
    val hasShows: StateFlow<Boolean?> = state(podcasts.shows.map { it.isNotEmpty() }, null)
    val inProgress: StateFlow<List<EpisodeItem>> = state(episodes.inProgress.map { it.take(HOME_ROWS) }, emptyList())
    val favorites: StateFlow<List<EpisodeItem>> = state(episodes.favorites, emptyList())
    val recentlyPlayed: StateFlow<List<EpisodeItem>> = state(episodes.recentlyPlayed(HOME_ROWS.toLong()), emptyList())
    /** Boosted episodes that still exist, with the sats sent to each. */
    val recentlyBoosted: StateFlow<List<Pair<EpisodeItem, Long>>> = state(
        value.recentBoosts(HOME_ROWS.toLong()).map { list ->
            list.mapNotNull { (id, sats) ->
                episodes.item(id)?.let { e -> e.copy(podcastTitle = podcasts.show(e.podcastId).first()?.title) to sats }
            }
        },
        emptyList(),
    )
    fun dismiss(item: EpisodeItem) = viewModelScope.launch { episodes.dismiss(item.id) }

    // A quiet day on Home (device feedback 2026-10-12): the shows themselves,
    // when they come out next, and what of them is still unheard.
    val shows: StateFlow<List<space.sovereigncast.hopp.data.Show>> = state(podcasts.shows, emptyList())
    val rhythms: StateFlow<Map<Long, space.sovereigncast.hopp.data.Rhythm>> = state(episodes.rhythms, emptyMap())
    val lastChecked: StateFlow<Long?> = state(podcasts.lastChecked, null)

    companion object { const val HOME_ROWS = 5; const val TAKE_PART_ROWS = 2 }
}

class LibraryViewModel(
    podcasts: PodcastRepository,
    episodes: EpisodeRepository,
    queue: QueueRepository,
    downloads: DownloadRepository,
    private val settings: SettingsRepository,
) : RefreshingViewModel(podcasts) {
    val shows: StateFlow<List<Show>?> = state(podcasts.shows, null)
    val queueCount: StateFlow<Int> = state(queue.items.map { it.size }, 0)
    val downloadCount: StateFlow<Int> = state(downloads.downloads.map { list -> list.count { it.downloadState == DownloadState.DONE } }, 0)
    /** Episodes and new ones per show, for the cards and the list. */
    val counts: StateFlow<Map<Long, space.sovereigncast.hopp.data.ShowCounts>> = state(episodes.countsPerShow, emptyMap())
    /** Cards (the default) or a list — the listener's choice, kept in the settings. */
    val asList: StateFlow<Boolean> = state(settings.settings.map { it.libraryAsList }, false)
    fun showAsList(list: Boolean) = viewModelScope.launch { settings.update { it.copy(libraryAsList = list) } }
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
    fun dismiss(item: EpisodeItem) = viewModelScope.launch { episodes.dismiss(item.id) }
    fun setFavorite(item: EpisodeItem, on: Boolean) = viewModelScope.launch { episodes.setFavorite(item.id, on) }
}

/** All favourites, newest first (device feedback 2026-10-11). */
class FavoritesViewModel(episodes: EpisodeRepository) : ViewModel() {
    val items: StateFlow<List<EpisodeItem>?> = state(episodes.favorites, null)
}

/** An episode's own page (device feedback 2026-10-08, as in Pocket Casts):
 *  what it is about before it plays. */
class EpisodeViewModel(private val id: Long, private val episodes: EpisodeRepository, private val podcasts: PodcastRepository) : ViewModel() {
    val item: StateFlow<EpisodeItem?> = state(episodes.itemFlow(id), null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val show: StateFlow<Show?> = state(episodes.itemFlow(id).flatMapLatest { e -> e?.let { podcasts.show(it.podcastId) } ?: kotlinx.coroutines.flow.flowOf(null) }, null)
    private val _details = MutableStateFlow<space.sovereigncast.hopp.model.Episode?>(null)
    val details: StateFlow<space.sovereigncast.hopp.model.Episode?> = _details.asStateFlow()
    private val _chapters = MutableStateFlow<List<space.sovereigncast.hopp.feed.Chapter>>(emptyList())
    val chapters: StateFlow<List<space.sovereigncast.hopp.feed.Chapter>> = _chapters.asStateFlow()
    private val _chaptersLoaded = MutableStateFlow(false)
    /** The chapters have been fetched (or failed) — until then their tab waits. */
    val chaptersLoaded: StateFlow<Boolean> = _chaptersLoaded.asStateFlow()
    private val _gone = MutableStateFlow(false)
    /** Unsubscribed while this page was open. */
    val gone: StateFlow<Boolean> = _gone.asStateFlow()
    private val _showPersons = MutableStateFlow<List<space.sovereigncast.hopp.model.Person>>(emptyList())
    /** The show's people — they stand for an episode that names none. */
    val showPersons: StateFlow<List<space.sovereigncast.hopp.model.Person>> = _showPersons.asStateFlow()
    private val _hasValue = MutableStateFlow(false)
    /** The episode or its show names recipients — a boost can go out. */
    val hasValue: StateFlow<Boolean> = _hasValue.asStateFlow()

    init {
        viewModelScope.launch {
            val e = episodes.item(id) ?: run { _gone.value = true; return@launch }
            _details.value = episodes.details(id)
            val meta = runCatching { podcasts.podcastMeta(e.podcastId) }.getOrNull()
            _hasValue.value = _details.value?.value != null || meta?.value != null
            _showPersons.value = meta?.persons.orEmpty()
            _chapters.value = runCatching { episodes.chapters(e) }.getOrDefault(emptyList()).filter { it.inToc }
            _chaptersLoaded.value = true
        }
        viewModelScope.launch { episodes.itemFlow(id).collect { if (it == null && _details.value != null) _gone.value = true } }
    }
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val podcasts: PodcastRepository,
    wallet: space.sovereigncast.hopp.value.WalletRepository,
) : ViewModel() {
    val current: StateFlow<Settings?> = state(settings.settings, null)
    /** The connected wallet's name, for the settings overview; null = none. */
    val walletLabel: StateFlow<String?> = state(wallet.wallet.map { it?.label }, null)
    fun update(change: (Settings) -> Settings) = viewModelScope.launch { settings.update(change) }
    suspend fun exportOpml(): String = podcasts.exportOpml()
}

/**
 * What the privacy page can check for itself (device feedback 2026-10-11):
 * a check is only ever "done" when Hopp can see it is — an active VPN says
 * the IP is hidden from the servers, not whom the listener trusts with it.
 */
data class PrivacyState(val settings: Settings, val relay: String?, val net: space.sovereigncast.hopp.platform.NetworkPrivacy) {
    val vpn: Boolean? get() = net.vpn
    /** Through the VPN's tunnel the network sees no DNS either. */
    val dns: Boolean? get() = if (net.vpn == true) true else net.privateDns
    val directoriesOff: Boolean get() = !settings.fyydSearch && !settings.appleSearch
    val anonymous: Boolean get() = settings.senderName.isBlank()
    /** The checks there are on this phone, done or not. */
    val checks: List<Boolean> get() = listOfNotNull(vpn, dns, directoriesOff, anonymous)
}

class PrivacyViewModel(
    settings: SettingsRepository,
    wallet: space.sovereigncast.hopp.value.WalletRepository,
    network: space.sovereigncast.hopp.platform.NetworkPrivacySource,
) : ViewModel() {
    val state: StateFlow<PrivacyState?> = state(
        combine(settings.settings, wallet.wallet, network.observe()) { s, w, n -> PrivacyState(s, w?.relay?.substringAfter("://")?.substringBefore('/'), n) },
        null,
    )
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShowViewModel(
    private val podcastId: Long,
    private val podcasts: PodcastRepository,
    private val episodes: EpisodeRepository,
) : ViewModel() {
    val show: StateFlow<Show?> = state(podcasts.show(podcastId), null)
    /** Unsubscribed — here or anywhere else while this page waited in a stack. */
    val gone: StateFlow<Boolean> = state(podcasts.show(podcastId).map { it == null }, false)
    private val limit = MutableStateFlow(PAGE)
    /** The first [PAGE] episodes, [PAGE] more each time the list nears its end. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val episodeList: StateFlow<List<EpisodeItem>?> = state(limit.flatMapLatest { episodes.ofPodcastPage(podcastId, it.toLong()) }, null)
    val episodeCount: StateFlow<Long> = state(episodes.countOfPodcast(podcastId), 0L)

    /** The next page — when a full one is loaded and the list showing it
     *  ([seenItems], the search field included) has been seen at its end. */
    fun loadMore(seenItems: Int) {
        val loaded = episodeList.value?.size ?: 0
        if (loaded >= limit.value && seenItems > limit.value) limit.value += PAGE
    }
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

    private val _query = MutableStateFlow("")
    /** Searching the show's episodes (device feedback 2026-10-11). */
    val query: StateFlow<String> = _query.asStateFlow()
    private val _found = MutableStateFlow<List<EpisodeItem>?>(null)
    /** The matches; null while there is no search. */
    val found: StateFlow<List<EpisodeItem>?> = _found.asStateFlow()
    fun search(q: String) { _query.value = q }

    init {
        viewModelScope.launch {
            // Again whenever the episodes change while searching (a refresh, played, a favourite).
            _query.debounce(250).map { it.trim() }.distinctUntilChanged().flatMapLatest { q ->
                if (q.length < 2) kotlinx.coroutines.flow.flowOf(null) else episodes.ofPodcast(podcastId).map { episodes.search(podcastId, q) }
            }.collect { _found.value = it }
        }
    }
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
class DiscoverViewModel(
    private val podcasts: PodcastRepository,
    private val directory: DirectoryRepository,
    private val settings: SettingsRepository,
    private val scDirectory: space.sovereigncast.hopp.sovereigncast.ScDirectory,
) : ViewModel() {
    private val _scShows = MutableStateFlow(scDirectory.known)
    /** The curated Sovereigncast shows (device feedback 2026-10-10): results get
     *  their badge from it; null while it is still on its way. */
    val scShows: StateFlow<List<space.sovereigncast.hopp.sovereigncast.ScDirectoryShow>?> = _scShows.asStateFlow()
    private val _scOnly = MutableStateFlow(false)
    val scOnly: StateFlow<Boolean> = _scOnly.asStateFlow()
    fun setScOnly(on: Boolean) { _scOnly.value = on }
    fun isSovereigncast(show: DirectoryShow) = scDirectory.matches(_scShows.value.orEmpty(), show.podcastGuid, show.feedUrl)
    val recent: StateFlow<List<String>> = state(settings.settings.map { it.recentSearches }, emptyList())

    /** A term the listener meant — confirmed or followed to a show — not
     *  every word typed on the way there. */
    fun remember(term: String) {
        val t = term.trim().takeIf { searchable(it) } ?: return
        viewModelScope.launch {
            settings.update { s -> s.copy(recentSearches = (listOf(t) + s.recentSearches.filterNot { it.equals(t, ignoreCase = true) }).take(Settings.RECENT_SEARCHES)) }
        }
    }

    fun clearRecent() = viewModelScope.launch { settings.update { it.copy(recentSearches = emptyList()) } }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _valueOnly = MutableStateFlow(false)
    val valueOnly: StateFlow<Boolean> = _valueOnly.asStateFlow()
    private val _search = MutableStateFlow<SearchUi>(SearchUi.Idle)
    val search: StateFlow<SearchUi> = _search.asStateFlow()
    /** Feed keys and podcast GUIDs of what is subscribed, for the recommendations. */
    private val subscribedKeys = podcasts.shows
        .map { list -> list.flatMap { listOfNotNull(feedKey(it.feedUrl), it.podcastGuid?.lowercase()) }.toSet() }
        .distinctUntilChanged()

    /** "Recommended by your shows" — fetched again whenever the subscriptions change,
     *  and without what is subscribed at once, before that answer is in. Loaded once
     *  only, a recommendation just subscribed stayed in the list (device feedback
     *  2026-10-13): this page outlives the show's page opened from it. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val podroll: StateFlow<List<DirectoryShow>> = state(
        combine(
            subscribedKeys.mapLatest { runCatching { directory.podroll() }.getOrDefault(emptyList()) },
            subscribedKeys,
        ) { list, subscribed ->
            list.filter { feedKey(it.feedUrl) !in subscribed && it.podcastGuid?.lowercase() !in subscribed }
        },
        emptyList(),
    )
    private val _import = MutableStateFlow<AddState>(AddState.Idle)
    val import: StateFlow<AddState> = _import.asStateFlow()
    /** Feed keys of what is subscribed — results show it. */
    val subscribed: StateFlow<Set<String>> = state(podcasts.shows.map { list -> list.map { feedKey(it.feedUrl) }.toSet() }, emptySet())

    init {
        viewModelScope.launch {
            combine(_query, _valueOnly, _scOnly, _scShows) { q, v, sc, list -> listOf(q.trim(), v, sc, list) }
                .debounce(400)
                .distinctUntilChanged()
                .collectLatest { (qa, va, sca) ->
                    val q = qa as String; val v = va as Boolean; val sc = sca as Boolean
                    // The filter on before the list is there: wait for it (it re-runs this).
                    if (sc && _scShows.value == null) { _search.value = SearchUi.Searching; return@collectLatest }
                    if (!searchable(q)) {
                        // Without a term, the filter shows the whole list of Sovereigncast shows.
                        _search.value = if (sc) SearchUi.Done(SearchResult(_scShows.value.orEmpty().map { it.asDirectoryShow() })) else SearchUi.Idle
                        return@collectLatest
                    }
                    _search.value = SearchUi.Searching
                    val r = directory.search(q, v)
                    _search.value = SearchUi.Done(if (sc) r.copy(shows = r.shows.filter { isSovereigncast(it) }) else r)
                }
        }
        if (_scShows.value == null) viewModelScope.launch { _scShows.value = scDirectory.shows() }
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
    data class Loaded(val feed: Feed, val feedUrl: String, val etag: String? = null, val lastModified: String? = null) : PreviewState
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
                        else PreviewState.Loaded(feed, r.movedTo ?: url, r.etag, r.lastModified)
                    }
                    else -> PreviewState.Failed("empty response")
                }
            } catch (e: Exception) {
                PreviewState.Failed(e.message ?: "network error")
            }
        }
    }

    /** Uses the feed this page has already loaded — waits for it if it is
     *  still on its way, rather than fetching and parsing it a second time. */
    fun subscribe(onDone: (Long) -> Unit) {
        if (_working.value) return
        _working.value = true
        _error.value = null
        viewModelScope.launch {
            val loaded = _state.first { it !is PreviewState.Loading } as? PreviewState.Loaded
            val result = if (loaded != null) podcasts.subscribe(loaded.feedUrl, loaded.feed, loaded.etag, loaded.lastModified)
                else podcasts.subscribe(url)
            when (val r = result) {
                is SubscribeResult.Subscribed -> onDone(r.podcastId)
                is SubscribeResult.AlreadySubscribed -> onDone(r.podcastId)
                is SubscribeResult.Failed -> _error.value = r.reason
            }
            _working.value = false
        }
    }
}

/** Episodes per page on the show page and the page before subscribing. */
const val PAGE = 20

private fun space.sovereigncast.hopp.sovereigncast.ScDirectoryShow.asDirectoryShow() =
    DirectoryShow(title, feedUrl, author, imageUrl, podcastGuid)
