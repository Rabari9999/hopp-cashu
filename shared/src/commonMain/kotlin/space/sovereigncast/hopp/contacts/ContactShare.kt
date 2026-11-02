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
data class SharedShow(val guid: String? = null, val url: String, val title: String, val since: Long)

/** The shared list, as sealed. */
@Serializable
data class Shelf(val v: Int = 1, val shows: List<SharedShow>)

/**
 * Sharing with mutual Nostr contacts (ADR 0024, 4): the listener's subscriptions — those not
 * left out — as one kind-30078 event signed by the share key, `d` = `hopp/contacts/shelf`,
 * sealed by the share key to itself; and for each mutual contact whose contacts note names an
 * inbox, the share key sealed to that inbox, `d` = `hopp/contacts/key/<inbox>`. Each goes out
 * only when it changed: the list when the shows do, a key once per inbox and share key.
 * Signed by the share key alone — the listener's signer is never asked.
 */
class ContactShare(
    private val identity: IdentityRepository,
    private val publisher: RelayPublisher,
    private val keys: ContactKeys,
    private val secrets: SecretStore,
    private val shows: suspend () -> List<SharedShow>,
    private val nowSeconds: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What went out under which share key: the list's hash, and the inboxes that have the key. */
    @Serializable
    private data class Sent(val share: String, val shelf: String? = null, val inboxes: Set<String> = emptySet())

    /** Sends what changed; [inboxes]: the inbox keys of mutual contacts who look. Nothing while not sharing. */
    suspend fun publish(inboxes: Set<String>) {
        val share = keys.shareKey() ?: return
        var sent = sent()?.takeIf { it.share == share.publicKeyHex } ?: Sent(share.publicKeyHex)
        val urls = identity.syncRelays()
        val shelf = json.encodeToString(Shelf.serializer(), Shelf(shows = fit(shows())))
        val hash = Sha256.digest(shelf.encodeToByteArray()).toHex()
        val signer = LocalSigner(share)
        if (hash != sent.shelf) {
            val sealed = Nip44.encrypt(shelf, Nip44.conversationKey(share.secretKey, share.publicKeyHex.hexToBytes()))
            val event = signer.sign(UnsignedEvent(Nip78.KIND, sealed, listOf(listOf("d", SHELF)), nowSeconds()))
            if (publisher.publish(event, urls).isNotEmpty()) sent = sent.copy(shelf = hash).also(::keep)
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

    /** As many shows as one sealed event holds (NIP-44: 65,535 bytes) — the newest first. */
    private fun fit(all: List<SharedShow>): List<SharedShow> {
        val trimmed = all.map { it.copy(title = it.title.take(MAX_TITLE)) }.filter { it.url.length <= MAX_URL }
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
