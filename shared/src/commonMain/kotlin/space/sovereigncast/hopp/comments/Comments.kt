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
import space.sovereigncast.hopp.nostr.Nip05
import space.sovereigncast.hopp.nostr.NoteRef
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.noteRef
import space.sovereigncast.hopp.nostr.profileKey
import space.sovereigncast.hopp.sovereigncast.SovereigncastRepository
import kotlin.time.Duration.Companion.milliseconds
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.value.Bolt11

/**
 * Where an episode's comments hang (ADR 0021, 1): its GUID as NIP-73 names it,
 * the notes the show announced it with, and the show's own keys — a note of
 * theirs about the episode is where comments go, not a comment itself.
 */
data class CommentAnchor(
    val episodeGuid: String,
    val podcastGuid: String?,
    val notes: List<NoteRef>,
    val showKeys: Set<String>,
    /** The note a new comment answers: the episode's announcement once it is out,
     *  not the Release Boosting note before it (device feedback 2026-10-16). */
    val answerTo: NoteRef? = notes.firstOrNull(),
    /** To find the show's announcement on the relays where nothing names it (device
     *  feedback 2026-10-17): the episode's title, when it came out, its web pages,
     *  and the show's website, whose NIP-05 `_` key is taken for the show's. */
    val title: String? = null,
    val publishedAt: Long? = null,
    val links: Set<String> = emptySet(),
    val site: String? = null,
    /** The show's name and a page for the episode (its own, else the show's): a comment
     *  quoting no note names them, so other clients show what it is about. */
    val showTitle: String? = null,
    val page: String? = null,
) {
    val itemTag: String get() = "podcast:item:guid:$episodeGuid"
}

/**
 * A comment; [replies] only on a top-level one, oldest first, each naming whom
 * it answers when that is not the top. [kind] and [root] (its NIP-10 root, for
 * kind 1) say how an answer to it is tagged; [likes]: who reacted (NIP-25).
 */
data class Comment(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val content: String,
    val replyTo: String? = null,
    val replies: List<Comment> = emptyList(),
    val kind: Int = 1,
    val root: String? = null,
    val likes: Set<String> = emptySet(),
    /** A zap with a message (kind 9735): its sats; it takes no answers or likes. */
    val sats: Long? = null,
    /** What others zapped on it (NIP-57), in sats. */
    val zapped: Long = 0,
)

data class CommentAuthor(val name: String?, val picture: String?)

private val EMBED = Regex("""nostr:(nevent|note|naddr)1[02-9ac-hj-np-z]+""")
private val TRAILING_SPACE = Regex("""[ \t]+\n""")
private val FOUNTAIN_TAIL = Regex("""\s*https://fountain\.fm/(episode|show)/\S+$""")
/** What Hopp ends a comment with that quotes no note (see [episodeLine]). */
private val HOPP_TAIL = Regex("""\n\n🎧 [^\n]+(\nhttps?://\S+)?$""")

/** "🎧 Show – Episode" and the episode's page: where a comment quotes no note, what
 *  tells other clients — which do not read NIP-73 — which episode it is about. */
internal fun episodeLine(anchor: CommentAnchor): String? {
    val name = listOfNotNull(anchor.showTitle, anchor.title).joinToString(" – ").replace('\n', ' ').takeIf { it.isNotBlank() } ?: return null
    return "🎧 $name" + anchor.page?.let { "\n$it" }.orEmpty()
}

/**
 * What a comment says, as Hopp shows it: without embedded notes
 * (`nostr:nevent…` — Hopp does not show them, the bare code says nothing) and
 * without the link to the episode on Fountain that Fountain ends each comment
 * with, nor the episode's name Hopp ends one with — the listener is on that episode. Empty for a Fountain boost without
 * a message. A public boost's last line "⚡ N sats" goes only where Hopp
 * shows [sats] beside the name, and only when it names that amount.
 */
fun commentBody(content: String, sats: Long? = null): String {
    val text = withoutTails(content)
    return (if (sats != null && boostSats(text) == sats) text.replace(BOOST_LINE, "") else text).trim()
}

