package space.sovereigncast.hopp.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * One entry of a sync document (ADR 0021, 5): a show, an episode's progress, a
 * place in "Up next", a setting. [t] is when it last changed, in milliseconds;
 * [del] marks a removal; [d] is what the document's owner keeps in it. [out]:
 * for a removal, when it first went out — it is kept that long from then, so
 * one made offline and sent months later is not already too old to count
 * (delta audit 2 2026-10-22, HN-05). It plays no part in which entry wins.
 */
@Serializable
data class SyncEntry(val t: Long, val del: Boolean = false, val d: JsonObject = JsonObject(emptyMap()), val out: Long? = null) {
    /** Whether this entry wins over [other] for the same key: the newer one, and
     *  for the same time an order every device agrees on, so all end alike. */
    fun winsOver(other: SyncEntry): Boolean = when {
        t != other.t -> t > other.t
        del != other.del -> del
        else -> d.toString() > other.d.toString()
    }
}

/** What merging the relays' entries into this device's gave. */
data class Merged(
    /** Entries the relays had newer than this device: to apply here. */
    val fromThem: Map<String, SyncEntry>,
    /** Whether this device has entries the relays lack or hold older: to publish. */
    val theyLack: Boolean,
    /** Everything, merged. */
    val all: Map<String, SyncEntry>,
)

object SyncMerge {
    /** Per entry, newest wins — not per document, so two devices never overwrite each other. */
    fun merge(mine: Map<String, SyncEntry>, theirs: Map<String, SyncEntry>): Merged {
        val fromThem = theirs.filter { (key, e) -> mine[key]?.let { e.winsOver(it) } ?: true }
        val theyLack = mine.any { (key, e) -> theirs[key]?.let { e.winsOver(it) } ?: true }
        return Merged(fromThem, theyLack, mine + fromThem)
    }
}

/** A document a newer Hopp wrote: left alone, never overwritten with less. */
class SyncNewerVersion(val version: Int) : Exception("sync document version $version")

/** A document larger than its [SyncCodec.MAX_PARTS] parts hold: not sent at all, rather than cut. */
class SyncTooLarge(val doc: String) : Exception("sync document $doc is larger than ${SyncCodec.MAX_PARTS} parts")

/** The relays held a document only in part (a part missing, of another revision, or not opening):
 *  what arrived is taken, but nothing is sent over it — it would drop the rest (audit 2026-10-22, H-02). */
class SyncIncomplete(val doc: String) : Exception("sync document $doc arrived only in part")

/** Too few relays answered to tell what they hold ([answered] of [asked]): nothing is applied or sent. */
class SyncNotReached(val answered: Int = 0, val asked: Int = 0) : Exception("$answered of $asked relays answered")

/** What a read of a document came to (audit 2026-10-22, H-02): only [Complete] and [Absent] may be written over —
 *  each read from a majority of the relays (delta audit 2026-10-22, HN-02/HN-03). */
sealed interface Fetched {
    /** Every part of one revision, and no part newer than it; [newestAt]: the newest event's time,
     *  seconds — a new version must be later. */
    data class Complete(val entries: Map<String, SyncEntry>, val newestAt: Long) : Fetched
    /** A majority of the relays answered to the end, and none holds the document. */
    data object Absent : Fetched
    /** Some of it, or from too few relays: [entries] may be applied, never published over. */
    data class Incomplete(val entries: Map<String, SyncEntry>, val newestAt: Long) : Fetched
    /** Too few relays answered to tell: [answered] of [asked]. */
    data class Unknown(val answered: Int, val asked: Int) : Fetched
}

/**
 * How a document travels: JSON, NIP-44-encrypted, in kind-30078 events whose
 * `d` tag names it. A long document goes in parts (`hopp/progress`,
 * `hopp/progress#2` …) that share a revision — parts of different revisions
 * are never mixed.
 */
