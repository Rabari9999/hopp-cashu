package space.sovereigncast.hopp.contacts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.LocalSigner
import space.sovereigncast.hopp.nostr.Nip44
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.UnsignedEvent
import space.sovereigncast.hopp.platform.SecretStore

/** One show as it is shared with mutual Nostr contacts (ADR 0024, 4). */
@Serializable
data class SharedShow(val guid: String? = null, val url: String, val title: String, val since: Long, val image: String? = null)

/** The shared list, as sealed. */
@Serializable
data class Shelf(val v: Int = 1, val shows: List<SharedShow>)

/**
 * Sharing with mutual Nostr contacts (ADR 0024, 4): the listener's subscriptions — those not
 * left out — as one kind-30078 event signed by the share key, `d` = `hopp/contacts/shelf`,
 * sealed by the share key to itself; and for each mutual contact whose contacts note names an
 * inbox, the share key sealed to that inbox, `d` = `hopp/contacts/key/<inbox>`. Each goes out
 * only when it changed: the list when the shows do, a key once per inbox and share key.
 * Signed by the share key alone — the listener's signer is never asked. Every device with the
 * share key sends its own list, so one first takes what the others changed ([upToDate]): a show left
 * out elsewhere must not go out again from a device that has not heard of it (ADR 0024, 4a).
 */
class ContactShare(
    private val identity: IdentityRepository,
    private val publisher: RelayPublisher,
    private val keys: ContactKeys,
    private val secrets: SecretStore,
    private val shows: suspend () -> List<SharedShow>,
    /** Whether this device has what the listener's other devices changed; false: the list waits for the next time. */
    private val upToDate: suspend () -> Boolean = { true },
    private val nowSeconds: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What went out under which share key: the list's hash, and the inboxes that have the key. */
    @Serializable
    private data class Sent(val share: String, val shelf: String? = null, val inboxes: Set<String> = emptySet())

    /** The inboxes the current share key was sealed to. */
    fun sentTo(): Set<String> = keys.shareKey()?.let { k -> sent()?.takeIf { it.share == k.publicKeyHex }?.inboxes }.orEmpty()

    /** Sends what changed; [inboxes]: the inbox keys of mutual contacts who look. Nothing while not sharing. */
    suspend fun publish(inboxes: Set<String>) {
        val share = keys.shareKey() ?: return
        var sent = sent()?.takeIf { it.share == share.publicKeyHex } ?: Sent(share.publicKeyHex)
        val urls = identity.syncRelays()
        val signer = LocalSigner(share)
        // Asked only when the list changed, and the list made again after: the answer may have changed it.
        if (shelf().second != sent.shelf && upToDate()) {
            val (shelf, hash) = shelf()
            if (hash != sent.shelf) {
                val sealed = Nip44.encrypt(shelf, Nip44.conversationKey(share.secretKey, share.publicKeyHex.hexToBytes()))
                val event = signer.sign(UnsignedEvent(Nip78.KIND, sealed, listOf(listOf("d", SHELF)), nowSeconds()))
                if (publisher.publish(event, urls).isNotEmpty()) sent = sent.copy(shelf = hash).also(::keep)
            }
        }
        for (inbox in inboxes - sent.inboxes) {
            val sealed = Nip44.encrypt(share.secretKey.toHex(), Nip44.conversationKey(share.secretKey, inbox.hexToBytes()))
            val event = signer.sign(UnsignedEvent(Nip78.KIND, sealed, listOf(listOf("d", keyTag(inbox))), nowSeconds()))
            if (publisher.publish(event, urls).isNotEmpty()) sent = sent.copy(inboxes = sent.inboxes + inbox).also(::keep)
        }
    }

    /**
     * What [old] signed goes (ADR 0024, 4): every event replaced by an empty one — relays keep only
     * the newest of an address — then deleted (NIP-09), signed by the old key itself. Sharing turned
     * off, or a new share key. True when every replacement reached a relay.
     */
    suspend fun retire(old: KeyPair): Boolean {
        val urls = identity.syncRelays()
        val filter = kotlinx.serialization.json.buildJsonObject {
            put("kinds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(Nip78.KIND))))
            put("authors", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(old.publicKeyHex))))
        }
        val tags = publisher.query(listOf(filter), urls).filter { it.pubkey == old.publicKeyHex && it.kind == Nip78.KIND }
            .mapNotNull { it.tag("d") }.toSet() + SHELF
        val signer = LocalSigner(old)
        val at = nowSeconds()
        var all = true
        for (d in tags) all = publisher.publish(signer.sign(UnsignedEvent(Nip78.KIND, "", listOf(listOf("d", d)), at)), urls).isNotEmpty() && all
        val addresses = tags.map { listOf("a", "${Nip78.KIND}:${old.publicKeyHex}:$it") }
        publisher.publish(signer.sign(UnsignedEvent(DELETION, "", addresses + listOf(listOf("k", "${Nip78.KIND}")), at)), urls)
        if (sent()?.share == old.publicKeyHex) secrets.put(SENT, null)
        return all
    }

    /** The list as it would go out, and its hash. */
    private suspend fun shelf(): Pair<String, String> {
        val shelf = json.encodeToString(Shelf.serializer(), Shelf(shows = fit(shows())))
        return shelf to Sha256.digest(shelf.encodeToByteArray()).toHex()
    }

    /** As many shows as one sealed event holds (NIP-44: 65,535 bytes) — the newest first. */
    private fun fit(all: List<SharedShow>): List<SharedShow> {
        val trimmed = all.map { it.copy(title = it.title.take(MAX_TITLE), image = it.image?.takeIf { i -> i.startsWith("https://") && i.length <= MAX_URL }) }.filter { it.url.length <= MAX_URL }
        var n = trimmed.size
        while (n > 0 && json.encodeToString(Shelf.serializer(), Shelf(shows = trimmed.take(n))).encodeToByteArray().size > MAX_PLAIN) n -= maxOf(1, n / 10)
        return trimmed.take(n)
    }

    private fun sent(): Sent? = runCatching { secrets.get(SENT) }.getOrNull()?.let { runCatching { json.decodeFromString(Sent.serializer(), it) }.getOrNull() }

    private fun keep(s: Sent) = secrets.put(SENT, json.encodeToString(Sent.serializer(), s))

    companion object {
        const val SHELF = "hopp/contacts/shelf"
        fun keyTag(inbox: String) = "hopp/contacts/key/$inbox"
        private const val SENT = "contacts_sent"
        private const val MAX_TITLE = 120
        private const val MAX_URL = 1024
        private const val MAX_PLAIN = 60_000
        private const val DELETION = 5
    }
}