private fun withoutTails(content: String) =
    EMBED.replace(content, "").replace(TRAILING_SPACE, "\n").trim().replace(FOUNTAIN_TAIL, "").replace(HOPP_TAIL, "").trim()

/** The last line of a public boost as Hopp writes it ([CommentWriter.boost]): "⚡ 210 sats". */
private val BOOST_LINE = Regex("""(?:^|\n\n)⚡ (\d{1,9}) sats$""")

/**
 * The amount a public boost names in its last line. Only the author's word —
 * anyone can end a note so (device feedback 2026-10-20: "⚡ 210000000 sats"
 * from Amethyst): Hopp pays by keysend, and no receipt backs it (ADR 0021, 6).
 * It goes beside the name only for the listener's own boosts, which Hopp
 * keeps as it posts them ([CommentWriter.boost]); others' stay in the text.
 */
internal fun boostSats(content: String): Long? = BOOST_LINE.find(withoutTails(content))?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }

/** The notes a comment embeds — Fountain embeds the zap receipt of its boost. */
internal fun embeddedNotes(content: String): List<NoteRef> = EMBED.findAll(content).mapNotNull { noteRef(it.value) }.toList()

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
    private val anchors = mutableMapOf<Long, CommentAnchor>()

    /** What the feed, the show's Sovereigncast instance and its Nostr keys say about [episodeId]; null for an unknown one.
     *  Kept while the app runs, like the comments. */
    suspend fun anchor(episodeId: Long): CommentAnchor? = anchors[episodeId] ?: findAnchor(episodeId)?.let { (anchor, complete) ->
        anchor.also { if (complete) anchors[episodeId] = it }
    }

    /** The comments shown last for [episodeId], from the database — nobody is asked; null before the first load.
     *  (The anchor may ask the show's Sovereigncast instance, which knows the listener's episodes anyway.) */
    suspend fun cached(episodeId: Long): CommentThread? = anchor(episodeId)?.let { reader.cached(it) }

    /** The anchor, and whether it is complete — not when the instance could not be asked (offline). */
    private suspend fun findAnchor(episodeId: Long): Pair<CommentAnchor, Boolean>? {
        val item = episodes.item(episodeId) ?: return null
        val details = runCatching { episodes.details(episodeId) }.getOrNull()
        val meta = runCatching { podcasts.podcastMeta(item.podcastId) }.getOrNull()
        val fromFeed = details?.socialInteracts.orEmpty()
            .filter { it.protocol.equals("nostr", ignoreCase = true) || it.uri.startsWith("nostr:") }
            .sortedBy { it.priority ?: Int.MAX_VALUE }
        val asked = runCatching {
            sovereigncast.layer(item.podcastId).first()?.let { sovereigncast.episodeNotes(it, item.guid) }
        }
        val fromInstance = asked.getOrNull().orEmpty()
        // A bare note1 names no author; the instance says who posted it (device feedback 2026-10-16:
        // a comment tagged the Release Boosting note's author instead of the announcement's).
        val notes = (fromFeed.mapNotNull { noteRef(it.uri)?.let { r -> r.copy(author = r.author ?: it.accountId?.let(::profileKey)) } } +
            fromInstance.mapNotNull { noteRef(it.uri)?.let { r -> r.copy(author = r.author ?: it.authorNpub?.let(::profileKey)) } })
            .groupBy { it.id }.map { (id, refs) -> NoteRef(id, refs.flatMap { it.relays }.distinct(), refs.firstNotNullOfOrNull { it.author }) }
        val keys = buildSet {
            fromFeed.mapNotNull { it.accountId?.let(::profileKey) }.forEach(::add)
            fromInstance.mapNotNull { it.authorNpub?.let(::profileKey) }.forEach(::add)
            notes.mapNotNull { it.author }.forEach(::add)
            meta?.txt.orEmpty().filter { it.purpose.equals("nostr", ignoreCase = true) }.mapNotNull { profileKey(it.value) }.forEach(::add)
        }
        // Comments go under the announcement: the instance's post_release note, else the
        // feed's newest (its priorities count up in the order they were posted).
        val answerId = fromInstance.lastOrNull { it.purpose == "post_release" }?.let { noteRef(it.uri)?.id }
            ?: fromFeed.lastOrNull()?.let { noteRef(it.uri)?.id }
            ?: fromInstance.lastOrNull()?.let { noteRef(it.uri)?.id }
        return CommentAnchor(
            item.guid, meta?.podcastGuid, notes, keys, notes.firstOrNull { it.id == answerId } ?: notes.firstOrNull(),
            title = item.title.takeIf { it.isNotBlank() }, publishedAt = item.publishedAt,
            links = setOfNotNull(details?.link?.trim()?.takeIf { it.startsWith("http") }),
            site = Nip05.hostOf(meta?.link),
            showTitle = meta?.title?.takeIf { it.isNotBlank() },
            page = (details?.link ?: meta?.link)?.trim()?.takeIf { it.startsWith("http") },
        ) to asked.isSuccess
    }
}

