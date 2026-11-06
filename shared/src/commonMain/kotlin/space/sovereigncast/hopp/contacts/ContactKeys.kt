package space.sovereigncast.hopp.contacts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.SignerUnavailable
import space.sovereigncast.hopp.nostr.UnsignedEvent
import space.sovereigncast.hopp.platform.SecretStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Nothing reached enough relays: the contacts note did not go out, and nothing changed here. */
class ContactsNoteNotPublished(val took: Int, val asked: Int) : Exception("$took of $asked relays took the contacts note")

/**
 * The listener's **contacts note** (ADR 0024, 3): a kind-30078 event by the listener,
 * `d` = `hopp/contacts`, that names in the open the public halves of two keys of its own —
 * `share` while the listener shares their subscriptions with mutual Nostr contacts, `inbox`
 * while they look at what contacts share — and holds the private halves sealed to the
 * listener's own key, so another device takes them over with one question to the signer.
 *
 * Public: that the listener uses this feature of Hopp's, and with which keys; never what they
 * subscribe to. Changed only with the listener there ([set]); the background reads it, never
 * signs. Goes to the listener's write relays and Hopp's for sync ([IdentityRepository.syncRelays]),
 * where contacts look for it.
 */
class ContactKeys(
    private val identity: IdentityRepository,
    private val publisher: RelayPublisher,
    private val secrets: SecretStore,
    private val nowSeconds: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** What this device holds of the note: whose, the private keys, and the note they came from. */
    @Serializable
    private data class Held(val owner: String, val share: String? = null, val inbox: String? = null, val note: String, val at: Long)

    @Serializable
    private data class Sealed(val v: Int = VERSION, val share: String? = null, val inbox: String? = null)

    /** The note as the relays show it — the public halves in its tags; [opened] when this device holds its keys. */
    data class Note(val share: String?, val inbox: String?, val at: Long, val opened: Boolean) {
        val sharing: Boolean get() = share != null
        val seeing: Boolean get() = inbox != null
    }

    /** The share key, while the listener shares and this device holds it. */
    fun shareKey(): KeyPair? = held()?.share?.let(KeyPair::fromHex)

    /** The inbox key, while the listener looks at what contacts share and this device holds it. */
    fun inboxKey(): KeyPair? = held()?.inbox?.let(KeyPair::fromHex)

    /**
     * The listener's note on the relays, and whether this device holds its keys — taking them
     * over with [interactive] (the signer opens it once). Null without an identity, or with no
     * note anywhere: the feature is off.
     */
    suspend fun note(interactive: Boolean = false): Note? = lock.withLock {
        val me = identity.identity.value ?: return null
        // Looked at on every refresh: not waited for to the slowest relay (maintainer 2026-11-04); a newer
        // note a slow one has is seen at a later look.
        val newest = newestOnRelays(me.pubkey, if (interactive) null else NOTE_SETTLE)
        val held = held()?.takeIf { it.owner == me.pubkey }
        if (newest == null) return held?.let { Note(it.share?.pub(), it.inbox?.pub(), it.at, opened = true) }
        if (held != null && (held.note == newest.id || held.at >= newest.created_at)) return Note(held.share?.pub(), held.inbox?.pub(), held.at, opened = true)
        val share = newest.tag("share")
        val inbox = newest.tag("inbox")
        if (newest.content.isEmpty() || (share == null && inbox == null)) {
            // Turned off on another device: nothing to hold any more.
            secrets.put(SECRET, null)
            return null
        }
        if (!interactive) return Note(share, inbox, newest.created_at, opened = false)
        val opened = open(newest, interactive = true)
        keep(Held(me.pubkey, opened.share, opened.inbox, newest.id, newest.created_at))
        Note(opened.share?.pub(), opened.inbox?.pub(), newest.created_at, opened = true)
    }

    /**
     * Sharing and seeing as the listener chose them (ADR 0024, 8): a key kept while its part
     * stays on, a new one for a part turned on, none for a part off — and the note published
     * again. Both off: the note emptied and deleted (NIP-09). The listener's signer is asked.
     */
    suspend fun set(share: Boolean, see: Boolean, newShare: Boolean = false, interactive: Boolean = true): Note? = lock.withLock {
        val me = identity.identity.value ?: throw SignerUnavailable("no identity")
        val signer = identity.signer(interactive) ?: throw SignerUnavailable("no signer for the listener's key")
        val newest = newestOnRelays(me.pubkey)
        val mine = held()?.takeIf { it.owner == me.pubkey }
        // A newer note than this device's goes first: what this device still holds would replace the keys the others use.
        val current = when {
            newest == null || (mine != null && (mine.note == newest.id || mine.at >= newest.created_at)) -> mine
            newest.content.isEmpty() -> null
            else -> open(newest, interactive).let { Held(me.pubkey, it.share, it.inbox, newest.id, newest.created_at) }
        }
        val next = Sealed(
            // [newShare]: a mutual contact lost — what is shared from now on, under a key they do not have (ADR 0024, 4).
            share = if (share) current?.share?.takeUnless { newShare } ?: newKey() else null,
            inbox = if (see) current?.inbox ?: newKey() else null,
        )
        // Later than any note before, or relays could keep the old one (NIP-01: same second, lower id).
        val at = maxOf(nowSeconds(), (newest?.created_at ?: 0) + 1, (current?.at ?: 0) + 1)
        val urls = identity.syncRelays()
        if (!share && !see) {
            val empty = signer.sign(UnsignedEvent(Nip78.KIND, "", listOf(listOf("d", DTAG)), at))
            val took = publisher.publish(empty, urls)
            if (took.isEmpty()) throw ContactsNoteNotPublished(0, urls.size)
            publisher.publish(signer.sign(UnsignedEvent(DELETION, "", listOf(listOf("a", "${Nip78.KIND}:${me.pubkey}:$DTAG"), listOf("k", "${Nip78.KIND}")), at)), urls)
            secrets.put(SECRET, null)
            return null
        }
        val tags = buildList {
            add(listOf("d", DTAG))
            next.share?.let { add(listOf("share", it.pub())) }
            next.inbox?.let { add(listOf("inbox", it.pub())) }
            add(listOf("alt", "Hopp: what this listener shares with mutual Nostr contacts is sealed with these keys"))
        }
        val event = signer.sign(UnsignedEvent(Nip78.KIND, signer.encryptToSelf(json.encodeToString(Sealed.serializer(), next)), tags, at))
        val took = publisher.publish(event, urls)
        if (took.isEmpty()) throw ContactsNoteNotPublished(0, urls.size)
        keep(Held(me.pubkey, next.share, next.inbox, event.id, event.created_at))
        Note(next.share?.pub(), next.inbox?.pub(), event.created_at, opened = true)
    }

    /** This device forgets the keys — signed out. The note stays on the relays. */
    fun forget() = secrets.put(SECRET, null)

    private suspend fun newestOnRelays(owner: String, settle: Duration? = null): Event? {
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(Nip78.KIND)) }
            putJsonArray("authors") { add(JsonPrimitive(owner)) }
            putJsonArray("#d") { add(JsonPrimitive(DTAG)) }
            put("limit", 4)
        }
        return publisher.query(listOf(filter), identity.syncRelays(), settle)
            .filter { it.pubkey == owner && it.kind == Nip78.KIND && it.tag("d") == DTAG }
            .sortedWith(compareByDescending<Event> { it.created_at }.thenBy { it.id }).firstOrNull()
    }

    private suspend fun open(note: Event, interactive: Boolean): Sealed {
        val signer = identity.signer(interactive) ?: throw SignerUnavailable("no signer for the listener's key")
        return json.decodeFromString(Sealed.serializer(), signer.decryptFromSelf(note.content))
    }

    private fun held(): Held? = runCatching { secrets.get(SECRET) }.getOrNull()?.let { runCatching { json.decodeFromString(Held.serializer(), it) }.getOrNull() }

    private fun keep(h: Held) = secrets.put(SECRET, json.encodeToString(Held.serializer(), h))

    private fun newKey(): String = KeyPair.generate().secretKey.toHex()

    private fun String.pub(): String = KeyPair.fromHex(this).publicKeyHex

    companion object {
        const val DTAG = "hopp/contacts"
        private const val SECRET = "contacts_keys"
        private const val VERSION = 1
        private const val DELETION = 5
        /** How long the other relays get once one answered, for the note looked at on every refresh. */
        private val NOTE_SETTLE = 3.seconds
    }
}
