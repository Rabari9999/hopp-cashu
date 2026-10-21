package space.sovereigncast.hopp.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.LocalSigner
import space.sovereigncast.hopp.nostr.Nip44Exception
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.NostrSigner
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.SignerUnavailable
import space.sovereigncast.hopp.nostr.UnsignedEvent
import space.sovereigncast.hopp.platform.SecretStore
import kotlin.time.Duration.Companion.minutes

/** The relays did not say clearly whether a sync key exists — none is made in its place. */
class SyncKeyringNotKnown : Exception("not every relay answered whether a sync key exists")

/** The listener's keyring is there but does not open or read — it is never replaced. */
class SyncKeyringUnreadable(cause: Throwable? = null) : Exception("the sync keyring does not open", cause)

/**
 * The sync key (ADR 0021, 5, amended 2026-10-21): a key pair of its own that
 * signs and seals the sync documents, so the listener's signer is not asked
 * for every round — and is never asked to open things in the background.
 *
 * It travels once as the **keyring**: a kind-30078 event by the listener,
 * `d` = `hopp/keyring`, its content NIP-44-sealed to the listener's own key.
 * A device opens it once — the one moment the signer is asked — and keeps the
 * key in its secret store. A signer then needs no standing permission to
 * decrypt, which would open everything ever encrypted to the listener (NIP-17
 * messages included) to whoever holds a session: the web client's case.
 *
 * Only the first device makes a keyring, and only when its relays answer
 * that there is none ([SyncKeyringNotKnown] otherwise): a new one over an old
 * one that was only out of reach would leave the library of a lost phone on
 * the relays for good, under a key no one has any more. An unreadable keyring
 * is never replaced either ([SyncKeyringUnreadable]).
 */
class SyncKeys(
    private val identity: IdentityRepository,
    private val publisher: RelayPublisher,
    private val secrets: SecretStore,
    private val nowMs: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    /** When this device last saw that its key is still the keyring's; in memory, so every start looks once. */
    private var checkedAtMs: Long? = null

    /** A device's copy of the key: whose it is, the key, and which keyring event it came from. */
    @Serializable
    private data class Held(val owner: String, val key: String, val keyring: String)

    @Serializable
    private data class Keyring(val v: Int = VERSION, val key: String)

    /**
     * The signer for the sync documents; null without a Nostr identity — or,
     * without [create], when the listener has no keyring yet. [interactive]:
     * the listener's signer may ask them (it is needed only to open a keyring
     * this device does not hold yet, or to make the first).
     */
    suspend fun signer(interactive: Boolean, create: Boolean = true): NostrSigner? = lock.withLock {
        val me = identity.identity.value ?: return null
        val held = held(me.pubkey)
        val now = nowMs()
        if (held != null && checkedAtMs?.let { now - it < CHECK_EVERY.inWholeMilliseconds } == true) return LocalSigner(KeyPair.fromHex(held.key))
        val urls = identity.writeRelays()
        val found = publisher.queryChecked(listOf(filter(me.pubkey)), urls)
        val newest = found.events.filter { it.pubkey == me.pubkey && it.kind == Nip78.KIND && it.dTag() == DTAG }
            .sortedWith(compareByDescending<Event> { it.created_at }.thenBy { it.id }).firstOrNull()
        val key = when {
            newest != null && held != null && newest.id == held.keyring -> held.key
            newest != null -> open(newest, interactive).also { keep(me.pubkey, it, newest.id) }
            // None found, but this device has the key: a relay that lost it is no reason to change it.
            held != null -> held.key
            !create -> return null
            // A keyring goes to every write relay: none on at least half of them, read to the end, is none.
            found.answered == 0 || found.answered * 2 < found.asked -> throw SyncKeyringNotKnown()
            else -> make(me.pubkey, interactive, urls)
        }
        checkedAtMs = now
        LocalSigner(KeyPair.fromHex(key))
    }

    /** This device forgets the key — sync turned off, or signed out. The keyring stays on the relays. */
    fun forget() {
        secrets.put(SECRET, null)
        checkedAtMs = null
    }

    private fun held(owner: String): Held? =
        secrets.get(SECRET)?.let { runCatching { json.decodeFromString(Held.serializer(), it) }.getOrNull() }?.takeIf { it.owner == owner }

    private fun keep(owner: String, key: String, keyring: String) =
        secrets.put(SECRET, json.encodeToString(Held.serializer(), Held(owner, key, keyring)))

    private suspend fun open(event: Event, interactive: Boolean): String {
        val signer = identity.signer(interactive) ?: throw SignerUnavailable("no signer for the listener's key")
        val text = try { signer.decryptFromSelf(event.content) } catch (e: Nip44Exception) { throw SyncKeyringUnreadable(e) }
        val ring = try { json.decodeFromString(Keyring.serializer(), text) } catch (e: SerializationException) { throw SyncKeyringUnreadable(e) } catch (e: IllegalArgumentException) { throw SyncKeyringUnreadable(e) }
        if (ring.v > VERSION) throw SyncNewerVersion(ring.v)
        runCatching { KeyPair.fromHex(ring.key) }.getOrElse { throw SyncKeyringUnreadable(it) }
        return ring.key
    }

    /** The first device: a new key, sealed to the listener and sent; then read back, in
     *  case another device made one at the same moment — the one the relays keep wins. */
    private suspend fun make(owner: String, interactive: Boolean, urls: List<String>): String {
        val signer = identity.signer(interactive) ?: throw SignerUnavailable("no signer for the listener's key")
        val key = KeyPair.generate().secretKey.toHex()
        val content = signer.encryptToSelf(json.encodeToString(Keyring.serializer(), Keyring(VERSION, key)))
        val event = signer.sign(UnsignedEvent(Nip78.KIND, content, listOf(listOf("d", DTAG)), nowMs() / 1000))
        if (publisher.publish(event, urls).isEmpty()) throw SyncNotPublished()
        keep(owner, key, event.id)
        val winner = publisher.query(listOf(filter(owner)), urls).filter { it.pubkey == owner && it.dTag() == DTAG }
            .sortedWith(compareByDescending<Event> { it.created_at }.thenBy { it.id }).firstOrNull()
        if (winner == null || winner.id == event.id) return key
        return open(winner, interactive).also { keep(owner, it, winner.id) }
    }

    private fun filter(owner: String) = buildJsonObject {
        putJsonArray("kinds") { add(JsonPrimitive(Nip78.KIND)) }
        putJsonArray("authors") { add(JsonPrimitive(owner)) }
        putJsonArray("#d") { add(JsonPrimitive(DTAG)) }
    }

    private fun Event.dTag() = tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)

    companion object {
        const val VERSION = 1
        const val DTAG = "hopp/keyring"
        private const val SECRET = "nostr_sync_key"
        /** How often a device looks whether the keyring changed (another device's, made at the same moment). */
        val CHECK_EVERY = 1.minutes
    }
}