/**
 * The relay side: the union of replies to the show's notes and kinds 1 and
 * 1111 that name the episode by NIP-73 — so Hopp shows what Fountain's
 * listeners say about it too. [listener]: whose mute list counts besides the show's.
 */
class CommentReader(
    private val publisher: RelayPublisher,
    private val now: () -> Long,
    private val store: CommentStore = MemoryCommentStore(),
    private val listener: () -> String? = { null },
    /** The key a website names for itself (NIP-05 `_`); none asked without it. */
    private val siteKey: suspend (String) -> String? = { null },
    /** The listener's own read relays (NIP-65) — their comments are sure to be there. */
    private val listenerRelays: () -> List<String> = { emptyList() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The comments as last loaded, to show at once while [load] asks for what is new; null before the first load. */
    suspend fun cached(anchor: CommentAnchor): CommentThread? =
        store.seen(anchor.itemTag)?.let { CommentThread(thread(it.events, anchor, it.muted, it.roots), it.relays) }

    /**
     * The comments on [anchor]; muted people (the listener's and the show's
     * lists) never show. Asked of the relays at once, the show's own relays
     * as soon as its list is in; each step waits only [SETTLE] for the rest
     * once one relay has answered. Asked in full each time, not only since
     * the last load (device feedback 2026-10-17: a relay too slow once, and
     * what it held was never asked for again — two Fountain comments on E298);
     * the kept ones show meanwhile. Fountain's relay always among them: its
     * listeners' comments are sure to be there.
     *
     * The show's announcement where nothing names it (device feedback
     * 2026-10-17, Einundzwanzig: a plain note, linked to the episode only by
     * Fountain): the show's notes from around the release, in the first
     * request — or, with no key known, the website's NIP-05 key looked up
     * meanwhile and its notes asked for beside it — and kept as a root where
     * one gives the episode's title or page. Answers to it and its zaps with a
     * message come with the second request, as the comments' answers do.
     */
    suspend fun load(anchor: CommentAnchor): CommentThread = coroutineScope {
        val before = store.seen(anchor.itemTag)
        val startedAt = now()
        val base = ((anchor.notes.flatMap { it.relays }.take(2) + IdentityRepository.DEFAULT_RELAYS).distinct().take(MAX_RELAYS) + listenerRelays() + FOUNTAIN).distinct()
        val showRelays = async {
            anchor.showKeys.firstOrNull()?.let { key ->
                publisher.latest(10002, key, LOOKUP, SETTLE)?.tags.orEmpty()
                    .filter { it.size >= 2 && it[0] == "r" && it[1].startsWith("wss://") && (it.size < 3 || it[2] == "write") }
                    .map { it[1].trimEnd('/') }
            }.orEmpty()
        }
        val muted = async { muted(anchor.showKeys + listOfNotNull(listener()), base) }

        val roots = anchor.notes.map { it.id }
        val filters = buildList {
            add(filter(KINDS) { putJsonArray("#i") { add(JsonPrimitive(anchor.itemTag)) } })
            add(filter(listOf(COMMENT)) { putJsonArray("#I") { add(JsonPrimitive(anchor.itemTag)) } })
            if (roots.isNotEmpty()) add(filter(KINDS) { putJsonArray("#e") { roots.forEach { add(JsonPrimitive(it)) } } })
            announcements(anchor, anchor.showKeys)?.let(::add)
        }
        // No key for the show: its website's, looked up while the relays are asked.
        val siteNotes = async {
            val key = anchor.site?.takeIf { anchor.showKeys.isEmpty() && anchor.publishedAt != null }?.let { siteKey(it) }
            key?.let { k -> announcements(anchor, setOf(k))?.let { f -> publisher.query(listOf(f), base, SETTLE) } }.orEmpty()
        }
        val fromBase = async { publisher.query(filters, base, SETTLE) }
        val extra = showRelays.await().filter { it !in base }.take((MAX_RELAYS + 1 - base.size).coerceAtLeast(0))
        val found = (fromBase.await() + (if (extra.isEmpty()) emptyList() else publisher.query(filters, extra, SETTLE)) + siteNotes.await()).distinctBy { it.id }
        // Of the show's notes from around the release, the one about this episode is a root; the others go.
        val authors = anchor.showKeys + siteNotes.await().map { it.pubkey }
        val announced = found.filter { it.kind == 1 && it.pubkey in authors && parentOf(it) == null && !quotes(it) && announces(it, anchor) }.map { it.id }.toSet()
        val first = found.filter { e -> e.id in announced || !(e.kind == 1 && e.pubkey in authors && parentOf(e) == null && !names(e, anchor)) }
        val relays = base + extra
        val allRoots = roots.toSet() + announced + before?.roots.orEmpty()
        // Replies to the comments found: a Fountain listener answering another names only that comment.
        // Replies to the comments known before too: new answers to an old comment.
        val more = (before?.events.orEmpty() + first).filter { it.kind == 1 || it.kind == COMMENT }.map { it.id }.distinct().filter { it !in roots }.take(MAX_EVENTS)
        // Their likes (NIP-25) and zaps (NIP-57) in the same request, and zaps on the show's notes.
        val secondFilters = more.chunked(CHUNK).map { ids -> filter(KINDS + REACTION + ZAP_RECEIPT) { putJsonArray("#e") { ids.forEach { add(JsonPrimitive(it)) } } } } +
            listOfNotNull(allRoots.takeIf { it.isNotEmpty() }?.let { ids -> filter(listOf(ZAP_RECEIPT)) { putJsonArray("#e") { ids.forEach { add(JsonPrimitive(it)) } } } })
        val second = if (secondFilters.isEmpty()) emptyList() else publisher.query(secondFilters, relays, SETTLE)
        val events = (before?.events.orEmpty() + first + second).distinctBy { it.id }
        val mutedNow = muted.await()
        store.save(anchor.itemTag, CommentStore.Seen(first + second, (before?.relays.orEmpty() + relays).distinct(), mutedNow, startedAt, before?.roots.orEmpty() + announced))
        CommentThread(thread(events, anchor, mutedNow, before?.roots.orEmpty() + announced), relays)
    }

    /**
     * The sats behind comments, by comment id: Fountain posts a boost as a
     * comment that embeds its zap receipt (NIP-57, kind 9735). Counted only
     * when the receipt holds up: its zap request is by the comment's author and
     * names the same amount, and an invoice with a description hash commits to
     * that request (Fountain's have none). The amount is the invoice's.
     */
    suspend fun boosts(thread: CommentThread): Map<String, Long> {
        val all = thread.comments.flatMap { listOf(it) + it.replies }
        // A zap with a message carries its own amount, checked as it became a comment.
        val zaps = all.mapNotNull { c -> c.sats?.let { c.id to it } }.toMap()
        val checked = store.boosts(all.map { it.id })
        val known = checked.mapNotNull { (id, n) -> n?.let { id to it } }.toMap() + zaps
        val refs = all.filter { it.id !in checked && it.id !in zaps }.flatMap { c -> embeddedNotes(c.content).map { c to it } }
        if (refs.isEmpty()) return known
        val receipts = publisher.query(refs.map { it.second.id }.distinct().chunked(CHUNK).map { ids ->
            buildJsonObject {
                putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("kinds") { add(JsonPrimitive(ZAP_RECEIPT)) }
            }
        }, (thread.relays + refs.flatMap { it.second.relays } + FOUNTAIN).distinct(), SETTLE_NAMES).associateBy { it.id }
        // A receipt not found (yet) is asked for again next time; one that does not hold up is not.
        val found = refs.filter { receipts[it.second.id] != null }
            .groupBy({ it.first.id }, { (comment, ref) -> zapSats(receipts.getValue(ref.id), comment.pubkey) })
            .mapValues { (_, amounts) -> amounts.filterNotNull().takeIf { it.isNotEmpty() }?.sum() }
        store.saveBoosts(found)
        return known + found.mapNotNull { (id, n) -> n?.let { id to it } }
    }

    private fun zapSats(receipt: Event, sender: String): Long? = zap(receipt)?.takeIf { it.first.pubkey == sender }?.second

    /** Names and pictures (kind 0) of [people], newest profile each, also from the indexes and Fountain's relay; https pictures only. */
    suspend fun authors(people: Set<String>, relays: List<String>): Map<String, CommentAuthor> {
        val stored = store.authors(people)
        val known = stored.mapValues { it.value.first }
        // Looked up again after a week: names and pictures change.
        val missing = people - stored.filterValues { now() - it.second < AUTHOR_TTL }.keys
        if (missing.isEmpty()) return known
        val found = publisher.query(missing.chunked(CHUNK).map { keys ->
            buildJsonObject {
                putJsonArray("kinds") { add(JsonPrimitive(0)) }
                putJsonArray("authors") { keys.forEach { add(JsonPrimitive(it)) } }
            }
        }, (relays + IdentityRepository.INDEX_RELAYS + FOUNTAIN).distinct(), SETTLE_NAMES)
        return known + found.filter { it.kind == 0 && it.pubkey in missing }.groupBy { it.pubkey }.mapNotNull { (key, events) ->
            val obj = runCatching { json.parseToJsonElement(events.maxBy { it.created_at }.content).jsonObject }.getOrNull() ?: return@mapNotNull null
            fun text(vararg names: String) = names.firstNotNullOfOrNull { n -> (obj[n] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
            key to CommentAuthor(text("display_name", "displayName", "name"), text("picture")?.takeIf { it.startsWith("https://") })
        }.toMap().also { store.saveAuthors(it, now()) }
    }

    /** The public entries of these people's mute lists (NIP-51, kind 10000). */
    private suspend fun muted(keys: Set<String>, relays: List<String>): Set<String> = coroutineScope {
        keys.map { key -> async { publisher.latest(10000, key, relays, SETTLE) } }.awaitAll()
            .filterNotNull().flatMap { list -> list.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] } }.toSet()
    }

    /** The notes of [keys] from two days before the episode came out to a week after — where its announcement is. */
    private fun announcements(anchor: CommentAnchor, keys: Set<String>): JsonObject? {
        val at = anchor.publishedAt ?: return null
        if (keys.isEmpty() || anchor.title == null && anchor.links.isEmpty()) return null
        return buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(1)) }
            putJsonArray("authors") { keys.forEach { add(JsonPrimitive(it)) } }
            put("since", at - 2 * DAY); put("until", at + 7 * DAY); put("limit", 30)
        }
    }

    private fun filter(kinds: List<Int>, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        putJsonArray("kinds") { kinds.forEach { add(JsonPrimitive(it)) } }
        more()
        put("limit", MAX_EVENTS)
    }

    companion object {
        const val COMMENT = 1111
        const val REACTION = 7
        const val ZAP_RECEIPT = 9735
        private const val DAY = 24 * 3600L
        private val KINDS = listOf(1, COMMENT)
        /** At most this many relays per episode (ADR 0021, 2), Fountain's besides. */
        const val MAX_RELAYS = 5
        private const val AUTHOR_TTL = 7 * 24 * 3600L
        /** How long the other relays get once the first has answered. */
        private val SETTLE = 1000.milliseconds
        /** Longer for names: Fountain's relay, often the only one with a commenter's
         *  profile, answers after the indexes (live 2026-10-16). Comments show meanwhile. */
        private val SETTLE_NAMES = 3000.milliseconds
        private const val MAX_EVENTS = 500
        private const val CHUNK = 100
        private val LOOKUP = IdentityRepository.DEFAULT_RELAYS + IdentityRepository.INDEX_RELAYS
        /** Fountain keeps its listeners' profiles here, often only here (seen 2026-10-16: 5 of 10 commenters). */
        private const val FOUNTAIN = "wss://relay.fountain.fm"
    }
}

