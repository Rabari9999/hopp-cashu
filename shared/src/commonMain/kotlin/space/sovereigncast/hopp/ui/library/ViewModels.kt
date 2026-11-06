package space.sovereigncast.hopp.ui.library

import kotlinx.coroutines.flow.flow
import space.sovereigncast.hopp.directory.Recommendation
import space.sovereigncast.hopp.directory.Category
import kotlinx.coroutines.flow.mapLatest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.directory.DirectoryRepository
import space.sovereigncast.hopp.directory.DirectoryShow
import space.sovereigncast.hopp.directory.SearchResult
import space.sovereigncast.hopp.directory.feedKey
import space.sovereigncast.hopp.directory.titleMatches
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

/** Pull-to-refresh over all subscriptions, shared by Home, New and Library: one refresh for
 *  all of them and the top bar's button ([FeedRefresh]). */
abstract class RefreshingViewModel(private val feeds: space.sovereigncast.hopp.data.FeedRefresh) : ViewModel() {
    val refreshing: StateFlow<Boolean> = feeds.refreshing
    fun refresh() { feeds.now() }
}

class InboxViewModel(private val episodes: EpisodeRepository, podcasts: PodcastRepository, feeds: space.sovereigncast.hopp.data.FeedRefresh) : RefreshingViewModel(feeds) {
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
    feeds: space.sovereigncast.hopp.data.FeedRefresh,
    sync: space.sovereigncast.hopp.sync.SyncManager? = null,
    identities: space.sovereigncast.hopp.identity.IdentityRepository? = null,
    private val queueRepo: QueueRepository? = null,
    private val player: space.sovereigncast.hopp.player.PlayerController? = null,
) : RefreshingViewModel(feeds) {
    /** What runs at the listener's Sovereigncast shows (device feedback 2026-10-10). */
    val actions: StateFlow<List<space.sovereigncast.hopp.sovereigncast.ShowAction>> = state(sc.actions.map { it.orEmpty() }, emptyList())

    init {
        // On start the Sovereigncast shows first (device feedback 2026-10-17); new
        // subscriptions get checked against their instance; whenever the set of
        // verified shows changes — here or on a show page — the list follows.
        viewModelScope.launch { sc.refreshOnStart() }
        // Sizes of episodes from before schema version 10, for the lists (user request 2026-10-18).
        viewModelScope.launch { runCatching { episodes.fillEnclosureLengths() } }
        viewModelScope.launch { podcasts.shows.map { l -> l.map { it.id }.toSet() }.distinctUntilChanged().drop(1).collectLatest { sc.verifyAll() } }
        viewModelScope.launch { sc.verifiedShows().distinctUntilChanged().drop(1).collectLatest { sc.refresh() } }
        // Home's order changed with 0.4.0: who had Hopp's before keeps it, "Up next" after "Continue" (maintainer 2026-11-02).
        viewModelScope.launch {
            settings.update { s ->
                when {
                    s.homeOrder033 -> s
                    s.onboardingDone && s.homeOrder.isEmpty() -> s.copy(homeOrder = HomeSections.before033, homeOrder033 = true)
                    else -> s.copy(homeOrder033 = true)
                }
            }
        }
        // "Continue listening" went into the playlist (Dennis, maintainer 2026-11-03): what was begun there and
        // not in "Up next" joins the playlist's top, once — not on a new install, which has nothing begun.
        viewModelScope.launch {
            var adopt = false
            settings.update { s -> if (s.playlistMerged) s else { adopt = s.onboardingDone; s.copy(playlistMerged = true) } }
            val q = queueRepo ?: return@launch
            if (!adopt) return@launch
            val begun = episodes.inProgress.first()
            q.adoptInProgress(begun, except = player?.current?.value?.item?.id ?: begun.firstOrNull()?.id)
        }
    }

    /** "Up next" on Home (Dennis, maintainer 2026-11-02), reorderable there. */
    val queue: StateFlow<List<EpisodeItem>> = state(queueRepo?.items ?: kotlinx.coroutines.flow.flowOf(emptyList()), emptyList())
    fun reorderQueue(ids: List<Long>) { val q = queueRepo ?: return; viewModelScope.launch { q.reorder(ids) } }
    /** For arranging Home in place. */
    val current: StateFlow<Settings?> = state(settings.settings, null)
    fun update(change: (Settings) -> Settings) = viewModelScope.launch { settings.update(change) }

    /** "Take part" without what the listener hid there (device feedback 2026-10-22);
     *  [actions] stays whole — "Your shows" still counts down to a release. */
    val takePart: StateFlow<List<space.sovereigncast.hopp.sovereigncast.ShowAction>> = state(
        combine(sc.actions.map { it.orEmpty() }, settings.settings.map { it.takePartHidden.toSet() }) { all, hidden ->
            all.filterNot { TakePart.hides(hidden, it) }
        },
        emptyList(),
    )
    fun hideTakePart(key: String) = viewModelScope.launch { settings.update { it.copy(takePartHidden = TakePart.add(it.takePartHidden, key)) } }
    fun showTakePartAgain(key: String) = viewModelScope.launch { settings.update { it.copy(takePartHidden = it.takePartHidden - key) } }


    /** Sections folded away — kept across starts (device feedback 2026-10-09). */
    val collapsed: StateFlow<Set<String>> = state(settings.settings.map { it.homeCollapsed }, emptySet())
    /** The listener's order of Home's sections (empty: Hopp's own) and what they hid. */
    val savedOrder: StateFlow<List<String>> = state(settings.settings.map { it.homeOrder }, emptyList())
    val hidden: StateFlow<Set<String>> = state(settings.settings.map { it.homeHidden }, emptySet())
    /** Who Home greets: the name the listener boosts with, if they gave one (device feedback
     *  2026-10-13), else their Nostr profile's name (device feedback 2026-10-21: after a
     *  restore from Nostr the boost name was empty, and Home greeted no one). */
    val greetName: StateFlow<String?> = state(
        combine(settings.settings, identities?.identity ?: kotlinx.coroutines.flow.flowOf(null)) { s, me ->
            s.senderName.trim().takeIf { it.isNotEmpty() } ?: me?.name?.trim()?.takeIf { it.isNotEmpty() }
        },
        null,
    )
    fun toggle(section: String) = viewModelScope.launch {
        settings.update { s -> s.copy(homeCollapsed = if (section in s.homeCollapsed) s.homeCollapsed - section else s.homeCollapsed + section) }
    }
    val inbox: StateFlow<List<EpisodeItem>?> = state(episodes.inbox, null)
    val hasShows: StateFlow<Boolean?> = state(podcasts.shows.map { it.isNotEmpty() }, null)
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
    /** All the shows: the library's tag filter stays in the library, where it is seen (maintainer 2026-11-04). */
    val shows: StateFlow<List<space.sovereigncast.hopp.data.Show>> = state(podcasts.shows, emptyList())
    val rhythms: StateFlow<Map<Long, space.sovereigncast.hopp.data.Rhythm>> = state(episodes.rhythms, emptyMap())
    val lastChecked: StateFlow<Long?> = state(podcasts.lastChecked, null)

    companion object { const val HOME_ROWS = 5; const val TAKE_PART_ROWS = 2; const val QUEUE_ROWS = 5 }
}

