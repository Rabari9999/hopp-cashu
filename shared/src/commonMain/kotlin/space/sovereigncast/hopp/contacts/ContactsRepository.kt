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
    private val readEvery: Long = 3600,
) {
    private val _state = MutableStateFlow(ContactsState())
    val state: StateFlow<ContactsState> = _state.asStateFlow()
    private val lock = Mutex()

    /** The note and, when due or [force]d, the contacts. [interactive]: a note made on another device is taken over. */
    suspend fun refresh(force: Boolean = false, interactive: Boolean = false) = lock.withLock {
        if (identity.identity.value == null) { _state.value = ContactsState(); return@withLock }
        work {
            val note = keys.note(interactive)
            _state.update { it.copy(note = note) }
            val last = _state.value.read?.at
            if (force || last == null || nowSeconds() - last >= readEvery) {
                val read = graph.read()
                _state.update { it.copy(read = read) }
            }
            shareNow()
        }
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
    fun forget() { keys.forget(); _state.value = ContactsState() }

    private suspend fun change(share: Boolean, see: Boolean) = lock.withLock {
        work {
            val before = keys.shareKey()
            _state.update { it.copy(note = keys.set(share, see)) }
            // Sharing off, or a new share key: what the old one signed goes from the relays.
            if (before != null && keys.shareKey()?.publicKeyHex != before.publicKeyHex) this.share?.retire(before)
            shareNow()
        }
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