private val zapJson = Json { ignoreUnknownKeys = true }

/**
 * A zap receipt (NIP-57, kind 9735) → its zap request and the sats: only when
 * the request is signed, names the same amount as the invoice, and an invoice
 * with a description hash commits to it (Fountain's have none).
 */
internal fun zap(receipt: Event): Pair<Event, Long>? = runCatching {
    if (receipt.kind != CommentReader.ZAP_RECEIPT) return null
    val description = receipt.tag("description") ?: return null
    val request = zapJson.decodeFromString(Event.serializer(), description)
    if (request.kind != 9734 || !request.isValid()) return null
    val invoice = Bolt11.parse(receipt.tag("bolt11") ?: return null)
    val msat = invoice.amountMsat ?: return null
    // Fountain's invoices carry no description hash (live 2026-10-16); one that does must match.
    invoice.descriptionHashHex?.let { if (it != Sha256.digest(description.encodeToByteArray()).toHex()) return null }
    request.tag("amount")?.toLongOrNull()?.let { if (it != msat) return null }
    (msat / 1000).takeIf { it > 0 }?.let { request to it }
}.getOrNull()

/** A note quoting another (`q`) is a comment, even the podcaster's own from Hopp. */
private fun quotes(e: Event) = e.tags.any { it.size >= 2 && it[0] == "q" }

