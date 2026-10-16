package space.sovereigncast.hopp.comments

import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.NostrSigner
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.UnsignedEvent
import kotlin.time.Duration.Companion.milliseconds

/** No relay took the event — nothing was published. */
class NotPublished : Exception("no relay took the event")

/** No Nostr identity to sign with. */
class NoIdentity : Exception("no Nostr identity")

/** NIP-56's report types Hopp offers. */
enum class ReportType(val tag: String) { Spam("spam"), Profanity("profanity"), Illegal("illegal"), Other("other") }

/**
 * Writing comments (ADR 0021, stage 3), signed with the listener's identity
 * and published to their write relays (NIP-65) and the relays the comments
 * came from, so readers of this episode find it.
 * - On an episode with a show note: a kind-1 reply to it (NIP-10) — the
 *   podcaster sees it in the thread of their own note, in any client.
 * - Without one: a kind-1111 comment (NIP-22) on the episode's GUID.
 * - An answer follows the kind of the comment it answers.
 * Kind-1 posts carry the episode's and the show's NIP-73 tags, kind 1111 the
 * episode's as its root and parent scope.
 */
class CommentWriter(
    private val publisher: RelayPublisher,
    private val identity: IdentityRepository,
    private val store: CommentStore,
    private val now: () -> Long,
) {
    private var writeRelays: Pair<String, List<String>>? = null

    /** A comment on the episode, or an answer to [to]; kept at once, so it shows without asking again. */
    suspend fun comment(anchor: CommentAnchor, text: String, relays: List<String>, to: Comment? = null): Event {
        val body = text.trim()
        require(body.isNotEmpty())
        val hint = relays.firstOrNull().orEmpty()
        val episode = buildList {
            add(listOf("i", anchor.itemTag)); add(listOf("k", "podcast:item:guid"))
            anchor.podcastGuid?.let { add(listOf("i", "podcast:guid:$it")); add(listOf("k", "podcast:guid")) }
        }
        val note = anchor.notes.firstOrNull()
        val unsigned = when {
            to != null && to.kind == CommentReader.COMMENT -> UnsignedEvent(CommentReader.COMMENT, body, listOf(
                listOf("I", anchor.itemTag), listOf("K", "podcast:item:guid"),
                listOf("e", to.id, hint, to.pubkey), listOf("k", "${CommentReader.COMMENT}"), listOf("p", to.pubkey),
            ), now())
            to != null -> {
                val root = to.root ?: to.id
                UnsignedEvent(1, body, listOf(listOf("e", root, hint, "root"), listOf("e", to.id, hint, "reply"), listOf("p", to.pubkey)) + episode, now())
            }
            note != null -> {
                val show = note.author ?: anchor.showKeys.firstOrNull()
                UnsignedEvent(1, body, listOf(listOf("e", note.id, note.relays.firstOrNull() ?: hint, "root")) + listOfNotNull(show?.let { listOf("p", it) }) + episode, now())
            }
            else -> UnsignedEvent(CommentReader.COMMENT, body, listOf(
                listOf("I", anchor.itemTag), listOf("K", "podcast:item:guid"), listOf("i", anchor.itemTag), listOf("k", "podcast:item:guid"),
            ), now())
        }
        val event = publish(unsigned, relays + anchor.notes.flatMap { it.relays })
        keep(anchor, event, relays)
        return event
    }

    /** A like on [comment] (NIP-25), kept at once. */
    suspend fun like(anchor: CommentAnchor, comment: Comment, relays: List<String>): Event {
        val event = publish(UnsignedEvent(CommentReader.REACTION, "+", listOf(
            listOf("e", comment.id, relays.firstOrNull().orEmpty(), comment.pubkey), listOf("p", comment.pubkey), listOf("k", "${comment.kind}"),
        ), now()), relays)
        keep(anchor, event, relays)
        return event
    }

    /** What the listener published, among the episode's comments — the next load needs not find it. */
    private suspend fun keep(anchor: CommentAnchor, event: Event, relays: List<String>) {
        val before = store.seen(anchor.itemTag)
        store.save(anchor.itemTag, CommentStore.Seen(listOf(event), before?.relays ?: relays, before?.muted.orEmpty(), before?.at ?: 0))
    }

    /**
     * [pubkey] onto the listener's mute list (NIP-51, kind 10000), which other
     * clients honour too. The list is read first and written back with all it
     * held — the encrypted private part untouched: Hopp cannot read it, and
     * overwriting it would unmute everyone in it.
     */
    suspend fun mute(pubkey: String) {
        val me = identity.identity.value ?: throw NoIdentity()
        val mine = mine()
        val current = publisher.latest(MUTE_LIST, me.pubkey, (mine + LOOKUP).distinct(), 3000.milliseconds)
        val tags = current?.tags.orEmpty()
        if (tags.any { it.size >= 2 && it[0] == "p" && it[1] == pubkey }) return
        publish(UnsignedEvent(MUTE_LIST, current?.content.orEmpty(), tags + listOf(listOf("p", pubkey)), now()), mine + LOOKUP)
    }

    /** A NIP-56 report (kind 1984) on [comment]. */
    suspend fun report(comment: Comment, type: ReportType, relays: List<String>) {
        publish(UnsignedEvent(REPORT, "", listOf(listOf("e", comment.id, type.tag), listOf("p", comment.pubkey, type.tag)), now()), relays)
    }

    private suspend fun publish(unsigned: UnsignedEvent, extra: List<String>): Event {
        val signer: NostrSigner = identity.signer() ?: throw NoIdentity()
        val event = signer.sign(unsigned)
        val took = publisher.publish(event, (mine() + extra).distinct().take(MAX_PUBLISH))
        if (took.isEmpty()) throw NotPublished()
        return event
    }

    /** The listener's write relays (NIP-65), looked up once; Hopp's own for a key made here or none found. */
    private suspend fun mine(): List<String> {
        val me = identity.identity.value ?: throw NoIdentity()
        writeRelays?.takeIf { it.first == me.pubkey }?.let { return it.second }
        val found = if (me.holdsKey) emptyList() else publisher.latest(10002, me.pubkey, LOOKUP, 2000.milliseconds)?.tags.orEmpty()
            .filter { it.size >= 2 && it[0] == "r" && it[1].startsWith("wss://") && (it.size < 3 || it[2] == "write") }
            .map { it[1].trimEnd('/') }.take(4)
        return found.ifEmpty { IdentityRepository.DEFAULT_RELAYS }.also { writeRelays = me.pubkey to it }
    }

    companion object {
        private const val MUTE_LIST = 10000
        private const val REPORT = 1984
        private const val MAX_PUBLISH = 8
        private val LOOKUP = IdentityRepository.DEFAULT_RELAYS + IdentityRepository.INDEX_RELAYS
    }
}