/** [shows] in the library's [sort]; A–Z among equals, and shows without episodes last. */
internal fun sorted(shows: List<Show>, counts: Map<Long, space.sovereigncast.hopp.data.ShowCounts>, sort: String): List<Show> {
    val byTitle = compareBy<Show, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
    return when (sort) {
        Settings.SORT_TITLE -> shows.sortedWith(byTitle)
        Settings.SORT_UNHEARD -> shows.sortedWith(compareByDescending<Show> { counts[it.id]?.unheard ?: 0 }.thenByDescending { counts[it.id]?.latestAt ?: Long.MIN_VALUE }.then(byTitle))
        else -> shows.sortedWith(compareByDescending<Show> { counts[it.id]?.latestAt ?: Long.MIN_VALUE }.then(byTitle))
    }
}

class LibraryViewModel(
    private val podcastsRepo: PodcastRepository,
    private val episodes: EpisodeRepository,
    private val queue: QueueRepository,
    downloads: DownloadRepository,
    private val settings: SettingsRepository,
    feeds: space.sovereigncast.hopp.data.FeedRefresh,
) : RefreshingViewModel(feeds) {
    /** Every show's episodes heard at once (maintainer 2026-11-01). */
    fun markAllPlayed() = viewModelScope.launch { episodes.setAllPlayed() }
    /** The listener's order (device feedback 2026-10-19): the show with the newest episode first by default. */
    val sort: StateFlow<String> = state(settings.settings.map { it.librarySort }, Settings.SORT_NEWEST)
    fun sortBy(sort: String) = viewModelScope.launch { settings.update { it.copy(librarySort = sort) } }
    val shows: StateFlow<List<Show>?> = state(
        combine(podcastsRepo.shows, episodes.countsPerShow, settings.settings.map { it.librarySort to it.libraryTag }.distinctUntilChanged()) { shows, counts, (sort, tag) ->
            sorted(space.sovereigncast.hopp.data.Tags.filter(shows, tag), counts, sort)
        },
        null,
    )
    /** The listener's tags, and the one the library shows (maintainer 2026-11-03); a tap on the chosen one shows all again. */
    val tags: StateFlow<List<String>> = state(podcastsRepo.shows.map { space.sovereigncast.hopp.data.Tags.all(it) }, emptyList())
    val tag: StateFlow<String> = state(settings.settings.map { it.libraryTag }, "")
    fun filterTag(tag: String) = viewModelScope.launch { settings.update { it.copy(libraryTag = if (it.libraryTag == tag) "" else tag) } }
    /** A show's tags from the library (a long press on it), and a new tag with its shows at once (maintainer 2026-11-03). */
    fun setTags(id: Long, tags: List<String>) = viewModelScope.launch { podcastsRepo.setTags(id, tags) }
    fun createTag(name: String, shows: Set<Long>) = viewModelScope.launch {
        val tag = name.trim().take(space.sovereigncast.hopp.data.Tags.MAX_LENGTH)
        if (tag.isNotEmpty()) { podcastsRepo.setTagFor(tag, shows); settings.update { it.copy(libraryTag = tag) } }
    }
    /** Every show, unfiltered and A–Z — the new tag's list. */
    val allShows: StateFlow<List<Show>> = state(podcastsRepo.shows.map { l -> l.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) }, emptyList())
    /** Every show, unfiltered — the count in the "Shows" title says how many there are. */
    val showCount: StateFlow<Int?> = state(podcastsRepo.shows.map { it.size }, null)
    val queueCount: StateFlow<Int> = state(queue.items.map { it.size }, 0)
    val downloadCount: StateFlow<Int> = state(downloads.downloads.map { list -> list.count { it.downloadState == DownloadState.DONE } }, 0)
    /** "Up next" and the downloads in the library itself, each a section that folds (Dennis, maintainer 2026-11-02). */
    val queueItems: StateFlow<List<EpisodeItem>> = state(queue.items, emptyList())
    fun reorderQueue(ids: List<Long>) = viewModelScope.launch { queue.reorder(ids) }
    val downloaded: StateFlow<List<EpisodeItem>> = state(downloads.downloads.map { list -> list.filter { it.downloadState == DownloadState.DONE } }, emptyList())
    val collapsed: StateFlow<Set<String>> = state(settings.settings.map { it.homeCollapsed }, emptySet())
    fun toggle(section: String) = viewModelScope.launch {
        settings.update { s -> s.copy(homeCollapsed = if (section in s.homeCollapsed) s.homeCollapsed - section else s.homeCollapsed + section) }
    }
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
    /** The playlist emptied — the episodes keep their progress. */
    fun clear() = viewModelScope.launch { queue.clear() }
}