/** Whether [e] names the episode by NIP-73. */
private fun names(e: Event, anchor: CommentAnchor) = e.tags.any { it.size >= 2 && (it[0] == "i" || it[0] == "I") && it[1] == anchor.itemTag }

/** Letters and digits only, lower case: titles compared past punctuation and dashes. */
private fun plain(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

/**
 * Whether a show's note announces this episode: it names it by NIP-73, links
 * one of its pages, or gives its title — whole, or without a leading number
 * ("#336 - Et is kalt …" is announced as "News Episode 336 - Et is kalt …").
 */
internal fun announces(e: Event, anchor: CommentAnchor): Boolean {
    if (names(e, anchor)) return true
    val urls = e.tags.filter { it.size >= 2 && it[0] == "r" }.map { it[1] }
    if (anchor.links.any { link -> link in e.content || link in urls }) return true
    val title = anchor.title ?: return false
    val text = plain(e.content)
    val core = plain(title.replace(LEADING_NUMBER, ""))
    return plain(title).let { it.length >= 8 && it in text } || core.length >= 12 && core in text
}

private val LEADING_NUMBER = Regex("""^\W*(?:(?:episode|folge|ep\.?|no\.?|nr\.?)\s*)?#?\d+\s*[-–—:|.]?\s*""", RegexOption.IGNORE_CASE)

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
internal fun thread(events: List<Event>, anchor: CommentAnchor, muted: Set<String>, found: Set<String> = emptySet()): List<Comment> {
    val all = events.filter { it.kind == 1 || it.kind == CommentReader.COMMENT }.distinctBy { it.id }.sortedBy { it.created_at }
    val roots = (anchor.notes.map { it.id } + found).toMutableSet()
    fun namesEpisode(e: Event) = e.tags.any { it.size >= 2 && (it[0] == "i" || it[0] == "I") && it[1] == anchor.itemTag }
    all.filter { it.kind == 1 && it.pubkey in anchor.showKeys && parentOf(it) == null && namesEpisode(it) && !quotes(it) }.forEach { roots += it.id }

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
    // Likes: a reaction other than "-" on a kept comment (NIP-25: the last e tag is its target).
    val likes = events.filter { it.kind == CommentReader.REACTION && it.content != "-" && it.pubkey !in muted }
        .mapNotNull { r -> r.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)?.takeIf { it in kept }?.let { it to r.pubkey } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    fun top(id: String): String {
        var at = id
        while (true) { val p = parent[at]; if (p == null || p !in kept) return at; at = p }
    }
    fun rootOf(e: Event) = e.tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "root" }?.get(1)
    // Zaps on a kept comment (NIP-57: the e tag is what was zapped), counted where the receipt holds up.
    val zapped = events.filter { it.kind == CommentReader.ZAP_RECEIPT }.distinctBy { it.id }
        .mapNotNull { r -> r.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)?.takeIf { it in kept }?.let { id -> zap(r)?.let { id to it } } }
        .filter { (_, z) -> z.first.pubkey !in muted }
        .groupBy({ it.first }, { it.second.second }).mapValues { it.value.sum() }
    fun comment(e: Event, replyTo: String? = null, replies: List<Comment> = emptyList()) =
        Comment(e.id, e.pubkey, e.created_at, e.content, replyTo, replies, e.kind, rootOf(e), likes[e.id].orEmpty(), zapped = zapped[e.id] ?: 0)
    val byTop = kept.values.groupBy { top(it.id) }
    val threads = byTop.mapNotNull { (topId, members) ->
        val head = kept[topId] ?: return@mapNotNull null
        val replies = members.filter { it.id != topId }.sortedBy { it.created_at }.map { r ->
            comment(r, replyTo = parent[r.id]?.takeIf { it != topId }?.let { kept[it]?.pubkey })
        }
        comment(head, replies = replies)
    }
    // Zaps on the show's notes with a message: comments with their sats (device feedback
    // 2026-10-17) — not those a comment embeds already (Fountain's boosts).
    val embedded = kept.values.flatMap { embeddedNotes(it.content) }.map { it.id }.toSet()
    val zaps = events.filter { it.kind == CommentReader.ZAP_RECEIPT && it.id !in embedded && it.tags.any { t -> t.size >= 2 && t[0] == "e" && t[1] in roots } }
        .distinctBy { it.id }
        .mapNotNull { r -> zap(r)?.let { (request, sats) -> Comment(r.id, request.pubkey, request.created_at, request.content, kind = CommentReader.ZAP_RECEIPT, sats = sats) } }
        .filter { it.content.isNotBlank() && it.pubkey !in muted }
    return (threads + zaps).sortedByDescending { it.createdAt }
}

