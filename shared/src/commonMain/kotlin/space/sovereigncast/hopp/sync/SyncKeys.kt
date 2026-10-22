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

    /** Whether this run already sent its keyring to the relays again. */
    private var resent = false

    /**
     * A device's copy of the key: whose it is, the key, which keyring event it
     * came from, that event's time — an older keyring is never taken over it
     * (security audit 2026-10-22, M-01) — and the event itself, to send again
     * to relays that lost it (H-01). Older copies have neither: 0 and null.
     */
    @Serializable
    private data class Held(val owner: String, val key: String, val keyring: String, val at: Long = 0, val event: String? = null)

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
            newest != null && held != null && newest.id == held.keyring -> {
                if (held.event == null) keep(me.pubkey, held.key, newest)
                resend(held.event?.let(::parse) ?: newest, urls)
                held.key
            }
            // Older than the one this device holds — a relay that lost the newer one: not taken (M-01).
            newest != null && held != null && !newest.isNewerThan(held) -> { held.event?.let(::parse)?.let { resend(it, urls) }; held.key }
            newest != null -> open(newest, interactive).also { keep(me.pubkey, it, newest) }.also { new ->
                // Moving off a key — renewed elsewhere, or two made at once: what it sealed goes.
                if (held != null && held.key != new) runCatching { retire(KeyPair.fromHex(held.key), urls) }
            }
            // None found, but this device has the key: a relay that lost it is no reason to change it — it gets it again.
            held != null -> { held.event?.let(::parse)?.let { resend(it, urls) }; held.key }
            !create -> return null
            // Made only when every write relay answered to the end without one (audit 2026-10-22, H-01): a keyring
            // on a single relay that is down for the moment must not be replaced — the library under it would be lost.
            found.asked == 0 || found.answered < found.asked -> throw SyncKeyringNotKnown()
            else -> make(me.pubkey, interactive, urls)
        }
        checkedAtMs = now
        LocalSigner(KeyPair.fromHex(key))
    }

    /**
     * A new sync key in a new keyring, for a lost device or a browser session
     * the listener no longer trusts (ADR 0022, 3): what the old key holds opens
     * nothing that comes after. The listener's signer is asked, as for the
     * first keyring. The old key is returned for [retire] once the documents
     * are out under the new one. Throws when nothing changed — no keyring known
     * here, the signer said no, or no relay took the new one.
     */
    suspend fun renew(): KeyPair = lock.withLock {
        val me = identity.identity.value ?: throw SignerUnavailable("no identity")
        val held = held(me.pubkey) ?: throw SyncKeyringNotKnown()
        val urls = identity.writeRelays()
        // Later than the keyring it replaces, or relays could keep the old one (NIP-01: same second, lower id).
        val current = publisher.query(listOf(filter(me.pubkey)), urls).filter { it.pubkey == me.pubkey && it.kind == Nip78.KIND && it.dTag() == DTAG }
            .maxOfOrNull { it.created_at }.let { maxOf(it ?: 0, held.at) }
        val signer = identity.signer(interactive = true) ?: throw SignerUnavailable("no signer for the listener's key")
        val key = KeyPair.generate().secretKey.toHex()
        val content = signer.encryptToSelf(json.encodeToString(Keyring.serializer(), Keyring(VERSION, key)))
        val event = signer.sign(UnsignedEvent(Nip78.KIND, content, listOf(listOf("d", DTAG)), maxOf(nowMs() / 1000, current + 1)))
        if (publisher.publish(event, urls).isEmpty()) throw SyncNotPublished()
        keep(me.pubkey, key, event)
        checkedAtMs = nowMs()
        KeyPair.fromHex(held.key)
    }

    /**
     * What [old] signed goes: every document replaced by an empty one — relays
     * keep only the newest of an address, NIP-09 or not — then a deletion
     * (NIP-09) of all of them. Signed with the old key itself; the listener's
     * signer is not needed. A device still on the old key may send its copy
     * once more before it sees the new keyring; it retires it as it moves.
     */
    suspend fun retire(old: KeyPair, urls: List<String>? = null) {
        val to = urls ?: identity.writeRelays()
        val signer = LocalSigner(old)
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(Nip78.KIND)) }
            putJsonArray("authors") { add(JsonPrimitive(old.publicKeyHex)) }
        }
        val latest = publisher.query(listOf(filter), to).filter { it.pubkey == old.publicKeyHex && it.kind == Nip78.KIND }
            .groupBy { it.dTag() }.filterKeys { it != null }.mapValues { (_, events) -> events.maxOf { it.created_at } }
        if (latest.isEmpty()) return
        val now = nowMs() / 1000
        var last = now
        for ((d, at) in latest) {
            val ts = maxOf(now, at + 1).also { last = maxOf(last, it) }
            publisher.publish(signer.sign(UnsignedEvent(Nip78.KIND, "", listOf(listOf("d", d!!)), ts)), to)
        }
        val addresses = latest.keys.map { listOf("a", "${Nip78.KIND}:${old.publicKeyHex}:$it") }
        publisher.publish(signer.sign(UnsignedEvent(DELETION, "sync key renewed", addresses + listOf(listOf("k", "${Nip78.KIND}")), last)), to)
    }

    /** This device forgets the key — sync turned off, or signed out. The keyring stays on the relays. */
    fun forget() {
        secrets.put(SECRET, null)
        checkedAtMs = null
        resent = false
    }

    private fun held(owner: String): Held? =
        secrets.get(SECRET)?.let { runCatching { json.decodeFromString(Held.serializer(), it) }.getOrNull() }?.takeIf { it.owner == owner }

    private fun keep(owner: String, key: String, keyring: Event) =
        secrets.put(SECRET, json.encodeToString(Held.serializer(), Held(owner, key, keyring.id, keyring.created_at, json.encodeToString(Event.serializer(), keyring))))

    private fun parse(text: String): Event? = runCatching { json.decodeFromString(Event.serializer(), text) }.getOrNull()?.takeIf { it.isValid() }

    /** NIP-01's order for one address: later, or the same second and the lower id. */
    private fun Event.isNewerThan(held: Held) = created_at > held.at || (created_at == held.at && id < held.keyring)

    /** The keyring once more to every write relay, once a run: one that lost it has it again (H-01). */
    private suspend fun resend(keyring: Event, urls: List<String>) {
        if (resent) return
        resent = true
        runCatching { publisher.publish(keyring, urls) }
    }

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
        keep(owner, key, event)
        // Read back with every check of a read (audit 2026-10-22, M-07): author, kind, address.
        val winner = publisher.query(listOf(filter(owner)), urls).filter { it.pubkey == owner && it.kind == Nip78.KIND && it.dTag() == DTAG }
            .sortedWith(compareByDescending<Event> { it.created_at }.thenBy { it.id }).firstOrNull()
        if (winner == null || winner.id == event.id) return key
        return open(winner, interactive).also { keep(owner, it, winner) }
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
        /** NIP-09. */
        private const val DELETION = 5
        private const val SECRET = "nostr_sync_key"
        /** How often a device looks whether the keyring changed (another device's, made at the same moment). */
        val CHECK_EVERY = 1.minutes
    }
}
