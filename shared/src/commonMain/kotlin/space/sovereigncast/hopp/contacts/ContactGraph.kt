package space.sovereigncast.hopp.contacts

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.Nip44
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.withRelayPool
import kotlin.time.Duration.Companion.seconds

/** One of the listener's Nostr contacts, as their own relays show them. */
@Serializable
data class Contact(
    val pubkey: String,
    /** They follow the listener back: their newest contact list names the listener. */
    val mutual: Boolean,
    /** Their contacts note's public keys (ADR 0024, 3): sharing, and looking at what is shared. */
    val share: String? = null,
    val inbox: String? = null,
    /** What they share with the listener — opened with the listener's inbox key (ADR 0024, 5). */
    val shows: List<SharedShow> = emptyList(),
    /** Their profile (kind 0), for the rows that name them. */
    val name: String? = null,
    val picture: String? = null,
    /** What they did in public about shows (ADR 0024, 6): boosts made public and comments. */
    val signals: List<PublicSignal> = emptyList(),
    /** Their write relays (NIP-65), where their list is looked for again. */
    val relays: List<String> = emptyList(),
    /** They share, but sealed no key for the listener's inbox yet: their Hopp does when it next reads its
     *  contacts (maintainer 2026-11-08: a list seen only in part, with nothing to say why). */
    val locked: Boolean = false,
)

/** A contact's public act about a show: a boost made public or a comment, naming the show's `podcast:guid`. */
@Serializable
data class PublicSignal(val show: String, val boost: Boolean, val at: Long)

