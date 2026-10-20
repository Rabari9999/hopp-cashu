package space.sovereigncast.hopp.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import space.sovereigncast.hopp.db.HoppDatabase

/** This device's side of sync (ADR 0021, 5): every entry as last known here. */
class SyncStore(private val db: HoppDatabase, private val nowMs: () -> Long) {
    private val q get() = db.syncQueries

    fun entries(doc: String): Map<String, SyncEntry> =
        q.entries(doc).executeAsList().associate { it.key to SyncEntry(it.updated_at, it.deleted != 0L, parse(it.json)) }

    fun entry(doc: String, key: String): SyncEntry? =
        q.entry(doc, key).executeAsOneOrNull()?.let { SyncEntry(it.updated_at, it.deleted != 0L, parse(it.json)) }

    /**
     * A change made on this device. Its time is now, or just after what it
     * replaces when that is later — a device whose clock runs behind still
     * wins with a change made after seeing the other's.
     */
    fun record(doc: String, key: String, data: JsonObject, deleted: Boolean = false): SyncEntry {
        val before = entry(doc, key)
        val entry = SyncEntry(maxOf(nowMs(), (before?.t ?: 0) + 1), deleted, data)
        put(doc, key, entry)
        return entry
    }

    /** Entries as they came from the relays. */
    fun putAll(doc: String, entries: Map<String, SyncEntry>) = db.transaction {
        entries.forEach { (key, e) -> put(doc, key, e) }
    }

    private fun put(doc: String, key: String, e: SyncEntry) =
        q.putEntry(doc, key, e.d.toString(), e.t, if (e.del) 1L else 0L)

    /** Entries no device needs any more, gone without a marker. */
    fun remove(doc: String, keys: Set<String>) = db.transaction { keys.forEach { q.removeEntry(doc, it) } }

    /** Removal markers older than [beforeMs] go: by then every device has seen them. */
    fun pruneRemoved(doc: String, beforeMs: Long) = q.pruneRemoved(doc, beforeMs)

    /** The newest entry time of [doc] the relays took from here; null: never published. */
    fun publishedUpTo(doc: String): Long? = q.docState(doc).executeAsOneOrNull()?.published_up_to

    fun fetchedAt(doc: String): Long? = q.docState(doc).executeAsOneOrNull()?.fetched_at

    fun markPublished(doc: String, upTo: Long) = db.transaction { q.ensureDoc(doc); q.setPublished(upTo, doc) }

    fun markFetched(doc: String, at: Long) = db.transaction { q.ensureDoc(doc); q.setFetched(at, doc) }

    /** Whether [doc] changed here since the relays last took it. */
    fun unpublished(doc: String): Boolean {
        val newest = q.newest(doc).executeAsOneOrNull()?.MAX ?: return false
        return newest > (publishedUpTo(doc) ?: Long.MIN_VALUE)
    }

    /** Sync turned off: what this device knew goes; the relays keep theirs (ADR 0021, 8). */
    fun clear() = db.transaction { q.clearEntries(); q.clearDocs() }

    private fun parse(text: String): JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) }
}
