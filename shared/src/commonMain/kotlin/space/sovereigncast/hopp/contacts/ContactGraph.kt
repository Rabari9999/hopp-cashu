package space.sovereigncast.hopp.contacts

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.RelayPublisher
import kotlin.time.Duration.Companion.seconds

/** One of the listener's Nostr contacts, as their own relays show them. */
data class Contact(
    val pubkey: String,
    /** They follow the listener back: their newest contact list names the listener. */
    val mutual: Boolean,
    /** Their contacts note's public keys (ADR 0024, 3): sharing, and looking at what is shared. */
    val share: String? = null,
    val inbox: String? = null,
)

/** The listener's contacts read: [following] from their own list, what each one's relays told. */
data class ContactsRead(val following: Set<String>, val contacts: Map<String, Contact>, val at: Long) {
    val mutual: List<Contact> get() = contacts.values.filter { it.mutual }
    /** Mutual contacts who use the feature: their contacts note names a key. */
    val withHopp: List<Contact> get() = mutual.filter { it.share != null || it.inbox != null }
}

/**
 * The listener's Nostr contacts (ADR 0024, 2): their own contact list (NIP-02, kind 3), then
 * each contact's relays (NIP-65) and, from those, whether that contact follows the listener back
 * and their contacts note. Asked of each relay at once for all the contacts it serves — a
 * contact list is asked for only where it names the listener (`#p`), so lists of thousands of
 * names never travel; relays keep only the newest list of a person, so one that dropped the
 * listener answers nothing.
 */
class ContactGraph(
    private val identity: IdentityRepository,
    private val publisher: RelayPublisher,
    private val nowSeconds: () -> Long,
) {
    suspend fun read(): ContactsRead? {
        val me = identity.identity.value?.pubkey ?: return null
        val lookup = (identity.writeRelays() + IdentityRepository.DEFAULT_RELAYS + IdentityRepository.INDEX_RELAYS).distinct()
        val mine = publisher.query(listOf(filter(CONTACT_LIST, listOf(me))), lookup, SETTLE)
            .filter { it.pubkey == me && it.kind == CONTACT_LIST }.maxByOrNull { it.created_at }
        val following = mine?.tags.orEmpty().filter { it.size >= 2 && it[0] == "p" && HEX64.matches(it[1]) && it[1] != me }.map { it[1] }.toSet()
        if (following.isEmpty()) return ContactsRead(emptySet(), emptyMap(), nowSeconds())

        // Where each contact writes: their relay list, from the indexes; Hopp's relays where they have none.
        val outbox = mutableMapOf<String, List<String>>()
        following.chunked(BATCH).forEach { batch ->
            publisher.query(listOf(filter(RELAY_LIST, batch)), IdentityRepository.INDEX_RELAYS + IdentityRepository.DEFAULT_RELAYS, SETTLE)
                .filter { it.kind == RELAY_LIST && it.pubkey in batch }
                .groupBy { it.pubkey }.mapValues { (_, lists) -> lists.maxBy { it.created_at } }
                .forEach { (who, list) -> outbox[who] = writeRelays(list) }
        }
        // A contacts note goes to Hopp's relays for sync too: asked there for everyone.
        val byRelay = mutableMapOf<String, MutableSet<String>>()
        for (who in following) {
            val theirs = outbox[who].orEmpty().ifEmpty { IdentityRepository.DEFAULT_RELAYS }
            (theirs + IdentityRepository.SYNC_RELAYS).distinct().forEach { byRelay.getOrPut(it) { mutableSetOf() } += who }
        }
        val gate = Semaphore(PARALLEL)
        val found = coroutineScope {
            byRelay.flatMap { (url, people) -> people.chunked(BATCH).map { batch -> url to batch } }.map { (url, batch) ->
                async {
                    gate.withPermit {
                        runCatching {
                            publisher.query(listOf(
                                filter(CONTACT_LIST, batch, "#p" to listOf(me)),
                                filter(Nip78.KIND, batch, "#d" to listOf(ContactKeys.DTAG)),
                            ), listOf(url), SETTLE)
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }
        val followsBack = found.filter { it.kind == CONTACT_LIST && it.pubkey in following && it.tags.any { t -> t.size >= 2 && t[0] == "p" && t[1] == me } }
            .map { it.pubkey }.toSet()
        val notes = found.filter { it.kind == Nip78.KIND && it.pubkey in following && it.tag("d") == ContactKeys.DTAG }
            .groupBy { it.pubkey }.mapValues { (_, n) -> n.sortedWith(compareByDescending<Event> { it.created_at }.thenBy { it.id }).first() }
        val contacts = following.associateWith { who ->
            val note = notes[who]?.takeIf { it.content.isNotEmpty() }
            Contact(who, who in followsBack, note?.tag("share")?.takeIf { HEX64.matches(it) }, note?.tag("inbox")?.takeIf { HEX64.matches(it) })
        }
        return ContactsRead(following, contacts, nowSeconds())
    }

    private fun writeRelays(list: Event): List<String> = list.tags
        .filter { it.size >= 2 && it[0] == "r" && it[1].startsWith("wss://") && (it.size < 3 || it[2] != "read") }
        .map { it[1].trimEnd('/') }.distinct().take(THEIR_RELAYS)

    private fun filter(kind: Int, authors: List<String>, tag: Pair<String, List<String>>? = null): JsonObject = buildJsonObject {
        putJsonArray("kinds") { add(JsonPrimitive(kind)) }
        putJsonArray("authors") { authors.forEach { add(JsonPrimitive(it)) } }
        tag?.let { (name, values) -> putJsonArray(name) { values.forEach { add(JsonPrimitive(it)) } } }
    }

    private companion object {
        const val CONTACT_LIST = 3
        const val RELAY_LIST = 10002
        /** Authors per request: what relays commonly take in one filter. */
        const val BATCH = 200
        /** A contact's write relays asked at most. */
        const val THEIR_RELAYS = 3
        /** Relays asked at once. */
        const val PARALLEL = 8
        val SETTLE = 3.seconds
        val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
