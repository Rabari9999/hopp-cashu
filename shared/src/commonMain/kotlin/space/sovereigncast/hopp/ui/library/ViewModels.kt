package space.sovereigncast.hopp.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    data class Done(val result: SubscribeResult) : AddState
    data class Imported(val subscribed: Int, val already: Int, val failed: Int) : AddState
}

class DiscoverViewModel(private val podcasts: PodcastRepository) : ViewModel() {
    private val _state = MutableStateFlow<AddState>(AddState.Idle)
    val state: StateFlow<AddState> = _state.asStateFlow()

    fun subscribe(url: String) {
        _state.value = AddState.Working
        viewModelScope.launch { _state.value = AddState.Done(podcasts.subscribe(url)) }
    }

    fun importOpml(text: String) {
        _state.value = AddState.Working
        viewModelScope.launch {
            val results = podcasts.importOpml(text).map { it.second }
            _state.value = AddState.Imported(
                subscribed = results.count { it is SubscribeResult.Subscribed },
                already = results.count { it is SubscribeResult.AlreadySubscribed },
                failed = results.count { it is SubscribeResult.Failed },
            )
        }
    }

    suspend fun exportOpml(): String = podcasts.exportOpml()
    fun reset() { _state.value = AddState.Idle }
}
