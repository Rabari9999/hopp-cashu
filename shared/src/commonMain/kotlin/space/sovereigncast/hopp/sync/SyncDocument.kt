package space.sovereigncast.hopp.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * One entry of a sync document (ADR 0021, 5): a show, an episode's progress, a
 * place in "Up next", a setting. [t] is when it last changed, in milliseconds;
 * [del] marks a removal; [d] is what the document's owner keeps in it.
 */
@Serializable
data class SyncEntry(val t: Long, val del: Boolean = false, val d: JsonObject = JsonObject(emptyMap())) {
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

/**
 * How a document travels: JSON, NIP-44-encrypted, in kind-30078 events whose
 * `d` tag names it. NIP-44 takes at most 65 535 bytes, so a long document goes
 * in parts (`hopp/progress`, `hopp/progress#2` …) that share a revision —
 * parts of different revisions are never mixed.
 */
object SyncCodec {
    const val VERSION = 1
    const val MAX_PART_BYTES = 60_000
    const val MAX_PARTS = 16

    @Serializable
    data class Part(val v: Int = VERSION, val rev: String, val part: Int = 1, val parts: Int = 1, val entries: Map<String, SyncEntry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun dTag(doc: String, part: Int = 1) = if (part == 1) "hopp/$doc" else "hopp/$doc#$part"

    fun dTags(doc: String) = (1..MAX_PARTS).map { dTag(doc, it) }

    fun encode(part: Part): String = json.encodeToString(Part.serializer(), part)

    fun decode(text: String): Part {
        val part = json.decodeFromString(Part.serializer(), text)
        if (part.v > VERSION) throw SyncNewerVersion(part.v)
        return part
    }

    /**
     * [entries] in as few parts as fit, newest first. Beyond [MAX_PARTS] the
     * oldest are left out — progress is the only document that grows, and its
     * oldest matter least.
     */
    fun split(entries: Map<String, SyncEntry>, rev: String): List<Part> {
        val chunks = mutableListOf<MutableMap<String, SyncEntry>>(linkedMapOf())
        var size = 0
        val budget = MAX_PART_BYTES - 200
        for ((key, e) in entries.entries.sortedByDescending { it.value.t }) {
            val bytes = key.encodeToByteArray().size + json.encodeToString(SyncEntry.serializer(), e).encodeToByteArray().size + 4
            if (size + bytes > budget && chunks.last().isNotEmpty()) {
                if (chunks.size == MAX_PARTS) break
                chunks += linkedMapOf(); size = 0
            }
            chunks.last()[key] = e; size += bytes
        }
        return chunks.mapIndexed { i, c -> Part(VERSION, rev, i + 1, chunks.size, c) }
    }

    /** The entries of the first part's revision, from as many of its parts as arrived. */
    fun join(parts: List<Part>): Map<String, SyncEntry> {
        val first = parts.firstOrNull { it.part == 1 } ?: return emptyMap()
        return parts.filter { it.rev == first.rev && it.part <= first.parts }.sortedBy { it.part }
            .fold(emptyMap()) { acc, p -> acc + p.entries }
    }
}
