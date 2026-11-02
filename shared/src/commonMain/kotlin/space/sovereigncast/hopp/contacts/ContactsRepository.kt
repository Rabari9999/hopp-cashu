package space.sovereigncast.hopp.contacts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.sovereigncast.hopp.identity.IdentityRepository

/** What the Nostr page shows of the feature (ADR 0024). */
data class ContactsState(
    /** The listener's contacts note; null: both switches off. */
    val note: ContactKeys.Note? = null,
    /** A mutual contact was lost, and the signer has to be asked for a new share key: on the Nostr page. */
    val renewPending: Boolean = false,
    /** The contacts, as last read; null until then. */
    val read: ContactsRead? = null,
    val working: Boolean = false,
    /** Why the last change or read did not go through. */
    val error: String? = null,
)

/**
 * The listener's Nostr contacts and what Hopp shares with them (ADR 0024): the two switches,
 * kept in the contacts note ([ContactKeys]) so every device shows the same, and the contacts
 * read ([ContactGraph]) at most every [readEvery] seconds. Nothing without a Nostr identity.
 */
class ContactsRepository(
    private val identity: IdentityRepository,
    private val keys: ContactKeys,
    private val graph: ContactGraph,
    private val nowSeconds: () -> Long,
    private val share: ContactShare? = null,
    /** What was read last, kept between starts — and keeping it; null: nothing kept. */
    private val cache: ContactsCache? = null,
    /** A show by its `podcast:guid`, from the directory — for shows contacts only boosted or commented on. */
    private val lookup: (suspend (String) -> SharedShow?)? = null,
    private val readEvery: Long = 3600,
) {
    private val _state = MutableStateFlow(ContactsState())
    val state: StateFlow<ContactsState> = _state.asStateFlow()
    private val lock = Mutex()

    /** Where what was read last is kept (ADR 0024, 7). */
    interface ContactsCache {
        suspend fun load(): ContactsRead?
        suspend fun save(read: ContactsRead?)
    }

    /** What was read before this start, at once — then the relays when due. */
    suspend fun loadKept() {
        if (identity.identity.value == null || _state.value.read != null) return
        runCatching { cache?.load() }.getOrNull()?.let { kept -> _state.update { if (it.read == null) it.copy(read = kept) else it } }
    }

    /**
     * The note and, when due or [force]d, the contacts. Due after [readEvery] seconds — or after [maxAge],
     * when given: the Nostr page reads again what is older than a few minutes, so a contact's new inbox
     * gets its key and what they share shows without waiting for the hour (device feedback 2026-11-02).
     * [interactive]: a note made on another device is taken over.
     */
    suspend fun refresh(force: Boolean = false, interactive: Boolean = false, maxAge: Long = readEvery) = lock.withLock {
        if (identity.identity.value == null) { _state.value = ContactsState(); return@withLock }
        // What was read before this start first: a refresh right after it is then no new read.
        loadKept()
        work {
            val note = keys.note(interactive)
            _state.update { it.copy(note = note) }
            // Both switches off: nobody's lists are asked for — not even hourly in the background.
            if (note == null) return@work
            val last = _state.value.read?.at
            if (force || last == null || nowSeconds() - last >= minOf(maxAge, readEvery)) readNow() else if (maxAge < readEvery) openWaiting()
            renewIfLost(interactive)
            shareNow()
        }
    }

    /** When the lists that did not open were last looked for again. */
    private var waitingLookedAt = 0L

    /**
     * Mutual contacts who share, but whose list did not open at the last read — no key for this inbox
     * yet: looked for again, at most once a minute, without reading everything again.
     */
    private suspend fun openWaiting() {
        val read = _state.value.read ?: return
        val inbox = keys.inboxKey()?.takeIf { _state.value.note?.seeing == true } ?: return
        val waiting = read.mutual.filter { it.share != null && it.shows.isEmpty() }
        if (waiting.isEmpty() || nowSeconds() - waitingLookedAt < WAITING_EVERY) return
        waitingLookedAt = nowSeconds()
        val opened = graph.shelvesAgain(waiting, inbox).filterValues { it.isNotEmpty() }
        if (opened.isEmpty()) return
        val named = runCatching { graph.profiles(opened.keys.filter { read.contacts[it]?.name == null }.toSet()) }.getOrDefault(emptyMap())
        val updated = read.copy(contacts = read.contacts.mapValues { (who, c) ->
            opened[who]?.let { shows -> c.copy(shows = shows, name = c.name ?: named[who]?.first, picture = c.picture ?: named[who]?.second) } ?: c
        })
        _state.update { it.copy(read = updated) }
        runCatching { cache?.save(updated) }
    }

    /** The contacts read now — with the inbox key while the listener looks — and kept. */
    private suspend fun readNow() {
        val note = _state.value.note
        val read = graph.read(if (note?.seeing == true) keys.inboxKey() else null)?.let { lookedUp(it, _state.value.read) }
        _state.update { it.copy(read = read) }
        runCatching { cache?.save(read) }
    }

    /** Inboxes found lost in the read before: one lost twice in a row makes a new share key (one relay
     *  down for a read is no reason for it). */
    private var lostBefore: Set<String> = emptySet()

    /**
     * A mutual contact lost — either side unfollowed, or their note no longer names that inbox (ADR 0024,
     * 4): a new share key, so what changes from now on is sealed under a key they do not have; what the
     * old one signed goes. The signer is asked only with the listener there ([interactive]) or where the
     * key is Hopp's own; otherwise the Nostr page offers it ([ContactsState.renewPending]).
     */
    private suspend fun renewIfLost(interactive: Boolean) {
        val note = _state.value.note ?: return
        val read = _state.value.read ?: return
        val sharer = share ?: return
        if (!note.sharing || !note.opened) return
        val current = read.withHopp.mapNotNull { it.inbox }.toSet()
        val lost = sharer.sentTo() - current
        val confirmed = lost intersect lostBefore
        lostBefore = lost
        if (confirmed.isEmpty() && !_state.value.renewPending) return
        val old = keys.shareKey() ?: return
        try {
            _state.update { it.copy(note = keys.set(share = true, see = note.seeing, newShare = true, interactive = interactive), renewPending = false) }
            lostBefore = emptySet()
            sharer.retire(old)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(renewPending = true) }
        }
    }

    /** Shows known only from public signals, looked up — at most [LOOKUPS] of them, the ones looked up before kept. */
    private suspend fun lookedUp(read: ContactsRead, before: ContactsRead?): ContactsRead {
        val find = lookup ?: return read
        val shared = read.contacts.values.flatMap { c -> c.shows.mapNotNull { it.guid?.lowercase() } }.toSet()
        val wanted = read.signalShows().filter { it !in shared }.take(LOOKUPS)
        val kept = before?.looked.orEmpty()
        val looked = wanted.mapNotNull { guid -> (kept[guid] ?: runCatching { find(guid) }.getOrNull())?.let { guid to it } }.toMap()
        return read.copy(looked = looked)
    }

    /** Sends the shared list again when [subscriptions] change — at most every [pause] (ADR 0024, 4). */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, subscriptions: Flow<*>, pause: Duration = 5.minutes) {
        scope.launch { subscriptions.drop(1).debounce(pause).collect { lock.withLock { work { shareNow() } } } }
    }

    /** What changed of the shared list and the keys for mutual contacts who look, sent — only while sharing. */
    private suspend fun shareNow() {
        val note = _state.value.note ?: return
        if (!note.sharing || !note.opened) return
        val inboxes = _state.value.read?.withHopp.orEmpty().mapNotNull { it.inbox }.toSet()
        share?.publish(inboxes)
    }

    /** Sharing with mutual contacts on or off; the listener's signer is asked. */
    suspend fun setSharing(on: Boolean) = change(share = on, see = _state.value.note?.seeing == true)

    /** Looking at what contacts listen to on or off; the listener's signer is asked. */
    suspend fun setSeeing(on: Boolean) = change(share = _state.value.note?.sharing == true, see = on)

    /** Signed out: nothing of it stays on this device. */
    suspend fun forget() { keys.forget(); _state.value = ContactsState(); runCatching { cache?.save(null) } }

    private suspend fun change(share: Boolean, see: Boolean) = lock.withLock {
        work {
            val before = keys.shareKey()
            _state.update { it.copy(note = keys.set(share, see)) }
            // Sharing off, or a new share key: what the old one signed goes from the relays.
            if (before != null && keys.shareKey()?.publicKeyHex != before.publicKeyHex) this.share?.retire(before)
            // Read again at once: looking now opens what contacts share; sharing now needs their inboxes
            // (device feedback 2026-11-02: turned on, nothing showed until the hour was up).
            readNow()
            shareNow()
        }
    }

    private companion object {
        /** Shows looked up in the directory per read, at most. */
        const val LOOKUPS = 12
        /** Seconds between looks for lists that did not open. */
        const val WAITING_EVERY = 60L
    }

    private suspend fun work(block: suspend () -> Unit) {
        _state.update { it.copy(working = true, error = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e::class.simpleName ?: "error") }
        } finally {
            _state.update { it.copy(working = false) }
        }
    }
}