/** The listener's contacts read: [following] from their own list, what each one's relays told. */
@Serializable
data class ContactsRead(
    val following: Set<String>,
    val contacts: Map<String, Contact>,
    val at: Long,
    /** Shows known only from public signals, looked up by their `podcast:guid`. */
    val looked: Map<String, SharedShow> = emptyMap(),
) {
    val mutual: List<Contact> get() = contacts.values.filter { it.mutual }
    /** Mutual contacts who use the feature: their contacts note names a key. */
    val withHopp: List<Contact> get() = mutual.filter { it.share != null || it.inbox != null }
    /** Mutual contacts who share, whose list this inbox cannot open yet. */
    val waiting: List<Contact> get() = mutual.filter { it.locked }

    /**
     * Shows the contacts share or did something about in public, each with who (ADR 0024, 7):
     * a boost weighs [BOOST], a comment [COMMENT], a shared subscription [SHARED] — per contact
     * the most of these — summed over the contacts; then the newest first.
     */
    fun listening(): List<ContactPick> {
        val acts = mutableMapOf<String, MutableMap<String, Pair<Int, Long>>>()
        val shows = mutableMapOf<String, SharedShow>()
        fun count(key: String, who: String, weight: Int, at: Long) {
            val byWho = acts.getOrPut(key) { mutableMapOf() }
            val was = byWho[who]
            byWho[who] = (maxOf(was?.first ?: 0, weight)) to maxOf(was?.second ?: 0, at)
        }
        for (c in contacts.values) {
            c.shows.forEach { s -> showKey(s).let { k -> shows.getOrPut(k) { s }; count(k, c.pubkey, SHARED, s.since) } }
            c.signals.forEach { sig -> count(showKey(sig.show, ""), c.pubkey, if (sig.boost) BOOST else COMMENT, sig.at) }
        }
        looked.forEach { (guid, s) -> shows.getOrPut(showKey(guid, s.url)) { s } }
        return acts.mapNotNull { (key, byWho) ->
            val show = shows[key] ?: return@mapNotNull null
            val by = byWho.entries.sortedByDescending { it.value.first }.mapNotNull { contacts[it.key] }
            ContactPick(show, by, byWho.values.maxOf { it.second }, byWho.values.sumOf { it.first })
        }.sortedWith(compareByDescending<ContactPick> { it.score }.thenByDescending { it.latest })
    }

    /** Guids of shows contacts did something about in public, most first — to look up the ones not known. */
    fun signalShows(): List<String> = contacts.values.flatMap { c -> c.signals.map { it.show }.distinct() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    /** With the names and pictures [before] knew where this read found none: a look-up that came back without one is no profile gone. */
    fun keepingNames(before: ContactsRead?): ContactsRead = if (before == null) this else copy(contacts = contacts.mapValues { (who, c) ->
        before.contacts[who]?.let { was -> c.copy(name = c.name ?: was.name, picture = c.picture ?: was.picture) } ?: c
    })

    /** Who of the contacts shares, boosted or commented on the show with [guid] or at [feedUrl]. */
    fun listeners(guid: String?, feedUrl: String): List<Contact> {
        val key = showKey(guid, feedUrl)
        return contacts.values.filter { c ->
            c.shows.any { showKey(it) == key || it.url == feedUrl } || (guid != null && c.signals.any { it.show.equals(guid, ignoreCase = true) })
        }
    }

    companion object {
        const val BOOST = 3
        const val COMMENT = 2
        const val SHARED = 1
    }
}

/** What a read found so far: the contacts, then how many follow back and how many of those use Hopp; null: not yet. */
data class ReadProgress(val following: Int? = null, val mutual: Int? = null, val withHopp: Int? = null)

/** A show the contacts share or did something about, and who: [latest], their newest act; [score], what it weighs. */
data class ContactPick(val show: SharedShow, val by: List<Contact>, val latest: Long, val score: Int = by.size)

private fun showKey(s: SharedShow) = showKey(s.guid, s.url)
private fun showKey(guid: String?, url: String) = guid?.takeIf { it.isNotBlank() }?.let { "guid:" + it.lowercase() } ?: "url:" + url.trimEnd('/')

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
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [inbox]: the listener's inbox key, while they look at what contacts share — their lists are opened with it.
     * [progress]: what was found so far, for the Nostr page — a read that said nothing for a minute left the
     * listener guessing (maintainer 2026-11-08). One connection per relay for the whole read ([withRelayPool]):
     * a relay that did not answer is not waited for again by the next step.
     */
    suspend fun read(inbox: KeyPair? = null, progress: (ReadProgress) -> Unit = {}): ContactsRead? = withRelayPool { readInPool(inbox, progress) }

    private suspend fun readInPool(inbox: KeyPair?, progress: (ReadProgress) -> Unit): ContactsRead? {
        val me = identity.identity.value?.pubkey ?: return null
        progress(ReadProgress())
        val lookup = (identity.writeRelays() + IdentityRepository.DEFAULT_RELAYS + IdentityRepository.INDEX_RELAYS).distinct()
        val mine = publisher.query(listOf(filter(CONTACT_LIST, listOf(me))), lookup, SETTLE)
            .filter { it.pubkey == me && it.kind == CONTACT_LIST }.maxByOrNull { it.created_at }
        val following = mine?.tags.orEmpty().filter { it.size >= 2 && it[0] == "p" && HEX64.matches(it[1]) && it[1] != me }.map { it[1] }.toSet()
        if (following.isEmpty()) return ContactsRead(emptySet(), emptyMap(), nowSeconds())
        progress(ReadProgress(following.size))

        val outbox = mutableMapOf<String, List<String>>()
        val found = coroutineScope {
            // A contacts note goes to Hopp's relays for sync too: asked there for everyone at once, while
            // the relay lists are still looked up — not one step after the other (maintainer 2026-11-08).
            val atHopps = async { ask(IdentityRepository.SYNC_RELAYS.associateWith { following }, me, inbox) }
            // Where each contact writes: their relay list, from the indexes; Hopp's relays where they have none.
            following.chunked(BATCH).forEach { batch ->
                publisher.query(listOf(filter(RELAY_LIST, batch)), IdentityRepository.INDEX_RELAYS + IdentityRepository.DEFAULT_RELAYS, LOOK_UP_SETTLE)
                    .filter { it.kind == RELAY_LIST && it.pubkey in batch }
                    .groupBy { it.pubkey }.mapValues { (_, lists) -> lists.maxBy { it.created_at } }
                    .forEach { (who, list) -> outbox[who] = writeRelays(list) }
            }
            val byRelay = mutableMapOf<String, MutableSet<String>>()
            for (who in following) {
                (outbox[who].orEmpty().ifEmpty { IdentityRepository.DEFAULT_RELAYS } - IdentityRepository.SYNC_RELAYS.toSet())
                    .forEach { byRelay.getOrPut(it) { mutableSetOf() } += who }
            }
            atHopps.await() + ask(byRelay, me, inbox)
        }
        val followsBack = found.filter { it.kind == CONTACT_LIST && it.pubkey in following && it.tags.any { t -> t.size >= 2 && t[0] == "p" && t[1] == me } }
            .map { it.pubkey }.toSet()
        val notes = found.filter { it.kind == Nip78.KIND && it.pubkey in following && it.tag("d") == ContactKeys.DTAG }
            .groupBy { it.pubkey }.mapValues { (_, n) -> n.sortedWith(compareByDescending<Event> { it.created_at }.thenBy { it.id }).first() }
        val bare = following.associateWith { who ->
            val note = notes[who]?.takeIf { it.content.isNotEmpty() }
            Contact(who, who in followsBack, note?.tag("share")?.takeIf { HEX64.matches(it) }, note?.tag("inbox")?.takeIf { HEX64.matches(it) },
                relays = outbox[who].orEmpty())
        }
        val mutual = bare.values.filter { it.mutual }
        progress(ReadProgress(following.size, mutual.size, mutual.count { it.share != null || it.inbox != null }))
        val sharing = if (inbox == null) emptyList() else mutual.filter { it.share != null }
        // What the people the listener follows did in public about shows (ADR 0024, 6) — one way, as public is public.
        val signals = if (inbox == null) emptyMap() else signals(found.filter { it.pubkey in following })
        // What mutual contacts share with this inbox, and the names of who may show — at once, not one after the other.
        val (shelves, named) = coroutineScope {
            val shelves = async { if (inbox == null) emptyMap() else shelves(sharing, outbox, inbox) }
            val named = async { profiles(sharing.map { it.pubkey }.toSet() + signals.filterValues { it.isNotEmpty() }.keys) }
            shelves.await() to named.await()
        }
        val contacts = bare.mapValues { (who, c) ->
            val p = named[who]
            c.copy(shows = shelves[who].orEmpty(), name = p?.first, picture = p?.second, signals = signals[who].orEmpty(),
                locked = who in shelves && shelves[who] == null)
        }
        return ContactsRead(following, contacts, nowSeconds())
    }

    /**
     * Each relay of [byRelay] asked for the contact lists naming [me] and the contacts notes of the people it
     * serves — with [inbox], their notes and comments about shows too. A contact's own relay gets [THEIR_WAIT]:
     * many lists name relays long gone, and each cost the full timeout (maintainer 2026-11-08).
     */
    private suspend fun ask(byRelay: Map<String, Collection<String>>, me: String, inbox: KeyPair?): List<Event> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        byRelay.flatMap { (url, people) -> people.toList().chunked(BATCH).map { batch -> url to batch } }.map { (url, batch) ->
            async {
                gate.withPermit {
                    val filters = listOf(
                        filter(CONTACT_LIST, batch, "#p" to listOf(me)),
                        filter(Nip78.KIND, batch, "#d" to listOf(ContactKeys.DTAG)),
                    ) + (if (inbox != null) signalFilters(batch) else emptyList())
                    val wait = if (url in HOPPS) null else THEIR_WAIT
                    runCatching {
                        if (wait == null) publisher.query(filters, listOf(url), SETTLE)
                        else withTimeoutOrNull(wait) { publisher.query(filters, listOf(url), SETTLE) }.orEmpty()
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()
    }

    /**
     * The lists of [sharing] contacts opened again — for those whose list did not open at the last read:
     * their key for this inbox may have come since (device feedback 2026-11-02: it came minutes after the
     * read, and the show waited for the next one). One request per contact, not the whole read.
     */
    suspend fun shelvesAgain(sharing: List<Contact>, inbox: KeyPair): Map<String, List<SharedShow>?> =
        withRelayPool { shelves(sharing, sharing.associate { it.pubkey to it.relays }, inbox) }

    /** Each sharing contact's list, opened: the share key sealed to [inbox], then the list sealed by that key.
     *  Null for one that sealed no key for [inbox] (yet), or none that opens it. */
    private suspend fun shelves(sharing: List<Contact>, outbox: Map<String, List<String>>, inbox: KeyPair): Map<String, List<SharedShow>?> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        sharing.map { c ->
            async {
                gate.withPermit {
                    val share = c.share!!
                    val urls = (outbox[c.pubkey].orEmpty().ifEmpty { IdentityRepository.DEFAULT_RELAYS } + IdentityRepository.SYNC_RELAYS).distinct()
                    val filter = buildJsonObject {
                        putJsonArray("kinds") { add(JsonPrimitive(Nip78.KIND)) }
                        putJsonArray("authors") { add(JsonPrimitive(share)) }
                        putJsonArray("#d") { add(JsonPrimitive(ContactShare.SHELF)); add(JsonPrimitive(ContactShare.keyTag(inbox.publicKeyHex))) }
                    }
                    val found = runCatching { withTimeoutOrNull(SHELF_WAIT) { publisher.query(listOf(filter), urls, SETTLE) }.orEmpty() }.getOrDefault(emptyList())
                        .filter { it.pubkey == share && it.kind == Nip78.KIND }
                    val newest = { d: String -> found.filter { it.tag("d") == d }.maxByOrNull { it.created_at } }
                    val shows: List<SharedShow>? = runCatching<List<SharedShow>?> {
                        val sealedKey = newest(ContactShare.keyTag(inbox.publicKeyHex))?.takeIf { it.content.isNotEmpty() } ?: return@runCatching null
                        val secret = Nip44.decrypt(sealedKey.content, Nip44.conversationKey(inbox.secretKey, share.hexToBytes()))
                        val key = KeyPair.fromHex(secret)
                        // A key that is not the one the contact's note names opens nothing.
                        if (key.publicKeyHex != share) return@runCatching null
                        val shelf = newest(ContactShare.SHELF)?.takeIf { it.content.isNotEmpty() } ?: return@runCatching emptyList()
                        json.decodeFromString(Shelf.serializer(), Nip44.decrypt(shelf.content, Nip44.conversationKey(key.secretKey, share.hexToBytes()))).shows
                            .filter { it.url.startsWith("http") }.take(MAX_SHOWS)
                    }.getOrNull()
                    c.pubkey to shows
                }
            }
        }.awaitAll().toMap()
    }

    /** Notes and comments naming a show or episode (NIP-73), of the last [SIGNAL_DAYS] days. */
    private fun signalFilters(authors: List<String>): List<JsonObject> {
        val since = nowSeconds() - SIGNAL_DAYS * 86_400
        return listOf(
            filter(1, authors, "#k" to listOf("podcast:guid", "podcast:item:guid"), since),
            filter(COMMENT, authors, "#K" to listOf("podcast:guid", "podcast:item:guid"), since),
        )
    }

    /** Per author, the shows their notes and comments name by `podcast:guid` — a boost when the note ends
     *  "⚡ N sats" as Hopp writes it, a comment otherwise; the newest [MAX_SIGNALS] each. */
    private fun signals(events: List<Event>): Map<String, List<PublicSignal>> = events
        .filter { it.kind == 1 || it.kind == COMMENT }.distinctBy { it.id }
        .flatMap { e ->
            val shows = e.tags.filter { it.size >= 2 && (it[0] == "i" || it[0] == "I") && it[1].startsWith(SHOW_PREFIX) }
                .map { it[1].removePrefix(SHOW_PREFIX).trim().lowercase() }.filter { it.isNotEmpty() && it.length <= 64 }.distinct()
            val boost = e.kind == 1 && space.sovereigncast.hopp.comments.boostSats(e.content) != null
            shows.map { e.pubkey to PublicSignal(it, boost, e.created_at) }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, list) -> list.sortedByDescending { it.at }.take(MAX_SIGNALS) }

    /** Name and picture (kind 0) of [people], from the indexes and Hopp's relays. */
    suspend fun profiles(people: Set<String>): Map<String, Pair<String?, String?>> {
        if (people.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, Pair<String?, String?>>()
        people.toList().chunked(BATCH).forEach { batch ->
            publisher.query(listOf(filter(0, batch)), IdentityRepository.INDEX_RELAYS + IdentityRepository.DEFAULT_RELAYS, LOOK_UP_SETTLE)
                .filter { it.kind == 0 && it.pubkey in batch }.groupBy { it.pubkey }.mapValues { (_, e) -> e.maxBy { it.created_at } }
                .forEach { (who, e) ->
                    val o = runCatching { json.parseToJsonElement(e.content) as? JsonObject }.getOrNull() ?: return@forEach
                    val text = { k: String -> (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } }
                    out[who] = (text("display_name") ?: text("name"))?.take(60) to text("picture")?.takeIf { it.startsWith("https://") && it.length <= 1024 }
                }
        }
        return out
    }

    private fun writeRelays(list: Event): List<String> = list.tags
        .filter { it.size >= 2 && it[0] == "r" && it[1].startsWith("wss://") && (it.size < 3 || it[2] != "read") }
        .map { it[1].trimEnd('/') }.distinct().take(THEIR_RELAYS)

    private fun filter(kind: Int, authors: List<String>, tag: Pair<String, List<String>>? = null, since: Long? = null): JsonObject = buildJsonObject {
        putJsonArray("kinds") { add(JsonPrimitive(kind)) }
        putJsonArray("authors") { authors.forEach { add(JsonPrimitive(it)) } }
        tag?.let { (name, values) -> putJsonArray(name) { values.forEach { add(JsonPrimitive(it)) } } }
        since?.let { put("since", it) }
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
        /** Shows taken from one contact's list at most. */
        const val MAX_SHOWS = 500
        const val COMMENT = 1111
        const val SIGNAL_DAYS = 90L
        const val MAX_SIGNALS = 50
        const val SHOW_PREFIX = "podcast:guid:"
        val SETTLE = 3.seconds
        /** How long a contact's own relay gets: one that answers at all answers within a second or two (measured 2026-11-08). */
        val THEIR_WAIT = 4.seconds
        /** A contact's list, from their relays and Hopp's: Hopp's answer within it. */
        val SHELF_WAIT = 6.seconds
        val HOPPS = (IdentityRepository.SYNC_RELAYS + IdentityRepository.DEFAULT_RELAYS).toSet()
        /** The indexes hold most of it and answer last: Hopp's relays, quick with little, must not cut them off. */
        val LOOK_UP_SETTLE = 6.seconds
        val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
