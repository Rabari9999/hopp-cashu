package space.sovereigncast.hopp.comments

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.NoteRef
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.noteRef
import space.sovereigncast.hopp.nostr.profileKey
import space.sovereigncast.hopp.sovereigncast.SovereigncastRepository

/**
 * Where an episode's comments hang (ADR 0021, 1): its GUID as NIP-73 names it,
 * the notes the show announced it with, and the show's own keys — a note of
 * theirs about the episode is where comments go, not a comment itself.
 */
data class CommentAnchor(val episodeGuid: String, val podcastGuid: String?, val notes: List<NoteRef>, val showKeys: Set<String>) {
    val itemTag: String get() = "podcast:item:guid:$episodeGuid"
}

/** A comment; [replies] only on a top-level one, oldest first, each naming whom it answers when that is not the top. */
data class Comment(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val content: String,
    val replyTo: String? = null,
    val replies: List<Comment> = emptyList(),
)

data class CommentAuthor(val name: String?, val picture: String?)

/** An episode's comments, newest thread first, and the relays they came from. */
data class CommentThread(val comments: List<Comment>, val relays: List<String>) {
    val count: Int get() = comments.sumOf { 1 + it.replies.size }
    /** Everyone who wrote, and everyone mentioned as `nostr:npub…` — the names to look up. */
    val people: Set<String> get() = comments.flatMap { c -> listOf(c) + c.replies }.flatMap { c ->
        listOf(c.pubkey) + MENTION.findAll(c.content).mapNotNull { profileKey(it.value) }
    }.toSet()

    companion object {
        val MENTION = Regex("""nostr:(npub|nprofile)1[02-9ac-hj-np-z]+""")
    }
}

/**
 * Reading comments (ADR 0021, stage 2): where an episode's comments hang, from
 * what Hopp knows about it; [reader] asks the relays. Nothing is asked before
 * the listener opens the comments: relays see the IP address and the episode.
 */
class CommentsRepository(
    val reader: CommentReader,
    private val episodes: EpisodeRepository,
    private val podcasts: PodcastRepository,
    private val sovereigncast: SovereigncastRepository,
) {

    /** What the feed, the show's Sovereigncast instance and its Nostr keys say about [episodeId]; null for an unknown one. */
    suspend fun anchor(episodeId: Long): CommentAnchor? {
        val item = episodes.item(episodeId) ?: return null
        val details = runCatching { episodes.details(episodeId) }.getOrNull()
        val meta = runCatching { podcasts.podcastMeta(item.podcastId) }.getOrNull()
        val fromFeed = details?.socialInteracts.orEmpty()
            .filter { it.protocol.equals("nostr", ignoreCase = true) || it.uri.startsWith("nostr:") }
            .sortedBy { it.priority ?: Int.MAX_VALUE }
        val fromInstance = runCatching {
            sovereigncast.layer(item.podcastId).first()?.let { sovereigncast.episodeNotes(it, item.guid) }
        }.getOrNull().orEmpty()
        val notes = (fromFeed.mapNotNull { noteRef(it.uri) } + fromInstance.mapNotNull { noteRef(it.uri) })
            .groupBy { it.id }.map { (id, refs) -> NoteRef(id, refs.flatMap { it.relays }.distinct(), refs.firstNotNullOfOrNull { it.author }) }
        val keys = buildSet {
            fromFeed.mapNotNull { it.accountId?.let(::profileKey) }.forEach(::add)
            fromInstance.mapNotNull { it.authorNpub?.let(::profileKey) }.forEach(::add)
            notes.mapNotNull { it.author }.forEach(::add)
            meta?.txt.orEmpty().filter { it.purpose.equals("nostr", ignoreCase = true) }.mapNotNull { profileKey(it.value) }.forEach(::add)
        }
        return CommentAnchor(item.guid, meta?.podcastGuid, notes, keys)
    }
}

/**
 * The relay side: the union of replies to the show's notes and kinds 1 and
 * 1111 that name the episode by NIP-73 — so Hopp shows what Fountain's
 * listeners say about it too. [listener]: whose mute list counts besides the show's.
 */
