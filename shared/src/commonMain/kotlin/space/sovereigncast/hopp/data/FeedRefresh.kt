package space.sovereigncast.hopp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The refresh of every feed, one at a time whoever asks: a pull on a list, the
 * button where nothing can be pulled (the browser, maintainer 2026-11-01), and
 * the listener's interval while Hopp is open where no background job looks
 * ([whileOpen]: the browser and, until its background refresh, iOS — the web
 * missed a day's episodes the Android app had).
 */
class FeedRefresh(
    private val podcasts: PodcastRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val nowSeconds: () -> Long,
    /** Whether the interval is kept here, while Hopp is open, rather than by the system in the background. */
    val whileOpen: Boolean,
    /** Before the feeds: the other devices' changes (ADR 0021, 5). */
    private val before: suspend () -> Unit = {},
    /** After the feeds: what runs at the Sovereigncast shows. */
    private val after: suspend () -> Unit = {},
    /** Waits that many milliseconds; a test's clock moves on instead. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private var running: Job? = null

    /** A refresh now — or the one already under way. */
    fun now(): Job {
        running?.takeIf { it.isActive }?.let { return it }
        _refreshing.value = true
        return scope.launch {
            try {
                runCatching { before() }
                podcasts.refreshAll()
                runCatching { after() }
            } finally { _refreshing.value = false }
        }.also { running = it }
    }

    /** Refreshes whenever the interval since the last look is over, for as long as it is called
     *  (Hopp open); nothing while the interval is "by hand only". */
    suspend fun keepFresh() {
        while (true) {
            val hours = settings.current().refreshHours
            if (hours <= 0) { settings.settings.first { it.refreshHours > 0 }; continue }
            val last = podcasts.lastChecked.first()
            val due = if (last == null) 0 else last + hours * 3600L - nowSeconds()
            if (due <= 0) {
                now().join()
                // Each feed notes its look even when it failed; this keeps an empty library or a
                // clock gone wrong from asking again at once.
                sleep(MIN_GAP_SECONDS * 1000)
            } else sleep(minOf(due, RECHECK_SECONDS) * 1000)
        }
    }

    companion object {
        const val MIN_GAP_SECONDS = 300L
        /** A changed interval or a look from a pull is seen within this time. */
        const val RECHECK_SECONDS = 600L
    }
}
