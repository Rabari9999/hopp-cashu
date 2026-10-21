package space.sovereigncast.hopp.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import space.sovereigncast.hopp.crypto.secureRandomBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.Nip44Exception
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.NostrSigner
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.UnsignedEvent
import kotlin.time.Duration.Companion.seconds

/** No relay took a document: it stays unpublished and goes out on the next sync. */
class SyncNotPublished : Exception("no relay took the sync document")

/**
 * The relays' side of sync (ADR 0021, 5): a document's parts read back and
 * opened with the sync key ([SyncKeys]), or sealed and sent. Only events by
 * that key count — anyone can post a kind 30078 with the same `d` tag.
 */
class SyncRelays(private val publisher: RelayPublisher, private val nowSeconds: () -> Long) {
    private val lastSent = mutableMapOf<String, Long>()

    /** The document as the relays hold it; null when none of them has it. */
    suspend fun fetch(signer: NostrSigner, doc: String, relays: List<String>): Map<String, SyncEntry>? {
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(Nip78.KIND)) }
            putJsonArray("authors") { add(JsonPrimitive(signer.pubkey)) }
            putJsonArray("#d") { SyncCodec.dTags(doc).forEach { add(JsonPrimitive(it)) } }
        }
        val tags = SyncCodec.dTags(doc)
        // Per part the newest; for the same second the lower id, as relays keep it (NIP-01).
        val newest = publisher.query(listOf(filter), relays, settle = 2.seconds)
            .filter { it.kind == Nip78.KIND && it.pubkey == signer.pubkey }
            .groupBy { e -> e.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) }
            .filterKeys { it in tags }
            .mapValues { (_, events) -> events.sortedWith(compareByDescending<Event> { it.created_at }.thenBy { it.id }).first() }
        if (newest[SyncCodec.dTag(doc)] == null) return null
        // A part that does not open or read is left out; the merge is per entry, so the rest still counts.
        val parts = newest.values.mapNotNull { e ->
            val text = try { signer.decryptFromSelf(e.content) } catch (x: Nip44Exception) { return@mapNotNull null }
            try { SyncCodec.decode(text) } catch (x: SerializationException) { null } catch (x: IllegalArgumentException) { null }
        }
        return SyncCodec.join(parts)
    }

    /** Seals [entries] and sends every part; the relays that took them all. */
    suspend fun publish(signer: NostrSigner, doc: String, entries: Map<String, SyncEntry>, relays: List<String>): Set<String> {
        val rev = secureRandomBytes(8).toHex()
        // Two versions in the same second: relays would keep the lower id, not the newer one.
        val now = maxOf(nowSeconds(), (lastSent[doc] ?: 0) + 1).also { lastSent[doc] = it }
        var took: Set<String>? = null
        for (part in SyncCodec.split(entries, rev)) {
            val content = signer.encryptToSelf(SyncCodec.encode(part))
            val event = signer.sign(UnsignedEvent(Nip78.KIND, content, listOf(listOf("d", SyncCodec.dTag(doc, part.part))), now))
            val ok = publisher.publish(event, relays)
            took = took?.intersect(ok) ?: ok
        }
        if (took.isNullOrEmpty()) throw SyncNotPublished()
        return took
    }
}