class CommentReader(private val publisher: RelayPublisher, private val listener: () -> String? = { null }) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The comments on [anchor]; muted people (the listener's and the show's lists) never show. */
    suspend fun load(anchor: CommentAnchor): CommentThread = coroutineScope {
        val showRelays = async {
            anchor.showKeys.firstOrNull()?.let { key ->
                publisher.latest(10002, key, LOOKUP)?.tags.orEmpty()
                    .filter { it.size >= 2 && it[0] == "r" && it[1].startsWith("wss://") && (it.size < 3 || it[2] == "write") }
                    .map { it[1].trimEnd('/') }
            }.orEmpty()
        }
        val relays = (anchor.notes.flatMap { it.relays }.take(2) + IdentityRepository.DEFAULT_RELAYS + showRelays.await())
            .distinct().take(MAX_RELAYS)
        val muted = async { muted(anchor.showKeys + listOfNotNull(listener()), relays) }

        val roots = anchor.notes.map { it.id }
        val first = publisher.query(buildList {
            add(filter(KINDS) { putJsonArray("#i") { add(JsonPrimitive(anchor.itemTag)) } })
            add(filter(listOf(COMMENT)) { putJsonArray("#I") { add(JsonPrimitive(anchor.itemTag)) } })
            if (roots.isNotEmpty()) add(filter(KINDS) { putJsonArray("#e") { roots.forEach { add(JsonPrimitive(it)) } } })
        }, relays)
        // Replies to the comments found: a Fountain listener answering another names only that comment.
        val asked = roots.toSet()
        val more = first.map { it.id }.filter { it !in asked }.take(MAX_EVENTS)
        val second = if (more.isEmpty()) emptyList() else
            publisher.query(more.chunked(CHUNK).map { ids -> filter(KINDS) { putJsonArray("#e") { ids.forEach { add(JsonPrimitive(it)) } } } }, relays)
        CommentThread(thread(first + second, anchor, muted.await()), relays)
    }

    /** Names and pictures (kind 0) of [people], newest profile each, also from the indexes and Fountain's relay; https pictures only. */
    suspend fun authors(people: Set<String>, relays: List<String>): Map<String, CommentAuthor> {
        if (people.isEmpty()) return emptyMap()
        val found = publisher.query(people.chunked(CHUNK).map { keys ->
            buildJsonObject {
                putJsonArray("kinds") { add(JsonPrimitive(0)) }
                putJsonArray("authors") { keys.forEach { add(JsonPrimitive(it)) } }
            }
        }, (relays + IdentityRepository.INDEX_RELAYS + FOUNTAIN).distinct())
        return found.filter { it.kind == 0 && it.pubkey in people }.groupBy { it.pubkey }.mapNotNull { (key, events) ->
            val obj = runCatching { json.parseToJsonElement(events.maxBy { it.created_at }.content).jsonObject }.getOrNull() ?: return@mapNotNull null
            fun text(vararg names: String) = names.firstNotNullOfOrNull { n -> (obj[n] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
            key to CommentAuthor(text("display_name", "displayName", "name"), text("picture")?.takeIf { it.startsWith("https://") })
        }.toMap()
    }

    /** The public entries of these people's mute lists (NIP-51, kind 10000). */
    private suspend fun muted(keys: Set<String>, relays: List<String>): Set<String> = coroutineScope {
        keys.map { key -> async { publisher.latest(10000, key, (relays + IdentityRepository.DEFAULT_RELAYS).distinct()) } }.awaitAll()
            .filterNotNull().flatMap { list -> list.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] } }.toSet()
    }

    private fun filter(kinds: List<Int>, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        putJsonArray("kinds") { kinds.forEach { add(JsonPrimitive(it)) } }
        more()
        put("limit", MAX_EVENTS)
    }

    companion object {
        const val COMMENT = 1111
        private val KINDS = listOf(1, COMMENT)
        /** At most this many relays per episode (ADR 0021, 2). */
        const val MAX_RELAYS = 5
        private const val MAX_EVENTS = 500
        private const val CHUNK = 100
        private val LOOKUP = IdentityRepository.DEFAULT_RELAYS + IdentityRepository.INDEX_RELAYS
        /** Fountain keeps its listeners' profiles here, often only here (seen 2026-10-16: 5 of 10 commenters). */
        private const val FOUNTAIN = "wss://relay.fountain.fm"
    }
}

/** The note a comment answers: NIP-22's `e` for kind 1111, NIP-10's reply (else root, else the last unmarked) for kind 1. */
internal fun parentOf(e: Event): String? {
    val es = e.tags.filter { it.size >= 2 && it[0] == "e" }
    return if (e.kind == CommentReader.COMMENT) es.firstOrNull()?.get(1)
    else es.firstOrNull { it.getOrNull(3) == "reply" }?.get(1)
        ?: es.firstOrNull { it.getOrNull(3) == "root" }?.get(1)
        ?: es.lastOrNull { it.getOrNull(3).isNullOrEmpty() }?.get(1)
}

/**
 * Events found → threads. Kept is what names the episode (`i`/`I`) or a show
 * note (`e`), and whatever answers something kept; the show's own notes on the
 * episode are roots, not comments. One level of replies, as in most apps: a
 * deeper answer sits under its top-level comment and names whom it answers.
 * A muted person's comment goes with everything below it.
 */
internal fun thread(events: List<Event>, anchor: CommentAnchor, muted: Set<String>): List<Comment> {
    val all = events.filter { it.kind == 1 || it.kind == CommentReader.COMMENT }.distinctBy { it.id }.sortedBy { it.created_at }
    val roots = anchor.notes.map { it.id }.toMutableSet()
    fun namesEpisode(e: Event) = e.tags.any { it.size >= 2 && (it[0] == "i" || it[0] == "I") && it[1] == anchor.itemTag }
    all.filter { it.kind == 1 && it.pubkey in anchor.showKeys && parentOf(it) == null && namesEpisode(it) }.forEach { roots += it.id }

    val parent = all.associate { it.id to parentOf(it) }
    val kept = linkedMapOf<String, Event>()
    var changed = true
    while (changed) {
        changed = false
        for (e in all) {
            if (e.id in kept || e.id in roots || e.pubkey in muted) continue
            val p = parent[e.id]
            val direct = namesEpisode(e) || e.tags.any { it.size >= 2 && it[0] == "e" && it[1] in roots }
            if (direct && (p == null || p in roots || p in kept || p !in parent) || p != null && p in kept) {
                kept[e.id] = e; changed = true
            }
        }
    }

    fun top(id: String): String {
        var at = id
        while (true) { val p = parent[at]; if (p == null || p !in kept) return at; at = p }
    }
    val byTop = kept.values.groupBy { top(it.id) }
    return byTop.mapNotNull { (topId, members) ->
        val head = kept[topId] ?: return@mapNotNull null
        val replies = members.filter { it.id != topId }.sortedBy { it.created_at }.map { r ->
            val p = parent[r.id]
            Comment(r.id, r.pubkey, r.created_at, r.content, replyTo = p?.takeIf { it != topId }?.let { kept[it]?.pubkey })
        }
        Comment(head.id, head.pubkey, head.created_at, head.content, replies = replies)
    }.sortedByDescending { it.createdAt }
}
