package space.sovereigncast.hopp.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.sovereigncast.hopp.data.SettingsRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A library on the relays from the listener's other devices; the counts are null
 *  when the signer would have to be asked to open it. */
data class RestoreOffer(val shows: Int?, val inProgress: Int?)

/** What the Nostr page shows about sync. */
data class SyncStatus(
    val running: Boolean = false,
    /** When every document was last in step, milliseconds; null: not yet. */
    val lastSyncedMs: Long? = null,
    /** Why the last round did not get through — null when it did. */
    val problem: SyncOutcome? = null,
)

/**
 * Runs sync (ADR 0021, 5; docs/tasks/nostr-sync.md): on start, on coming to
 * the foreground at most every [FOREGROUND_EVERY], on pull-to-refresh, and a
 * little after the listener changed something. The documents in order —
 * subscriptions first, so the episodes whose progress follows are there.
 */
class SyncManager(
    private val engine: SyncEngine,
    private val store: SyncStore,
    private val journal: SyncJournal,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val documents: () -> List<SyncedDocument>,
    private val nowMs: () -> Long,
    private val publishDelay: Duration = 10.seconds,
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val round = Mutex()
    private var pending: Job? = null

    init {
        journal.onChange = { _, urgent -> schedule(urgent) }
        scope.launch { settings.settings.map { it.nostrSync }.distinctUntilChanged().collect { journal.enabled = it } }
    }

    /**
     * Turns sync on: what this device has is merged with what the relays hold —
     * nothing is overwritten; on a new device it is a restore. The signer may
     * ask the listener here, once.
     */
    suspend fun enable(): SyncStatus {
        val seeds = mutableMapOf<String, Map<String, SyncEntry>>()
        for (doc in documents()) {
            val known = store.entries(doc.name)
            val (timed, untimed) = doc.snapshot().filterKeys { it !in known }.entries.partition { it.value.t > 0 }
            store.putAll(doc.name, timed.associate { it.toPair() })
            seeds[doc.name] = untimed.associate { it.toPair() }
        }
        settings.update { it.copy(nostrSync = true) }
        journal.enabled = true
        return syncNow(interactive = true, seeds)
    }

    /**
     * Whether the listener's other devices left a library on the relays that
     * this one could take — after signing in with an existing identity, while
     * sync is off here. Null: nothing to restore.
     */
    suspend fun restoreOffer(): RestoreOffer? {
        if (settings.current().nostrSync) return null
        return when (val subs = engine.peek(SyncDocs.SUBSCRIPTIONS)) {
            null -> null
            SyncEngine.Peek.Sealed -> RestoreOffer(null, null)
            is SyncEngine.Peek.Open -> {
                val shows = subs.entries.values.count { !it.del }
                val progress = (engine.peek(SyncDocs.PROGRESS) as? SyncEngine.Peek.Open)?.entries.orEmpty()
                val inProgress = progress.filter { (k, e) -> k.startsWith("pos\n") && !e.del && (e.d["played"]?.toString() != "true") && (e.d["pos"]?.toString()?.toLongOrNull() ?: 0) > 0 }.size
                RestoreOffer(shows, inProgress).takeIf { shows > 0 || inProgress > 0 }
            }
        }
    }

    /** [enable] in the app's own scope — a page that started it may close meanwhile. */
    suspend fun enableAndWait(): SyncStatus = scope.async { enable() }.await()

    /** Turns sync off: this device forgets what it knew; the relays keep theirs (ADR 0021, 8). */
    suspend fun disable() {
        pending?.cancel()
        journal.enabled = false
        settings.update { it.copy(nostrSync = false) }
        round.withLock { store.clear() }
        _status.value = SyncStatus()
    }

    /** One round over every document; [interactive]: the signer app may be opened. */
    suspend fun syncNow(interactive: Boolean = false, seeds: Map<String, Map<String, SyncEntry>> = emptyMap()): SyncStatus = round.withLock {
        if (!settings.current().nostrSync) return _status.value
        _status.value = _status.value.copy(running = true)
        var problem: SyncOutcome? = null
        for (doc in documents()) {
            val outcome = engine.sync(doc, interactive, seeds[doc.name].orEmpty())
            if (outcome !is SyncOutcome.Done) { problem = outcome; if (outcome is SyncOutcome.NoIdentity) break }
        }
        _status.value = SyncStatus(false, if (problem == null) nowMs() else _status.value.lastSyncedMs, problem)
        _status.value
    }

    /** The app came to the foreground (or started): a round, unless one ran a moment ago. */
    fun onForeground() {
        val last = _status.value.lastSyncedMs
        if (last != null && nowMs() - last < FOREGROUND_EVERY.inWholeMilliseconds) return
        scope.launch { syncNow() }
    }

    /**
     * A change here: sent out a little later, several changes in one round. A
     * position while playing waits up to [PROGRESS_EVERY]; a pause, the end of
     * an episode or anything else the listener did goes sooner (ADR 0021, 5).
     */
    private fun schedule(urgent: Boolean) {
        // A position joins whatever round is coming and never puts one off.
        if (!urgent && pending?.isActive == true) return
        pending?.cancel()
        pending = scope.launch { delay(if (urgent) publishDelay else PROGRESS_EVERY); syncNow() }
    }

    /** Hopp goes to the background: what is not out yet goes now. */
    fun onBackground() {
        if (!journal.enabled) return
        if (documents().any { engine.unpublished(it) }) { pending?.cancel(); scope.launch { syncNow() } }
    }

    companion object {
        val FOREGROUND_EVERY = 5.minutes
        /** Positions while playing go out at most this often. */
        val PROGRESS_EVERY = 5.minutes
    }
}
