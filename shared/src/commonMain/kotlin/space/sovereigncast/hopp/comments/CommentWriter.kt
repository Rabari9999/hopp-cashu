package space.sovereigncast.hopp.comments

import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.NostrSigner
import space.sovereigncast.hopp.nostr.NoteRef
import space.sovereigncast.hopp.nostr.nevent
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.UnsignedEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/** No relay took the event — nothing was published. */
class NotPublished : Exception("no relay took the event")

/** No Nostr identity to sign with. */
class NoIdentity : Exception("no Nostr identity")

/** A replaceable list (mutes, relays) was found nowhere, and not every relay answered: not known, so not written anew. */
class ListNotRead : Exception("the list could not be read from every relay")

/** NIP-56's report types Hopp offers. */
enum class ReportType(val tag: String) { Spam("spam"), Profanity("profanity"), Illegal("illegal"), Other("other") }

/**
 * Writing comments (ADR 0021, stage 3), signed with the listener's identity
 * and published to their write relays (NIP-65) and the relays the comments
 * came from, so readers of this episode find it.
 * - A new comment stands on its own, as Fountain's do (device feedback
 *   2026-10-17: as a reply to the show's note Fountain did not show it): a
 *   kind-1 note with the episode's and the show's NIP-73 tags; the show's
 *   note, if there is one, quoted (`q`, and as `nostr:nevent…` at the end of
 *   the text, NIP-18) and its author named (`p`), so the podcaster is told
 *   and other clients show which episode it is about (device feedback
 *   2026-10-17: without the mention it stood there with no reference, where
 *   Fountain's own end in a link to the episode). Hopp leaves the mention out
 *   when it shows the comment. Without a note to quote the comment ends in
 *   the show's and the episode's name and the episode's page instead (device
 *   feedback 2026-10-19: a boost on Münzweg, which posts no notes, stood in
 *   Primal with nothing to say what it was about).
 * - An answer follows the kind of the comment it answers: kind 1 as a NIP-10
 *   reply, kind 1111 (NIP-22) with the episode as its root scope.
 */