object SyncCodec {
    const val VERSION = 1
    /**
     * What one part's JSON may take. The event must stay under 64 KiB, strfry's
     * default (nos.lol and many others; maintainer 2026-10-30: "event too large:
     * 87839" with 60 000): NIP-44 pads this to 40 960 bytes, base64 makes it
     * ~54 700, the event around it ~55 100.
     */
    const val MAX_PART_BYTES = 40_000
    /** The event size strfry takes by default (`events.maxEventSize`). */
    const val MAX_EVENT_BYTES = 65_536
    const val MAX_PARTS = 16

    @Serializable
    data class Part(val v: Int = VERSION, val rev: String, val part: Int = 1, val parts: Int = 1, val entries: Map<String, SyncEntry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun dTag(doc: String, part: Int = 1) = if (part == 1) "hopp/$doc" else "hopp/$doc#$part"

    fun dTags(doc: String) = (1..MAX_PARTS).map { dTag(doc, it) }

    fun encode(part: Part): String = json.encodeToString(Part.serializer(), part)

    fun decode(text: String): Part {
        val part = json.decodeFromString(Part.serializer(), text)
        if (part.v > VERSION) throw SyncNewerVersion(part.v)
        return part
    }

    /** What one part may hold, with room for the part's own fields. */
    private const val BUDGET = MAX_PART_BYTES - 200

    /**
     * [entries] in as few parts as fit, newest first. A document that does not
     * fit [MAX_PARTS], or an entry larger than a part, throws [SyncTooLarge]:
     * nothing is cut silently (audit 2026-10-22, M-04) — the document stays
     * unpublished and the status says so.
     */
    fun split(entries: Map<String, SyncEntry>, rev: String, doc: String = ""): List<Part> {
        val chunks = mutableListOf<MutableMap<String, SyncEntry>>(linkedMapOf())
        var size = 0
        for ((key, e) in entries.entries.sortedByDescending { it.value.t }) {
            val bytes = key.encodeToByteArray().size + json.encodeToString(SyncEntry.serializer(), e).encodeToByteArray().size + 4
            if (bytes > BUDGET) throw SyncTooLarge(doc)
            if (size + bytes > BUDGET && chunks.last().isNotEmpty()) {
                if (chunks.size == MAX_PARTS) throw SyncTooLarge(doc)
                chunks += linkedMapOf(); size = 0
            }
            chunks.last()[key] = e; size += bytes
        }
        return chunks.mapIndexed { i, c -> Part(VERSION, rev, i + 1, chunks.size, c) }
    }

    /**
     * The entries of one revision, only when every one of its parts is there
     * (audit 2026-10-22, H-02/M-04): [parts] maps the part number its `d` tag
     * names to what it held — a part saying another number, a count out of
     * range, another revision or a gap gives null.
     */
    fun join(parts: Map<Int, Part>): Map<String, SyncEntry>? {
        val first = parts[1] ?: return null
        if (first.parts !in 1..MAX_PARTS) return null
        val wanted = (1..first.parts).map { n -> parts[n]?.takeIf { it.part == n && it.rev == first.rev && it.parts == first.parts } ?: return null }
        return wanted.fold(emptyMap()) { acc, p -> acc + p.entries }
    }

    /** Whatever parts of the first part's revision arrived — for an incomplete read, applied but never sent over. */
    fun joinWhatArrived(parts: Map<Int, Part>): Map<String, SyncEntry> {
        val first = parts[1] ?: return emptyMap()
        return parts.filter { (n, p) -> p.part == n && p.rev == first.rev && n <= first.parts }.entries.sortedBy { it.key }
            .fold(emptyMap()) { acc, p -> acc + p.value.entries }
    }

    /** The part number a `d` tag of [doc] names; null for any other tag. */
    fun partOf(doc: String, dTag: String): Int? = (1..MAX_PARTS).firstOrNull { dTag(doc, it) == dTag }
}
