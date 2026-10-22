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
    /** What the last round took from the relays, per document. */
    val applied: Map<String, Int> = emptyMap(),
    /** Entries from elsewhere that wait here — a feed out of reach (audit 2026-10-22, M-06). */
    val pending: Int = 0,
    /** How many documents this round sent: none means the relays held all this device has. */
    val sent: Int = 0,
)

/** What renewing the sync key came to (delta audit 2026-10-22, HN-06/MN-10). */
enum class Renewal {
    /** New key, every document out and read back under it, the old one's gone. */
    Done,
    /** Nothing changed: a round did not get through first, something waits here, or the signer or relays said no. */
    NotRenewed,
    /** The new key is there; its documents are not all out yet, or the old ones not yet gone —
     *  the old ones stay until they are, and later rounds finish it. */
    Unfinished,
}

/** Sync turned on brought something from the listener's other devices: [shows] of it subscriptions. */
data class Restored(val shows: Int, val entries: Int)

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
        for (doc in documents()) {
            val known = store.entries(doc.name)
            val (timed, untimed) = doc.snapshot().filterKeys { it !in known }.entries.partition { it.value.t > 0 }
            store.putIfNewer(doc.name, timed.associate { it.toPair() }, pending = true)
            // Kept in the store, not for this round alone: a first round the relays fail, or the app
            // closed before it ends, must not lose the queue and the settings (delta audit 2026-10-22, HN-01).
            store.seed(doc.name, untimed.associate { it.toPair() })
        }
        settings.update { it.copy(nostrSync = true) }
        journal.enabled = true
        val status = syncNow(interactive = true)
        // Something came from elsewhere: the app says so and shows it (maintainer, 2026-10-21).
        if (status.applied.values.sum() > 0) {
            _restored.tryEmit(Restored(status.applied[SyncDocs.SUBSCRIPTIONS] ?: 0, status.applied.values.sum()))
        }
        return status
    }

    private val _restored = kotlinx.coroutines.flow.MutableSharedFlow<Restored>(extraBufferCapacity = 1)
    /** After sync was turned on and brought something: Home, and a line that says what. */
    val restored: kotlinx.coroutines.flow.SharedFlow<Restored> = _restored

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

    /** Turns sync off: this device forgets what it knew and the sync key; the relays keep theirs (ADR 0021, 8). */
    suspend fun disable() {
        pending?.cancel()
        journal.enabled = false
        settings.update { it.copy(nostrSync = false) }
        round.withLock { store.clear(); engine.forgetKey() }
        _status.value = SyncStatus()
    }

    /**
     * A new sync key (ADR 0022, 3): for a lost device or a browser the listener
     * no longer uses. In this order, each step only after the one before went
     * through (delta audit 2026-10-22, HN-06): a round under the old key with
     * nothing left waiting or unsent, so this device holds everything; the new
     * keyring; every document out under it; a second round that finds them all
     * there; only then what the old key sealed deleted. The listener's other
     * devices ask their signer once and go on.
     */
    suspend fun renewKey(): Renewal {
        if (!settings.current().nostrSync) return Renewal.NotRenewed
        val before = syncNow(interactive = true)
        if (before.problem != null || before.pending > 0 || documents().any { engine.unpublished(it) }) return Renewal.NotRenewed
        try {
            engine.renewKey()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SyncNotPublished) {
            // Out on too few relays: the new key stands here and goes again; nothing retired.
            if (e.took > 0) return Renewal.Unfinished
            return Renewal.NotRenewed
        } catch (e: Exception) {
            return Renewal.NotRenewed
        }
        val out = syncNow()
        val back = syncNow()
        // Read back: every document there, nothing more to send — and the old key's gone (syncNow retires it).
        return if (out.problem == null && back.problem == null && back.sent == 0 && !engine.movedPending()) Renewal.Done else Renewal.Unfinished
    }

    /** One round over every document; [interactive]: the signer app may be opened. */
    suspend fun syncNow(interactive: Boolean = false): SyncStatus = round.withLock {
        if (!settings.current().nostrSync) return _status.value
        _status.value = _status.value.copy(running = true)
        var problem: SyncOutcome? = null
        val applied = mutableMapOf<String, Int>()
        var pending = 0
        var sent = 0
        for (doc in documents()) {
            when (val outcome = engine.sync(doc, interactive)) {
                is SyncOutcome.Done -> { applied[doc.name] = outcome.applied; pending += outcome.pending; if (outcome.published) sent++ }
                is SyncOutcome.Incomplete -> { applied[doc.name] = outcome.applied; problem = outcome }
                else -> { problem = outcome; if (outcome is SyncOutcome.NoIdentity) break }
            }
        }
        // A key moved off goes only now: every document went through under the new one, nothing waits (HN-06).
        if (problem == null && pending == 0) runCatching { engine.retireMoved() }.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        _status.value = SyncStatus(false, if (problem == null) nowMs() else _status.value.lastSyncedMs, problem, applied, pending, sent)
        _status.value
    }

    /** The app came to the foreground (or started): a round, unless one ran a moment ago. */
    fun onForeground() {
        // Not by the journal's flag: at start it may not have read the setting yet; a round with sync off ends at once.
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