/** Where loaded comments, names and boost amounts are kept between loads. */
interface CommentStore {
    /** An episode's events as found so far, the relays asked, who was muted, when it was last asked,
     *  and the show's notes on it found on the relays. */
    class Seen(val events: List<Event>, val relays: List<String>, val muted: Set<String>, val at: Long, val roots: Set<String> = emptySet())

    suspend fun seen(itemTag: String): Seen?
    /** Adds [seen]'s events to those kept; the rest replaces what was there. */
    suspend fun save(itemTag: String, seen: Seen)
    /** Names kept, with when each was looked up. */
    suspend fun authors(keys: Set<String>): Map<String, Pair<CommentAuthor, Long>>
    suspend fun saveAuthors(found: Map<String, CommentAuthor>, at: Long)
    /** Comments whose receipt was checked: the sats, or null where it did not hold up. */
    suspend fun boosts(ids: Collection<String>): Map<String, Long?>
    suspend fun saveBoosts(found: Map<String, Long?>)
}

/** For a run without a database (tests, the live check). */
class MemoryCommentStore : CommentStore {
    private val seen = mutableMapOf<String, CommentStore.Seen>()
    private val authors = mutableMapOf<String, Pair<CommentAuthor, Long>>()
    private val boosts = mutableMapOf<String, Long?>()

    override suspend fun seen(itemTag: String) = seen[itemTag]
    override suspend fun save(itemTag: String, seen: CommentStore.Seen) {
        val events = (this.seen[itemTag]?.events.orEmpty() + seen.events).distinctBy { it.id }
        this.seen[itemTag] = CommentStore.Seen(events, seen.relays, seen.muted, seen.at, seen.roots)
    }
    override suspend fun authors(keys: Set<String>) = authors.filterKeys { it in keys }
    override suspend fun saveAuthors(found: Map<String, CommentAuthor>, at: Long) { found.forEach { (k, v) -> authors[k] = v to at } }
    override suspend fun boosts(ids: Collection<String>) = ids.filter { it in boosts }.associateWith { boosts[it] }
    override suspend fun saveBoosts(found: Map<String, Long?>) { boosts += found }
}
