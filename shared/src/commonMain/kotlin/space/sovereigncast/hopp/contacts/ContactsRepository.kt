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
            if (force || last == null || nowSeconds() - last >= minOf(maxAge, readEvery)) readNow() else if (maxAge < readEvery) lookAgain()
            renewIfLost(interactive)
            shareNow()
        }
    }

    /** When the shared lists were last looked at again. */
    private var lookedAgainAt = 0L

    /**
     * The lists of mutual contacts who share, looked at again between full reads — at most every
     * [LOOK_AGAIN_EVERY] seconds, one request per contact: a list that changed (a show subscribed) and
     * one that did not open before (its key came since) show without waiting for the next full read
     * (device feedback 2026-11-02). A list that does not come now keeps what was there: a relay that did
     * not answer is no list emptied — one turned off shows in the contact's note, at the next full read.
     * A contact shown without a name is asked for again too: only their npub is no way to show them
     * (device feedback 2026-11-05).
     */
    private suspend fun lookAgain() {
        val read = _state.value.read ?: return
        val inbox = keys.inboxKey()?.takeIf { _state.value.note?.seeing == true } ?: return
        val sharing = read.mutual.filter { it.share != null }
        val unnamed = read.contacts.values.filter { it.name == null && (it.shows.isNotEmpty() || it.signals.isNotEmpty()) }.map { it.pubkey }
        if ((sharing.isEmpty() && unnamed.isEmpty()) || nowSeconds() - lookedAgainAt < LOOK_AGAIN_EVERY) return
        lookedAgainAt = nowSeconds()
        val opened = graph.shelvesAgain(sharing, inbox).filterValues { it.isNotEmpty() }.filter { (who, shows) -> read.contacts[who]?.shows != shows }
        val named = runCatching { graph.profiles((unnamed + opened.keys.filter { read.contacts[it]?.name == null }).toSet()) }.getOrDefault(emptyMap())
        if (opened.isEmpty() && named.isEmpty()) return
        val updated = read.copy(contacts = read.contacts.mapValues { (who, c) ->
            c.copy(shows = opened[who] ?: c.shows, name = c.name ?: named[who]?.first, picture = c.picture ?: named[who]?.second)
        })
        _state.update { it.copy(read = updated) }
        runCatching { cache?.save(updated) }
    }

    /** The contacts read now — with the inbox key while the listener looks — and kept. */
    private suspend fun readNow() {
        val note = _state.value.note
        val before = _state.value.read
        val read = graph.read(if (note?.seeing == true) keys.inboxKey() else null)?.let { lookedUp(it, before) }?.keepingNames(before)
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
        /** Seconds between looks at the shared lists, between full reads. */
        const val LOOK_AGAIN_EVERY = 120L
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
