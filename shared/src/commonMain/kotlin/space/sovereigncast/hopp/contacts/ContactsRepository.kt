package space.sovereigncast.hopp.contacts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import space.sovereigncast.hopp.nostr.withRelayPool
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.identity.IdentityRepository

/** What the Nostr page shows of the feature (ADR 0024). */
data class ContactsState(
    /** The listener's contacts note; null: both switches off. */
    val note: ContactKeys.Note? = null,
    /** A mutual contact was lost, and the signer has to be asked for a new share key: on the Nostr page. */
    val renewPending: Boolean = false,
    /** The contacts, as last read; null until then. */
    val read: ContactsRead? = null,
    /** A switch being turned, or the keys taken over: the switches wait for it. */
    val working: Boolean = false,
    /** A read under way, and what it found so far; null: none. The switches do not wait for it (maintainer 2026-11-08). */
    val reading: ReadProgress? = null,
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
    /** Where reading the contacts or sending the list goes wrong is shown (maintainer 2026-11-10); null: nowhere. */
    private val activity: space.sovereigncast.hopp.nostr.NostrActivity? = null,
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
     * [interactive]: a note made on another device is taken over, the signer asked; with a key Hopp holds
     * it is taken over without asking anyone. A device holding none of its keys reads nothing until then:
     * such a read opens nothing, and it kept the page's "Take over" hidden for a minute (maintainer 2026-11-08).
     */
    suspend fun refresh(force: Boolean = false, interactive: Boolean = false, maxAge: Long = readEvery) {
        // Said at once, while an earlier read still holds the lock: the page shows the signer is being asked.
        if (interactive) _state.update { it.copy(working = true) }
        try {
            lock.withLock { refreshLocked(force, interactive, maxAge) }
        } finally {
            if (interactive) _state.update { it.copy(working = false) }
        }
    }

    private suspend fun refreshLocked(force: Boolean, interactive: Boolean, maxAge: Long) {
        if (identity.identity.value == null) { _state.value = ContactsState(); return }
        // What was read before this start first: a refresh right after it is then no new read.
        loadKept()
        withRelayPool {
            work {
                val note = keys.note(interactive, takeOver = interactive || identity.identity.value?.holdsKey == true)
                _state.update { it.copy(note = note, working = false) }
                // Both switches off: nobody's lists are asked for — not even hourly in the background.
                if (note == null) return@work
                // Turned on on another device, and none of its keys here: nothing to read with yet.
                if (!note.opened && keys.inboxKey() == null && keys.shareKey() == null) return@work
                val last = _state.value.read?.at
                if (force || last == null || nowSeconds() - last >= minOf(maxAge, readEvery)) readNow() else if (maxAge < readEvery) lookAgain()
                renewIfLost(interactive)
                shareNow()
            }
        }
    }

    /**
     * [refresh] for a page that opens: it goes on when the page closes. A full read takes a minute with a few
     * hundred contacts and is kept only at its end; left with the page, the next visit began it anew
     * (device feedback 2026-11-05). Asks no signer, and every relay is asked with a time limit, so it ends.
     */
    suspend fun readToTheEnd(maxAge: Long) = withContext(NonCancellable) { refresh(maxAge = maxAge) }

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
        val again = graph.shelvesAgain(sharing, inbox)
        val opened = again.mapNotNull { (who, shows) -> shows?.takeIf { it.isNotEmpty() }?.let { who to it } }.toMap()
            .filter { (who, shows) -> read.contacts[who]?.shows != shows }
        // A list that opens now is no longer waited for.
        val unlocked = again.filterValues { it != null }.keys.filter { read.contacts[it]?.locked == true }
        val named = runCatching { graph.profiles((unnamed + opened.keys.filter { read.contacts[it]?.name == null }).toSet()) }.getOrDefault(emptyMap())
        if (opened.isEmpty() && named.isEmpty() && unlocked.isEmpty()) return
        val updated = read.copy(contacts = read.contacts.mapValues { (who, c) ->
            c.copy(shows = opened[who] ?: c.shows, name = c.name ?: named[who]?.first, picture = c.picture ?: named[who]?.second,
                locked = c.locked && who !in unlocked)
        })
        _state.update { it.copy(read = updated) }
        runCatching { cache?.save(updated) }
    }

    /** The contacts read now — with the inbox key while the listener looks — and kept; what was found so far shown as it comes. */
    private suspend fun readNow() {
        val note = _state.value.note
        val before = _state.value.read
        try {
            val inbox = if (note?.seeing == true) keys.inboxKey() else null
            val read = tracked(space.sovereigncast.hopp.nostr.NostrActivity.Area.Contacts, core = coreRelays()) {
                graph.read(inbox) { found -> _state.update { it.copy(reading = found) } }
            }?.let { lookedUp(it, before) }?.keepingNames(before)
            _state.update { it.copy(read = read, reading = null) }
            runCatching { cache?.save(read) }
        } finally {
            _state.update { it.copy(reading = null) }
        }
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
        // A few at once: one after the other, twelve took as long as the rest of the read (maintainer 2026-11-08).
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        val looked = coroutineScope {
            wanted.map { guid -> async { gate.withPermit { (kept[guid] ?: runCatching { find(guid) }.getOrNull())?.let { guid to it } } } }.awaitAll()
        }.filterNotNull().toMap()
        return read.copy(looked = looked)
    }

    /** Sends the shared list again when [subscriptions] change — at most every [pause] (ADR 0024, 4). */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, subscriptions: Flow<*>, pause: Duration = 5.minutes) {
        scope.launch {
            subscriptions.drop(1).debounce(pause).collect {
                lock.withLock {
                    withRelayPool {
                        work {
                            // The list goes to the inboxes the last read found: read first when that is due — a contact
                            // followed since got no key, and saw a part of what is shared (maintainer 2026-11-08).
                            val note = _state.value.note
                            val last = _state.value.read?.at
                            if (note != null && note.sharing && note.opened && (last == null || nowSeconds() - last >= readEvery)) readNow()
                            shareNow()
                        }
                    }
                }
            }
        }
    }

    /**
     * Hopp started or came back to the foreground, or looked for new episodes in the background: the
     * contacts read when the last read is [readEvery] old (ADR 0024, 5) — so a contact who turned on looking
     * gets the key to what is shared without the listener opening a page (maintainer 2026-11-08: only the
     * Nostr page, Discover and a pull read them, and a new contact waited hours). Goes on when the caller goes.
     */
    suspend fun whenDue() = withContext(NonCancellable) { refresh() }

    /** What changed of the shared list and the keys for mutual contacts who look, sent — only while sharing. */
    private suspend fun shareNow() {
        val note = _state.value.note ?: return
        if (!note.sharing || !note.opened) return
        val inboxes = _state.value.read?.withHopp.orEmpty().mapNotNull { it.inbox }.toSet()
        val share = share ?: return
        tracked(space.sovereigncast.hopp.nostr.NostrActivity.Area.Share, send = true) { share.publish(inboxes) }
    }

    /** The relays a read of the contacts stands on — the listener's, Hopp's and the indexes; the contacts' own count beside them. */
    private suspend fun coreRelays(): Set<String> =
        (identity.writeRelays() + IdentityRepository.DEFAULT_RELAYS + IdentityRepository.INDEX_RELAYS + IdentityRepository.SYNC_RELAYS).toSet()

    private suspend fun <T> tracked(area: space.sovereigncast.hopp.nostr.NostrActivity.Area, send: Boolean = false, core: Collection<String>? = null, block: suspend () -> T): T =
        activity.let { if (it == null) block() else it.track(area, send, core, block) }

    /** Sharing with mutual contacts on or off; the listener's signer is asked. */
    suspend fun setSharing(on: Boolean) = change(share = on, see = _state.value.note?.seeing == true)

    /** Looking at what contacts listen to on or off; the listener's signer is asked. */
    suspend fun setSeeing(on: Boolean) = change(share = _state.value.note?.sharing == true, see = on)

    /** Signed out: nothing of it stays on this device. */
    suspend fun forget() { keys.forget(); _state.value = ContactsState(); runCatching { cache?.save(null) } }

    private suspend fun change(share: Boolean, see: Boolean) {
        // Said at once: a read under way holds the lock until it ends.
        _state.update { it.copy(working = true) }
        try {
            lock.withLock { withRelayPool { changeInPool(share, see) } }
        } finally {
            _state.update { it.copy(working = false) }
        }
    }

    private suspend fun changeInPool(share: Boolean, see: Boolean) {
        work {
            val before = keys.shareKey()
            _state.update { it.copy(note = keys.set(share, see), working = false) }
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
        const val LOOKUPS_AT_ONCE = 4
        /** Seconds between looks at the shared lists, between full reads. */
        const val LOOK_AGAIN_EVERY = 120L
    }

    /** [block], what goes wrong said on the page; [ContactsState.working] is set by who asked for it. */
    private suspend fun work(block: suspend () -> Unit) {
        _state.update { it.copy(error = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e::class.simpleName ?: "error") }
        }
    }
}
