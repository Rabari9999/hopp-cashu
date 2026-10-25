package space.sovereigncast.hopp.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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

/** Too few relays ([answered] of [asked]) said whether a sync key exists — none is made in its place. */
class SyncKeyringNotKnown(val answered: Int = 0, val asked: Int = 0) : Exception("$answered of $asked relays answered whether a sync key exists")

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
 * Only the first device makes a keyring, and only when a majority of its
 * relays answer to the end that there is none ([SyncKeyringNotKnown]
 * otherwise): a new one over an old one that was only out of reach would
 * leave the library of a lost phone on the relays for good, under a key no
 * one has any more. A keyring counts as made only once a majority took it —
 * so any majority that answers holds it (delta audit 2026-10-22, MN-08: all
 * relays were asked before, and one down stopped every first device). Until
 * then the device keeps it — from before it goes out — and sends it again,
 * never makes a second one, and seals nothing with it (delta audit 2). An
 * unreadable keyring is never replaced either ([SyncKeyringUnreadable]).
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

    /** Whether this run already got its keyring to a majority of the relays again. */
    private var resent = false

    /** Changes whenever this device moves to another key ([SyncEngine.keyGeneration]). */
    @kotlin.concurrent.Volatile
    var generation = 0
        private set

    /**
     * A device's copy of the key: whose it is, the key, which keyring event it
     * came from, that event's time — an older keyring is never taken over it
     * (security audit 2026-10-22, M-01) — and the event itself, to send again
     * to relays that lost it (H-01). Older copies have neither: 0 and null.
     * [committed]: a majority of the relays took the keyring. One this device
     * made is kept before it goes out, and nothing is sealed with it until a
     * majority has it (delta audit 2 2026-10-22, MN-08): a relay that kept it
     * without saying so must not lead to a second key, and a key on one relay
     * alone must not hold the library.
     * [majority]: [committed] was set under that rule. A copy from before it may
     * say committed of a keyring read from one relay alone (delta audit 4
     * 2026-10-23, MN-08): its keyring goes to a majority once more before
     * anything is sealed with it. One without its event cannot be sent; it
     * stays as it was (MN-03).
     */
    @Serializable
    private data class Held(val owner: String, val key: String, val keyring: String, val at: Long = 0, val event: String? = null, val committed: Boolean = true, val majority: Boolean = false) {
        val trusted get() = committed && (majority || event == null)
    }

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
        if (held != null && held.trusted && checkedAtMs?.let { now - it < CHECK_EVERY.inWholeMilliseconds } == true) return LocalSigner(KeyPair.fromHex(held.key))
        val urls = identity.writeRelays()
        val found = publisher.queryChecked(listOf(filter(me.pubkey)), urls)
        val newest = found.events.filter { it.pubkey == me.pubkey && it.kind == Nip78.KIND && it.dTag() == DTAG }
            .sortedWith(compareByDescending<Event> { it.created_at }.thenBy { it.id }).firstOrNull()
        val key = when {
            newest != null && held != null && newest.id == held.keyring -> {
                // A copy from before keyring events were kept: the relays' one taken in, and sent on like any not confirmed.
                if (held.event == null) keep(me.pubkey, held.key, newest, committed = false)
                confirm(me.pubkey, held(me.pubkey)!!, held.event?.let(::parse) ?: newest, urls)
                held.key
            }
            // A copy from before keyring times were kept, and another keyring on the relays: which is newer
            // cannot be told — even every relay answering may show an old one they all kept (delta audit
            // 2026-10-22 and 2, MN-03). The key stays; sync turned off and on again takes the relays' one.
            newest != null && held != null && held.event == null -> held.key
            // Older than the one this device holds — a relay that lost the newer one: not taken (M-01).
            newest != null && held != null && !newest.isNewerThan(held) -> { confirm(me.pubkey, held, held.event?.let(::parse), urls); held.key }
            newest != null -> open(newest, interactive).also { new ->
                // Read from the relays is not held by a majority: one relay may be all that has it. Not
                // committed until a majority took it from here too (delta audit 3 2026-10-22, MN-08/E01).
                keep(me.pubkey, new, newest, committed = false)
                // Moving off a key — renewed elsewhere, or two made at once: what it sealed goes once the
                // documents are read back under the new one (HN-06). One never committed sealed nothing.
                if (held != null && held.committed && held.key != new) addMoved(held.key)
                confirm(me.pubkey, held(me.pubkey)!!, newest, urls)
            }
            // None found, but this device has the key: a relay that lost it is no reason to change it — it gets it again.
            held != null -> { confirm(me.pubkey, held, held.event?.let(::parse), urls); held.key }
            !create -> return null
            // Made only when a majority of the write relays answered to the end without one (H-01, MN-08): a
            // keyring is out only once a majority took it, so a majority that answers would have shown it.
            found.asked == 0 || found.answered * 2 <= found.asked -> throw SyncKeyringNotKnown(found.answered, found.asked)
            else -> make(me.pubkey, interactive, urls)
        }
        checkedAtMs = now
        LocalSigner(KeyPair.fromHex(key))
    }

    /**
     * A new sync key in a new keyring, for a lost device or a browser session
     * the listener no longer trusts (ADR 0022, 3): what the old key holds opens
     * nothing that comes after. The listener's signer is asked, as for the
     * first keyring. What the old key sealed goes once a round under the new
     * one went through ([retireMoved]). Throws when nothing changed — no
     * keyring known here, or the signer said no; [SyncNotPublished] after the
     * change when too few relays said they took it — none included, as one may
     * keep it without saying so: this device holds the new key from then on,
     * sends it again, and seals nothing with it before a majority has it.
     */
    suspend fun renew(): Unit = lock.withLock {
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
        // Kept before it goes out: a relay may keep it without saying so (delta audit 3 2026-10-22, MN-08/E02).
        // From here this device is on the new key; it sends it again until a majority has it, and seals nothing
        // with it before (MN-08). The key first, then the old one to retire: never the key in use on that list.
        keep(me.pubkey, key, event, committed = false)
        addMoved(held.key)
        checkedAtMs = nowMs()
        val took = try { publisher.publish(event, urls) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptySet() }
        if (took.size * 2 <= urls.distinct().size) throw SyncNotPublished(took.size, urls.distinct().size)
        keep(me.pubkey, key, event, committed = true)
        resent = true
    }

    /**
     * The keys this device moved off, retired — called only once a round under the new key read
     * every document back complete. True: nothing left to retire. The list is kept with the key,
     * so a retirement cut off is taken up after a restart (delta audit 2 2026-10-22, ND-01).
     */
    suspend fun retireMoved(): Boolean {
        for (old in lock.withLock { moved() }) {
            if (!retire(KeyPair.fromHex(old))) return false
            lock.withLock { (moved() - old).let { left -> secrets.put(MOVED, if (left.isEmpty()) null else json.encodeToString(left)) } }
        }
        return true
    }

    /** Whether a key this device moved off still waits to be retired. */
    fun movedPending(): Boolean = moved().isNotEmpty()

    private fun moved(): List<String> = secrets.get(MOVED)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

    private fun addMoved(key: String) { if (key !in moved()) secrets.put(MOVED, json.encodeToString(moved() + key)) }

    /**
     * What [old] signed goes: every document replaced by an empty one — relays
     * keep only the newest of an address, NIP-09 or not — then a deletion
     * (NIP-09) of all of them. Signed with the old key itself; the listener's
     * signer is not needed. A device still on the old key may send its copy
     * once more before it sees the new keyring; it retires it as it moves.
     * True when a majority of the relays took every replacement and the deletion — and false when
     * too few answered to tell what the old key holds: a read that failed is not one that found
     * nothing (delta audit 2 2026-10-22, MN-10).
     */
    suspend fun retire(old: KeyPair, urls: List<String>? = null): Boolean {
        val to = urls ?: identity.writeRelays()
        val majority = { took: Set<String> -> took.size * 2 > to.distinct().size }
        val signer = LocalSigner(old)
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(Nip78.KIND)) }
            putJsonArray("authors") { add(JsonPrimitive(old.publicKeyHex)) }
        }
        val read = publisher.queryChecked(listOf(filter), to)
        if (read.asked == 0 || read.answered * 2 <= read.asked) return false
        val latest = read.events.filter { it.pubkey == old.publicKeyHex && it.kind == Nip78.KIND }
            .groupBy { it.dTag() }.filterKeys { it != null }.mapValues { (_, events) -> events.maxOf { it.created_at } }
        if (latest.isEmpty()) return true
        val now = nowMs() / 1000
        var last = now
        var all = true
        for ((d, at) in latest) {
            val ts = maxOf(now, at + 1).also { last = maxOf(last, it) }
            all = majority(publisher.publish(signer.sign(UnsignedEvent(Nip78.KIND, "", listOf(listOf("d", d!!)), ts)), to)) && all
        }
        val addresses = latest.keys.map { listOf("a", "${Nip78.KIND}:${old.publicKeyHex}:$it") }
        return majority(publisher.publish(signer.sign(UnsignedEvent(DELETION, "sync key renewed", addresses + listOf(listOf("k", "${Nip78.KIND}")), last)), to)) && all
    }

    /** This device forgets the key — sync turned off, or signed out. The keyring stays on the relays. */
    fun forget() {
        secrets.put(SECRET, null)
        secrets.put(MOVED, null)
        checkedAtMs = null
        resent = false
        generation++
    }

    private fun held(owner: String): Held? =
        secrets.get(SECRET)?.let { runCatching { json.decodeFromString(Held.serializer(), it) }.getOrNull() }?.takeIf { it.owner == owner }

    private fun keep(owner: String, key: String, keyring: Event, committed: Boolean) {
        val before = secrets.get(SECRET)?.let { runCatching { json.decodeFromString(Held.serializer(), it) }.getOrNull() }
        secrets.put(SECRET, json.encodeToString(Held.serializer(), Held(owner, key, keyring.id, keyring.created_at, json.encodeToString(Event.serializer(), keyring), committed, majority = true)))
        if (before?.key != key) generation++
    }

    /**
     * The held keyring on the relays: one a majority took once is only sent again to those
     * that lost it ([resend]); one not yet committed must reach a majority now — or the round
     * stops here ([SyncNotPublished]) and nothing is sealed with it (MN-08).
     */
    private suspend fun confirm(owner: String, held: Held, keyring: Event?, urls: List<String>) {
        if (held.trusted) { keyring?.let { resend(it, urls) }; return }
        val event = keyring ?: throw SyncKeyringUnreadable()
        val took = publisher.publish(event, urls)
        if (took.size * 2 <= urls.distinct().size) throw SyncNotPublished(took.size, urls.distinct().size)
        keep(owner, held.key, event, committed = true)
        resent = true
    }

    private fun parse(text: String): Event? = runCatching { json.decodeFromString(Event.serializer(), text) }.getOrNull()?.takeIf { it.isValid() }

    /** NIP-01's order for one address: later, or the same second and the lower id. */
    private fun Event.isNewerThan(held: Held) = created_at > held.at || (created_at == held.at && id < held.keyring)

    /** The keyring once more to every write relay, once a run: one that lost it has it again (H-01) —
     *  done only when a majority took it, tried again at the next check otherwise (delta audit 2026-10-22, MN-04). */
    private suspend fun resend(keyring: Event, urls: List<String>) {
        if (resent) return
        val took = try { publisher.publish(keyring, urls) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptySet() }
        resent = took.size * 2 > urls.distinct().size
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
        // Kept before it goes out, not yet committed: a relay may keep it without saying so, and a later
        // round sends this one again instead of making another (delta audit 2026-10-22 and 2, MN-08).
        keep(owner, key, event, committed = false)
        val took = publisher.publish(event, urls)
        if (took.size * 2 <= urls.distinct().size) throw SyncNotPublished(took.size, urls.distinct().size)
        keep(owner, key, event, committed = true)
        resent = true
        // Read back with every check of a read (audit 2026-10-22, M-07): author, kind, address.
        val winner = publisher.query(listOf(filter(owner)), urls).filter { it.pubkey == owner && it.kind == Nip78.KIND && it.dTag() == DTAG }
            .sortedWith(compareByDescending<Event> { it.created_at }.thenBy { it.id }).firstOrNull()
        if (winner == null || winner.id == event.id) return key
        // Another device's, made at the same moment: taken, and committed only once a majority has it (E01).
        return open(winner, interactive).also { keep(owner, it, winner, committed = false); confirm(owner, held(owner)!!, winner, urls) }
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
        /** The keys this device moved off and has not retired yet. */
        private const val MOVED = "nostr_sync_moved"
        /** How often a device looks whether the keyring changed (another device's, made at the same moment). */
        val CHECK_EVERY = 1.minutes
    }
}
