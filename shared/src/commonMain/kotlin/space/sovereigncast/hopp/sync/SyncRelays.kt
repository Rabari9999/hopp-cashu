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

    /**
     * The document as the relays hold it (audit 2026-10-22, H-02): [Fetched.Complete]
     * only with every part of one revision opened; [Fetched.Absent] only when a
     * majority of the relays answered to the end without it — a document goes
     * out only to a majority too ([publish]), so the two always meet.
     */
    suspend fun fetch(signer: NostrSigner, doc: String, relays: List<String>): Fetched {
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(Nip78.KIND)) }
            putJsonArray("authors") { add(JsonPrimitive(signer.pubkey)) }
            putJsonArray("#d") { SyncCodec.dTags(doc).forEach { add(JsonPrimitive(it)) } }
        }
        val got = publisher.queryChecked(listOf(filter), relays, settle = 2.seconds)
        // Per part the newest; for the same second the lower id, as relays keep it (NIP-01).
        val newest = got.events
            .filter { it.kind == Nip78.KIND && it.pubkey == signer.pubkey }
            .mapNotNull { e -> e.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.let { SyncCodec.partOf(doc, it) }?.let { it to e } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, events) -> events.sortedWith(compareByDescending<Event> { it.created_at }.thenBy { it.id }).first() }
        val majority = got.answered * 2 > got.asked
        if (newest[1] == null) return if (majority) Fetched.Absent else Fetched.Unknown
        val at = newest.values.maxOf { it.created_at }
        // A part that does not open or read makes the read incomplete, never smaller.
        val parts = newest.mapNotNull { (n, e) ->
            val text = try { signer.decryptFromSelf(e.content) } catch (x: Nip44Exception) { return@mapNotNull null }
            val part = try { SyncCodec.decode(text) } catch (x: SerializationException) { null } catch (x: IllegalArgumentException) { null }
            part?.let { n to it }
        }.toMap()
        return SyncCodec.join(parts)?.let { Fetched.Complete(it, at) } ?: Fetched.Incomplete(SyncCodec.joinWhatArrived(parts), at)
    }

    /**
     * Seals [entries] and sends every part, later than [notBefore] (the newest
     * version read — or a relay keeps that one, NIP-01; audit 2026-10-22, M-02).
     * Taken only when a majority of [relays] took every part ([SyncNotPublished]
     * otherwise: it goes again next time).
     */
    suspend fun publish(signer: NostrSigner, doc: String, entries: Map<String, SyncEntry>, relays: List<String>, notBefore: Long = 0): Set<String> {
        val rev = secureRandomBytes(8).toHex()
        val parts = SyncCodec.split(entries, rev, doc)
        // Two versions in the same second: relays would keep the lower id, not the newer one.
        val now = maxOf(nowSeconds(), (lastSent[doc] ?: 0) + 1, notBefore + 1).also { lastSent[doc] = it }
        var took: Set<String>? = null
        for (part in parts) {
            val content = signer.encryptToSelf(SyncCodec.encode(part))
            val event = signer.sign(UnsignedEvent(Nip78.KIND, content, listOf(listOf("d", SyncCodec.dTag(doc, part.part))), now))
            val ok = publisher.publish(event, relays)
            took = took?.intersect(ok) ?: ok
        }
        if (took.isNullOrEmpty() || took.size * 2 <= relays.distinct().size) throw SyncNotPublished()
        return took
    }
}
