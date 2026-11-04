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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.nostr.RelayLog
import space.sovereigncast.hopp.nostr.withRelayPool
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
    /** How many relays answered the last read to its end, of how many (Home's sync window). */
    val relays: Pair<Int, Int>? = null,
    /** Changes made here the relays have not taken yet. */
    val toSend: Int = 0,
    /** What each relay answered in the last round, per document (maintainer 2026-10-29). */
    val relayRounds: List<RelayRound> = emptyList(),
    /** Per relay, its last rounds since the start, oldest first: true where it answered everything. */
    val relayHistory: Map<String, List<Boolean>> = emptyMap(),
    /** Per document, what this device holds of it, in numbers — for the report. */
    val documents: Map<String, String> = emptyMap(),
)

/** What renewing the sync key came to (delta audit 2026-10-22, HN-06/MN-10). */
enum class Renewal {
    /** New key, every document out and read back under it, the old one's gone. */
    Done,
    /** Nothing changed: a round did not get through first, something waits here, or the signer said no. */
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
 * settings first, so shows that follow download only if the listener has that
 * on (device feedback 2026-10-24); then subscriptions, so the episodes whose
 * progress follows are there.
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
    /** After each round, done or not: what waited for it (the downloads of shows it subscribed). */
    private val afterRound: () -> Unit = {},
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val round = Mutex()
    private var pending: Job? = null
    private val history = mutableMapOf<String, List<Boolean>>()

    init {
        journal.onChange = { _, urgent ->
            schedule(urgent)
            // A change waits here from now on, not only once the next round says so (delta audit 3 2026-10-22, ND-04).
            runCatching { engine.toSend() }.getOrNull()?.let { n -> _status.update { it.copy(toSend = n) } }
        }
        scope.launch { settings.settings.map { it.nostrSync }.distinctUntilChanged().collect { journal.enabled = it } }
    }

    /**
     * Turns sync on: what this device has is merged with what the relays hold —
     * nothing is overwritten; on a new device it is a restore. The signer may
     * ask the listener here, once.
     */
    suspend fun enable(): SyncStatus = withRelayPool { enableInPool() }