class CommentWriter(
    private val publisher: RelayPublisher,
    private val identity: IdentityRepository,
    private val store: CommentStore,
    private val now: () -> Long,
    /** Where sending goes wrong is shown (maintainer 2026-11-10); null: nowhere. */
    private val activity: space.sovereigncast.hopp.nostr.NostrActivity? = null,
    /** A comment (or a boost as one) went out — the listening statistics count it. */
    private val posted: suspend () -> Unit = {},
) {
    /** One mute at a time: each reads the list the one before it wrote. */
    private val muting = Mutex()

    /** A comment on the episode, or an answer to [to]; kept at once, so it shows without asking again. */
    suspend fun comment(anchor: CommentAnchor, text: String, relays: List<String>, to: Comment? = null): Event {
        val body = text.trim()
        require(body.isNotEmpty())
        val hint = relays.firstOrNull().orEmpty()
        val episode = buildList {
            add(listOf("i", anchor.itemTag)); add(listOf("k", "podcast:item:guid"))
            anchor.podcastGuid?.let { add(listOf("i", "podcast:guid:$it")); add(listOf("k", "podcast:guid")) }
        }
        val note = anchor.answerTo
        val unsigned = when {
            to != null && to.kind == CommentReader.COMMENT -> UnsignedEvent(CommentReader.COMMENT, body, listOf(
                listOf("I", anchor.itemTag), listOf("K", "podcast:item:guid"),
                listOf("e", to.id, hint, to.pubkey), listOf("k", "${CommentReader.COMMENT}"), listOf("p", to.pubkey),
            ), now())
            to != null -> {
                val root = to.root ?: to.id
                UnsignedEvent(1, body, listOf(listOf("e", root, hint, "root"), listOf("e", to.id, hint, "reply"), listOf("p", to.pubkey)) + episode, now())
            }
            else -> {
                val show = note?.author ?: anchor.showKeys.firstOrNull()
                val quoted = note?.let { NoteRef(it.id, listOfNotNull(it.relays.firstOrNull() ?: hint.takeIf(String::isNotEmpty)), it.author ?: show) }
                val quote = quoted?.let { listOf("q", it.id, it.relays.firstOrNull().orEmpty()) + listOfNotNull(it.author) }
                val text = if (quoted != null) "$body\n\nnostr:${nevent(quoted)}"
                    else episodeLine(anchor)?.let { "$body\n\n$it" } ?: body
                UnsignedEvent(1, text, episode + listOfNotNull(quote, show?.let { listOf("p", it) }), now())
            }
        }
        // Fountain's relay too: where Fountain's listeners' comments are, Hopp's should be.
        val event = publish(unsigned, listOf(FOUNTAIN) + relays + anchor.notes.flatMap { it.relays })
        keep(anchor, event, relays)
        runCatching { posted() }
        return event
    }

    /**
     * A boost made public (ADR 0021, 6): a comment like any other — on its
     * own, with the episode's NIP-73 tags — with the amount in its
     * text, as every client shows it. Nostr readers cannot check the amount;
     * it is the listener's word, as Fountain's own boost notes are. This
     * device paid it, so here it is kept as the comment's boost and shown
     * beside the name, as a zap's.
     */
    suspend fun boost(anchor: CommentAnchor, sats: Long, message: String, relays: List<String>): Event {
        val line = "⚡ $sats sats"
        return comment(anchor, if (message.isBlank()) line else "${message.trim()}\n\n$line", relays)
            .also { store.saveBoosts(mapOf(it.id to sats)) }
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
        store.save(anchor.itemTag, CommentStore.Seen(listOf(event), before?.relays ?: relays, before?.muted.orEmpty(), before?.at ?: 0, before?.roots.orEmpty()))
    }

    /**
     * [pubkey] onto the listener's mute list (NIP-51, kind 10000), which other
     * clients honour too. The list is read first and written back with all it
     * held — the encrypted private part untouched: Hopp cannot read it, and
     * overwriting it would unmute everyone in it.
     */
    suspend fun mute(pubkey: String) = muting.withLock<Unit> {
        val me = identity.identity.value ?: throw NoIdentity()
        val mine = mine()
        val relays = (mine + LOOKUP).distinct()
        // Nothing within the settle time may only mean that the relay holding the
        // list is slow — an index relay says "none" at once. Every relay is asked
        // to the end before a new list goes out over the old one (code review
        // 2026-10-17, H4). And "none" counts only when every relay said so: with
        // one not answering, the list may sit there, with its private part — a
        // new one would replace it (security audit 2026-10-20, M-04).
        val quick = publisher.latestChecked(MUTE_LIST, me.pubkey, relays, 3000.milliseconds)
        val read = if (quick.event != null) quick else publisher.latestChecked(MUTE_LIST, me.pubkey, relays)
        val current = read.event
        if (current == null && !read.complete) throw ListNotRead()
        val tags = current?.tags.orEmpty()
        if (tags.any { it.size >= 2 && it[0] == "p" && it[1] == pubkey }) return@withLock
        publish(UnsignedEvent(MUTE_LIST, current?.content.orEmpty(), tags + listOf(listOf("p", pubkey)), now()), mine + LOOKUP)
    }

    /** A NIP-56 report (kind 1984) on [comment]. */
    suspend fun report(comment: Comment, type: ReportType, relays: List<String>) {
        publish(UnsignedEvent(REPORT, "", listOf(listOf("e", comment.id, type.tag), listOf("p", comment.pubkey, type.tag)), now()), relays)
    }

    private suspend fun publish(unsigned: UnsignedEvent, extra: List<String>): Event {
        val signer: NostrSigner = identity.signer() ?: throw NoIdentity()
        val event = signer.sign(unsigned)
        val to = (mine() + extra).distinct().take(MAX_PUBLISH)
        val took = if (activity == null) publisher.publish(event, to)
            else activity.track(space.sovereigncast.hopp.nostr.NostrActivity.Area.Comment, send = true) { publisher.publish(event, to) }
        if (took.isEmpty()) throw NotPublished()
        return event
    }

    /** The listener's write relays (NIP-65) — their own list, which they can change in Hopp (device feedback 2026-10-19). */
    private suspend fun mine(): List<String> {
        if (identity.identity.value == null) throw NoIdentity()
        return identity.writeRelays()
    }

    companion object {
        private const val MUTE_LIST = 10000
        private const val REPORT = 1984
        private const val MAX_PUBLISH = 8
        private const val FOUNTAIN = "wss://relay.fountain.fm"
        private val LOOKUP = IdentityRepository.DEFAULT_RELAYS + IdentityRepository.INDEX_RELAYS
    }
}
