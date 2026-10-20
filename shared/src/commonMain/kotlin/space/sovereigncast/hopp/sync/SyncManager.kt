package space.sovereigncast.hopp.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        journal.onChange = { schedule() }
        scope.launch { settings.settings.map { it.nostrSync }.distinctUntilChanged().collect { journal.enabled = it } }
    }

    /**
     * Turns sync on: what this device has is merged with what the relays hold —
     * nothing is overwritten; on a new device it is a restore. The signer may
     * ask the listener here, once.
     */
    suspend fun enable(): SyncStatus {
        val docs = documents()
        for (doc in docs) {
            val known = store.entries(doc.name)
            store.putAll(doc.name, doc.snapshot().filterKeys { it !in known })
        }
        settings.update { it.copy(nostrSync = true) }
        journal.enabled = true
        return syncNow(interactive = true)
    }

    /** Turns sync off: this device forgets what it knew; the relays keep theirs (ADR 0021, 8). */
    suspend fun disable() {
        pending?.cancel()
        journal.enabled = false
        settings.update { it.copy(nostrSync = false) }
        round.withLock { store.clear() }
        _status.value = SyncStatus()
    }

    /** One round over every document; [interactive]: the signer app may be opened. */
    suspend fun syncNow(interactive: Boolean = false): SyncStatus = round.withLock {
        if (!settings.current().nostrSync) return _status.value
        _status.value = _status.value.copy(running = true)
        var problem: SyncOutcome? = null
        for (doc in documents()) {
            val outcome = engine.sync(doc, interactive)
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

    /** A change here: sent out a little later, several changes in one round. */
    private fun schedule() {
        pending?.cancel()
        pending = scope.launch { delay(publishDelay); syncNow() }
    }

    companion object {
        val FOREGROUND_EVERY = 5.minutes
    }
}