    private suspend fun enableInPool(): SyncStatus {
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
    suspend fun restoreOffer(): RestoreOffer? = withRelayPool { restoreOfferInPool() }

    private suspend fun restoreOfferInPool(): RestoreOffer? {
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
     * keyring; every document out under it; a second round that reads them all
     * back complete with nothing left to send; only then what the old key sealed
     * deleted ([syncNow], delta audit 2 2026-10-22: the first round alone, its
     * documents taken but not read back, retired the old ones before). Cut off
     * anywhere, later rounds finish it, after a restart too. The listener's
     * other devices ask their signer once and go on.
     */
    suspend fun renewKey(): Renewal {
        if (!settings.current().nostrSync) return Renewal.NotRenewed
        val before = syncNow(interactive = true)
        if (before.problem != null || before.pending > 0 || documents().any { engine.unpublished(it) }) return Renewal.NotRenewed
        try {
            // No round in between: one that began under the old key must not end under the new one.
            round.withLock { engine.renewKey() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SyncNotPublished) {
            // Out on too few relays — or none said so, and one may have kept it all the same (delta audit 3,
            // MN-08/E02): the new key stands here and goes again; nothing retired.
            return Renewal.Unfinished
        } catch (e: Exception) {
            return Renewal.NotRenewed
        }
        syncNow()
        val back = syncNow()
        // Read back: every document there, nothing more to send — and the old key's gone (syncNow retires it then).
        return if (back.problem == null && back.sent == 0 && !engine.movedPending()) Renewal.Done else Renewal.Unfinished
    }

    /** One round over every document; [interactive]: the signer app may be opened. */
    /** A round, every relay on one connection ([withRelayPool]): many short ones were turned away (device check 2026-10-23). */
    suspend fun syncNow(interactive: Boolean = false): SyncStatus = withRelayPool { syncNowInPool(interactive) }

    /** A round the listener asked for — from the profile menu or the sync window — in sync's own scope, so
     *  that it goes on when the menu or the page it came from closes; [rewrite] as in [rewrite]. */
    fun requestRound(rewrite: String? = null) {
        scope.launch { runCatching { if (rewrite == null) syncNow(interactive = true) else rewrite(rewrite) } }
    }

    /** A round in which [doc], read in part, is written anew whole from this device — the listener's word
     *  on the sync page (maintainer 2026-10-30: "Incomplete" for good, nothing to be done). */
    suspend fun rewrite(doc: String): SyncStatus = withRelayPool { syncNowInPool(interactive = true, rewrite = doc) }

    private suspend fun syncNowInPool(interactive: Boolean = false, rewrite: String? = null): SyncStatus = round.withLock {
        if (!settings.current().nostrSync) return _status.value
        _status.value = _status.value.copy(running = true)
        try {
            var problem: SyncOutcome? = null
            val applied = mutableMapOf<String, Int>()
            var pending = 0
            var sent = 0
            val generation = engine.keyGeneration()
            val docs = documents()
            // What each relay answers, per document, for the sync page.
            val log = RelayLog()
            withContext(log) { for (doc in docs) {
                // Shows wait until this device has read the listener's settings to the end once: restored
                // under the fresh default, a show would download although downloads are off elsewhere
                // (delta audit 6 2026-10-24 — settings first is not enough when their read fails).
                if (doc.name == SyncDocs.SUBSCRIPTIONS && docs.any { it.name == SyncDocs.SETTINGS } && store.fetchedAt(SyncDocs.SETTINGS) == null) {
                    if (problem == null) problem = SyncOutcome.Incomplete(0, doc.name)
                    continue
                }
                // A setting synced only since an update joins as at turning sync on: where the relays have nothing.
                if (doc.name == SyncDocs.SETTINGS) store.entries(doc.name).let { known -> store.seed(doc.name, doc.snapshot().filterKeys { it !in known }) }
                log.area = doc.name
                when (val outcome = engine.sync(doc, interactive, rewrite = doc.name == rewrite)) {
                    is SyncOutcome.Done -> { applied[doc.name] = outcome.applied; pending += outcome.pending; if (outcome.published) sent++ }
                    is SyncOutcome.Incomplete -> { applied[doc.name] = outcome.applied; problem = outcome }
                    // Each document would ask the signer again, and wait for it again (2026-11-04).
                    else -> { problem = outcome; if (outcome is SyncOutcome.NoIdentity || outcome.isTheSigner()) break }
                }
            } }
            // A round in the background asks no signer, so it learns nothing new of one: what the signer
            // answered the listener's own round a moment ago stays shown (maintainer 2026-11-04: Amber's
            // silence went under "your signer has to allow it", and nothing came in Amber).
            val shown = _status.value.problem
            if (!interactive && problem == SyncOutcome.WaitsForSigner && shown is SyncOutcome.Failed && shown.isTheSigner()) problem = shown
            val rounds = RelayRound.of(log.notes())
            rounds.forEach { r -> history[r.url] = ((history[r.url] ?: emptyList()) + r.ok).takeLast(HISTORY) }
            // A key moved off goes only after a round under the new one, all of it, read every document back
            // complete and had nothing to send — the relays hold under the new key all this device has, what
            // waits for its time included (delta audit 2026-10-22 and 2, HN-06/MN-02).
            val readBack = problem == null && pending == 0 && sent == 0 && engine.keyGeneration() == generation && engine.toSend() == 0
            if (readBack && engine.movedPending()) runCatching { engine.retireMoved() }.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            // How large each document is here, and what it holds — what a relay's limit would meet.
            val sizes = docs.associate { d ->
                val entries = runCatching { store.entries(d.name) }.getOrDefault(emptyMap())
                val bytes = entries.entries.sumOf { (k, e) -> k.length + 40 + e.d.toString().length }
                d.name to ("${entries.size} entries, ~${(bytes + 1023) / 1024} KB" + (runCatching { d.describe(entries) }.getOrNull()?.let { "; $it" } ?: ""))
            }
            _status.value = SyncStatus(false, if (problem == null) nowMs() else _status.value.lastSyncedMs, problem, applied, pending, sent,
                engine.reach(), runCatching { engine.toSend() }.getOrDefault(0), rounds, history.toMap(), sizes)
            _status.value
        } finally {
            // Cut off (the app closed, sync turned off): not "running" for ever (MN-07).
            if (_status.value.running) _status.value = _status.value.copy(running = false)
            runCatching { afterRound() }
        }
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
        pending = scope.launch {
            delay(if (urgent) publishDelay else PROGRESS_EVERY)
            // Taken back meanwhile — a section hidden and shown again: nothing to send, so no round and
            // no relay asked (maintainer 2026-11-04). The other devices' changes come with the next one.
            if (documents().none { engine.unpublished(it) }) return@launch
            syncNow()
        }
    }

    /** Hopp goes to the background: what is not out yet goes now. */
    /** [prepare]: what is noted just before (the listening statistics, kept back while playing). */
    fun onBackground(prepare: suspend () -> Unit = {}) {
        scope.launch {
            // Prepared with sync off too (device check 2026-10-27): the listening statistics are
            // written here, and an app ended from the background lost the last half minute.
            runCatching { prepare() }.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (!journal.enabled) return@launch
            if (documents().any { engine.unpublished(it) }) { pending?.cancel(); syncNow() }
        }
    }

    companion object {
        val FOREGROUND_EVERY = 5.minutes
        /** How many rounds per relay the sync page shows. */
        const val HISTORY = 10
        /** Positions while playing go out at most this often. */
        val PROGRESS_EVERY = 5.minutes
    }
}