class DownloadsViewModel(private val downloads: DownloadRepository) : ViewModel() {
    val items: StateFlow<List<EpisodeItem>?> = state(downloads.downloads, null)
    val usedBytes: StateFlow<Long> = state(downloads.usedBytes, 0L)
    val progress: StateFlow<Map<Long, Float>> = downloads.progress
    fun remove(item: EpisodeItem) = viewModelScope.launch { downloads.remove(item.id) }
    val waiting: StateFlow<Long> = state(downloads.waitingCount, 0L)
    fun startAllNow() = viewModelScope.launch { downloads.startNow() }
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
    /** Out of the playlist, and back where it was when [undo] says so (maintainer 2026-11-04) — here, not
     *  in the row, which is gone by then. */
    fun removeFromQueue(item: EpisodeItem, undo: suspend () -> Boolean) = viewModelScope.launch {
        val taken = queue.removeKeepingOrder(item.id)
        if (undo()) queue.putBack(item.id, taken)
    }
    fun download(item: EpisodeItem) = viewModelScope.launch { downloads.request(item.id) }
    fun removeDownload(item: EpisodeItem) = viewModelScope.launch { downloads.remove(item.id) }
    fun downloadNow(item: EpisodeItem) = viewModelScope.launch { downloads.startNow(item.id) }
    fun setPlayed(item: EpisodeItem, played: Boolean) = viewModelScope.launch { episodes.setPlayed(item.id, played) }
    fun setPlayedUpTo(item: EpisodeItem) = viewModelScope.launch { episodes.setPlayedUpTo(item) }
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
    private val downloads: space.sovereigncast.hopp.data.DownloadRepository,
) : ViewModel() {
    /** What the downloads take and how many (device feedback 2026-10-14). */
    val downloadBytes: StateFlow<Long> = state(downloads.usedBytes, 0L)
    val downloadCount: StateFlow<Long> = state(downloads.downloadedCount, 0L)
    fun deleteAllDownloads() = viewModelScope.launch { downloads.removeAll() }
    /** Off: what waits for Wi-Fi unasked is dropped too. */
    fun setAutoDownloads(on: Boolean) = viewModelScope.launch {
        settings.update { it.copy(autoDownloads = on) }
        if (!on) downloads.dropAutomatic()
    }

    val current: StateFlow<Settings?> = state(settings.settings, null)
    /** Show titles by podcast:guid — for what was hidden under Home's "Take part". */
    val showTitles: StateFlow<Map<String, String>> = state(
        podcasts.shows.map { l -> l.mapNotNull { s -> s.podcastGuid?.takeIf { it.isNotEmpty() }?.let { it to s.title } }.toMap() },
        emptyMap(),
    )
    /** The shows, for "Per show": each one's speed and what it skips. */
    val shows: StateFlow<List<space.sovereigncast.hopp.data.Show>> = state(podcasts.shows, emptyList())
    fun setShowSpeed(id: Long, speed: Float?) = viewModelScope.launch { podcasts.setSpeed(id, speed) }
    fun setShowSkip(id: Long, intro: Int? = null, outro: Int? = null) = viewModelScope.launch { podcasts.setSkip(id, introSeconds = intro, outroSeconds = outro) }
    /** A show shared with mutual Nostr contacts, or left out (ADR 0024, 4a) — [ids] at once from "Shared shows". */
    fun setSharedWithContacts(ids: Collection<Long>, shared: Boolean) = viewModelScope.launch { ids.forEach { podcasts.setContactsHidden(it, !shared) } }
    /** The connected wallet's name, for the settings overview; null = none. */
    val walletLabel: StateFlow<String?> = state(wallet.wallet.map { it?.shown }, null)
    fun update(change: (Settings) -> Settings) = viewModelScope.launch { settings.update(change) }
    suspend fun exportOpml(): String = podcasts.exportOpml()

    /** An OPML file another app handed over: how many shows it names, and how many of them are here already. */
    data class OpmlFile(val text: String, val shows: Int, val already: Int)

    suspend fun opmlFile(text: String): OpmlFile {
        val entries = space.sovereigncast.hopp.data.Opml.parse(text)
        val here = podcasts.shows.first().map { space.sovereigncast.hopp.directory.feedKey(it.feedUrl) }.toSet()
        val keys = entries.map { space.sovereigncast.hopp.directory.feedKey(it.feedUrl) }.toSet()
        return OpmlFile(text, keys.size, keys.count { it in here })
    }

    private val _import = MutableStateFlow<AddState>(AddState.Idle)
    val import: StateFlow<AddState> = _import.asStateFlow()
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
        combine(settings.settings, wallet.wallet, network.observe()) { s, w, n -> PrivacyState(s, w?.takeIf { !it.browser }?.relay?.substringAfter("://")?.substringBefore('/')?.takeIf { it.isNotBlank() }, n) },
        null,
    )
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShowViewModel(
    private val podcastId: Long,
    private val podcasts: PodcastRepository,
    private val episodes: EpisodeRepository,
    private val directory: DirectoryRepository? = null,
    contacts: space.sovereigncast.hopp.contacts.ContactsRepository? = null,
) : ViewModel() {
    val show: StateFlow<Show?> = state(podcasts.show(podcastId), null)
    /** The listener shares with mutual Nostr contacts: the menu offers to leave this show out (ADR 0024, 4a). */
    val sharingWithContacts: StateFlow<Boolean> = state(contacts?.state?.map { it.note?.sharing == true } ?: kotlinx.coroutines.flow.flowOf(false), false)
    fun setSharedWithContacts(shared: Boolean) = viewModelScope.launch { podcasts.setContactsHidden(podcastId, !shared) }
    /** The show's own tags, and every tag the listener has (maintainer 2026-11-03). */
    fun setTags(tags: List<String>) = viewModelScope.launch { podcasts.setTags(podcastId, tags) }
    val allTags: StateFlow<List<String>> = state(podcasts.shows.map { space.sovereigncast.hopp.data.Tags.all(it) }, emptyList())
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

    /** Looking for a boost-capable feed variant of this show (see
     *  DirectoryRepository.boostableAlternative) — only when the listener
     *  asks: by itself the page would tell the index about a subscription,
     *  which ADR 0015 reserves for the batch check (review of 2026-10-19). */
    sealed interface Alternative {
        data object NotAsked : Alternative
        data object Searching : Alternative
        data object None : Alternative
        data class Found(val show: DirectoryShow) : Alternative
    }

    private val _alternative = MutableStateFlow<Alternative>(Alternative.NotAsked)
    val alternative: StateFlow<Alternative> = _alternative.asStateFlow()

    init { viewModelScope.launch { _meta.value = podcasts.podcastMeta(podcastId) } }

    /** The listener taps the line under a show whose feed has no value block. */
    fun findAlternative() {
        val dir = directory ?: return
        if (_alternative.value != Alternative.NotAsked) return
        _alternative.value = Alternative.Searching
        viewModelScope.launch {
            val s = podcasts.show(podcastId).first()
            val found = s?.let { runCatching { dir.boostableAlternative(it.title, it.feedUrl, it.author, it.podcastGuid) }.getOrNull() }
            _alternative.value = found?.let { Alternative.Found(it) } ?: Alternative.None
        }
    }

    /** The feed fetched again; [then] once it is stored — the instance asked again with it. */
    fun refresh(then: () -> Unit = {}) {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try { runCatching { podcasts.refresh(podcastId) }; _meta.value = podcasts.podcastMeta(podcastId) }
            finally { _refreshing.value = false; then() }
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
    /** [speed] null: the show follows the default speed. */
    fun setSpeed(speed: Float?) = viewModelScope.launch { podcasts.setSpeed(podcastId, speed) }
    fun setSkipIntro(seconds: Int) = viewModelScope.launch { podcasts.setSkip(podcastId, introSeconds = seconds) }
    fun setSkipOutro(seconds: Int) = viewModelScope.launch { podcasts.setSkip(podcastId, outroSeconds = seconds) }
    fun markAllPlayed() = viewModelScope.launch { episodes.setShowPlayed(podcastId) }
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
private const val SUGGESTIONS = 5
private const val SEEN = 300
/** "For you": at most this many, of them this many per category. */
private const val FOR_YOU = 12
private const val FOR_YOU_PER_CATEGORY = 3

internal fun searchable(q: String) = q.length >= 2 && "://" !in q && '/' !in q

/** Discover (ADR 0008): search as you type, the V4V filter, a feed address
 *  opened directly, the subscribed shows' recommendations, OPML import. */
@OptIn(FlowPreview::class)
class DiscoverViewModel(
    private val podcasts: PodcastRepository,
    private val directory: DirectoryRepository,
    private val settings: SettingsRepository,
    private val scDirectory: space.sovereigncast.hopp.sovereigncast.ScDirectory,
    private val people: space.sovereigncast.hopp.data.PeopleRepository,
    private val contacts: space.sovereigncast.hopp.contacts.ContactsRepository? = null,
) : ViewModel() {
    private val _people = MutableStateFlow<List<space.sovereigncast.hopp.data.PersonMatch>>(emptyList())
    /** People in the subscribed shows whose name holds the term (0.3.1) — found here, on the device. */
    val persons: StateFlow<List<space.sovereigncast.hopp.data.PersonMatch>> = _people.asStateFlow()
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

    /** Discover shown — each time, not only when first built: the shared lists looked at again every two minutes,
     *  everything read again when older than ten (device feedback 2026-11-02). */
    fun lookAtContacts() { val c = contacts ?: return; viewModelScope.launch { c.readToTheEnd(maxAge = 600) } }

    /** "With your Nostr contacts" (ADR 0024, 7): what they share, most of them first; shows the listener
     *  has left out. Empty unless the listener looks at it — and so without a Nostr identity. */
    val withContacts: StateFlow<List<space.sovereigncast.hopp.contacts.ContactPick>> = state(
        if (contacts == null) kotlinx.coroutines.flow.flowOf(emptyList())
        else combine(contacts.state, subscribedKeys) { s, subscribed ->
            if (s.note?.seeing != true) emptyList()
            else s.read?.listening().orEmpty().filter { p -> feedKey(p.show.url) !in subscribed && p.show.guid?.lowercase() !in subscribed }.take(20)
        },
        emptyList(),
    )

    /** "Recommended by your shows" — fetched again whenever the subscriptions change,
     *  and without what is subscribed at once, before that answer is in. Loaded once
     *  only, a recommendation just subscribed stayed in the list (device feedback
     *  2026-10-13): this page outlives the show's page opened from it. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val podroll: StateFlow<List<Recommendation>> = state(
        combine(
            subscribedKeys.mapLatest { runCatching { directory.podroll() }.getOrDefault(emptyList()) },
            subscribedKeys,
        ) { list, subscribed ->
            list.filter { feedKey(it.show.feedUrl) !in subscribed && it.show.podcastGuid?.lowercase() !in subscribed }
        },
        emptyList(),
    )

    // ── Discover before a search (device feedback 2026-10-19) ────────────────

    /** The device's language, the one Discover starts in. */
    val deviceLanguage: String? = androidx.compose.ui.text.intl.Locale.current.language.takeIf { it.length == 2 }
    /** The language popular and new shows are in (device feedback 2026-10-19): the listener's
     *  choice, else the device's; null — every language. The Podcast Index knows a feed's
     *  language, not its country. */
    val language: StateFlow<String?> = state(settings.settings.map { s ->
        when (s.discoverLanguage) { "" -> deviceLanguage; Settings.LANGUAGE_ALL -> null; else -> s.discoverLanguage }
    }.distinctUntilChanged(), deviceLanguage)
    /** The setting itself: "" (the device's), [Settings.LANGUAGE_ALL] or a code. */
    val languageChoice: StateFlow<String> = state(settings.settings.map { it.discoverLanguage }, "")
    fun selectLanguage(code: String) = viewModelScope.launch { settings.update { it.copy(discoverLanguage = code) } }

    /** The categories of the subscribed shows, the most subscribed first (each show by its first). */
    val myCategories: StateFlow<List<Category>> = state(
        podcasts.shows.map { shows ->
            shows.mapNotNull { Category.of(it.categories).firstOrNull() }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key }
        }.distinctUntilChanged(),
        emptyList(),
    )

    private val _category = MutableStateFlow<Category?>(null)
    /** The category chosen; null: "For you". */
    val category: StateFlow<Category?> = _category.asStateFlow()
    fun selectCategory(c: Category?) { _category.value = c }

    /** What is popular right now, in the chosen category; null while it loads. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val trending: StateFlow<List<DirectoryShow>?> = state(
        combine(_category, language) { c, l -> c to l }.flatMapLatest { (c, l) -> flow { emit(null); emit(runCatching { directory.trending(l, c) }.getOrDefault(emptyList())) } },
        null,
    )

    /** Shows newly with a value block, in the chosen category; null while it loads. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val newValue: StateFlow<List<DirectoryShow>?> = state(
        combine(_category, language) { c, l -> c to l }.flatMapLatest { (c, l) -> flow { emit(null); emit(runCatching { directory.newValue(l, c) }.getOrDefault(emptyList())) } },
        null,
    )

    /**
     * "For you": what the subscribed shows recommend, then what is popular in
     * the two categories the listener subscribed most — each with its reason,
     * nothing subscribed and nothing twice.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val forYou: StateFlow<List<Recommendation>> = state(
        combine(podroll, myCategories.map { it.take(2) }.distinctUntilChanged(), subscribedKeys, language) { rolled, cats, subscribed, l -> listOf(rolled, cats, subscribed, l) }
            .mapLatest { (rolledA, catsA, subscribedA, l) ->
                @Suppress("UNCHECKED_CAST") val rolled = rolledA as List<Recommendation>
                @Suppress("UNCHECKED_CAST") val cats = catsA as List<Category>
                @Suppress("UNCHECKED_CAST") val subscribed = subscribedA as Set<String>
                val language = l as String?
                val seen = rolled.map { feedKey(it.show.feedUrl) }.toMutableSet()
                val popular = cats.flatMap { c ->
                    runCatching { directory.trending(language, c) }.getOrDefault(emptyList())
                        .filter { s -> feedKey(s.feedUrl) !in subscribed && s.podcastGuid?.lowercase() !in subscribed && seen.add(feedKey(s.feedUrl)) }
                        .take(FOR_YOU_PER_CATEGORY).map { Recommendation(it, category = c) }
                }
                (rolled.take(FOR_YOU - popular.size.coerceAtMost(FOR_YOU / 2)) + popular).take(FOR_YOU)
            },
        emptyList(),
    )
    private val _import = MutableStateFlow<AddState>(AddState.Idle)
    val import: StateFlow<AddState> = _import.asStateFlow()
    /** Feed keys of what is subscribed — results show it. */
    val subscribed: StateFlow<Set<String>> = state(podcasts.shows.map { list -> list.map { feedKey(it.feedUrl) }.toSet() }, emptySet())

    init {
        // A category tapped on a show's page opens here.
        viewModelScope.launch { directory.requested.collect { c -> if (c != null) { _category.value = c; _query.value = ""; directory.requested.value = null } } }
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
                    // The library read beside the directories, not before them (review 2026-10-27).
                    val (found, r) = kotlinx.coroutines.coroutineScope {
                        val found = async { runCatching { people.find(q) }.getOrDefault(emptyList()) }
                        val r = directory.search(q, v)
                        found.await() to r
                    }
                    _people.value = found
                    seen.value = (r.shows + seen.value).distinctBy { feedKey(it.feedUrl) }.take(SEEN)
                    _search.value = SearchUi.Done(if (sc) r.copy(shows = r.shows.filter { isSovereigncast(it) }) else r)
                }
        }
        if (_scShows.value == null) viewModelScope.launch { _scShows.value = scDirectory.shows() }
    }

    fun setQuery(q: String) { _query.value = q }
    fun setValueOnly(on: Boolean) { _valueOnly.value = on }

    private val _subscribing = MutableStateFlow<Set<String>>(emptySet())
    /** Feed keys being subscribed from the list right now. */
    val subscribing: StateFlow<Set<String>> = _subscribing.asStateFlow()

    /** Subscribes from the result list, staying on the search (device feedback
     *  2026-10-15); [onFailed] when it did not work — the show's page says why. */
    fun subscribe(show: DirectoryShow, onFailed: () -> Unit) {
        val key = feedKey(show.feedUrl)
        if (key in _subscribing.value) return
        _subscribing.update { it + key }
        viewModelScope.launch {
            val r = podcasts.subscribe(show.feedUrl)
            _subscribing.update { it - key }
            if (r is SubscribeResult.Failed) onFailed()
        }
    }

    /** Shows the directories answered with in this session: typed again in
     *  part, they are suggested at once. */
    private val seen = MutableStateFlow<List<DirectoryShow>>(emptyList())

    /**
     * Suggestions while typing (device feedback 2026-10-14): the directories
     * only find whole words, so what Hopp already knows — the Sovereigncast
     * shows, the subscriptions, the recommendations and this session's results
     * — is matched on the letters typed so far, without waiting.
     */
    val suggestions: StateFlow<List<DirectoryShow>> = state(
        combine(_query, _scShows, podcasts.shows, podroll, seen) { q, sc, subscribed, rolled, found ->
            val term = q.trim()
            if (!searchable(term)) return@combine emptyList()
            val known = sc.orEmpty().map { it.asDirectoryShow() } + subscribed.map { DirectoryShow(it.title, it.feedUrl, it.author, it.imageUrl, it.podcastGuid) } +
                rolled.map { it.show } + found
            known.filter { titleMatches(it.title, term) }.distinctBy { feedKey(it.feedUrl) }.take(SUGGESTIONS)
        },
        emptyList(),
    )

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
class ShowPreviewViewModel(
    input: String, private val fetcher: FeedFetcher, private val podcasts: PodcastRepository,
    private val sc: space.sovereigncast.hopp.sovereigncast.SovereigncastRepository? = null,
) : ViewModel() {
    private val _layer = MutableStateFlow<space.sovereigncast.hopp.sovereigncast.ScLayer?>(null)
    /** The show's Sovereigncast layer before subscribing (maintainer 2026-10-27), once its instance confirmed this feed. */
    val layer: StateFlow<space.sovereigncast.hopp.sovereigncast.ScLayer?> = _layer.asStateFlow()
    private val _suggestions = MutableStateFlow<List<space.sovereigncast.hopp.sovereigncast.Suggestion>?>(null)
    /** Its open topic suggestions, read only here; null while unknown. */
    val suggestions: StateFlow<List<space.sovereigncast.hopp.sovereigncast.Suggestion>?> = _suggestions.asStateFlow()

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
                        else PreviewState.Loaded(feed, r.movedTo ?: url, r.etag, r.lastModified).also { loaded ->
                            if (sc != null) viewModelScope.launch {
                                val l = sc.layerOf(loaded.feedUrl, feed.podcast) ?: return@launch
                                _layer.value = l
                                if (l.show.capabilities.suggestions) _suggestions.value = runCatching { sc.suggestions(l).suggestions }.getOrNull()
                            }
                        }
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
