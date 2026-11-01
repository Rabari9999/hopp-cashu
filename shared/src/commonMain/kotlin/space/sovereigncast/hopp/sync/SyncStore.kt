package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import space.sovereigncast.hopp.db.HoppDatabase

/** This device's side of sync (ADR 0021, 5): every entry as last known here. */
class SyncStore(private val db: HoppDatabase, private val nowMs: () -> Long) {
    private val q get() = db.syncQueries

    suspend fun entries(doc: String): Map<String, SyncEntry> =
        q.entries(doc).awaitAsList().associate { it.key to SyncEntry(it.updated_at, it.deleted != 0L, parse(it.json), it.kept_from) }

    suspend fun entry(doc: String, key: String): SyncEntry? =
        q.entry(doc, key).awaitAsOneOrNull()?.let { SyncEntry(it.updated_at, it.deleted != 0L, parse(it.json), it.kept_from) }

    /**
     * A change made on this device. Its time is now, or just after what it
     * replaces when that is later — a device whose clock runs behind still
     * wins with a change made after seeing the other's.
     */
    suspend fun record(doc: String, key: String, data: JsonObject, deleted: Boolean = false): SyncEntry {
        return db.transactionWithResult {
            val before = entry(doc, key)
            val entry = SyncEntry(maxOf(nowMs(), (before?.t ?: 0) + 1), deleted, data)
            put(doc, key, entry, pending = true)
            entry
        }
    }

    /**
     * Entries from the relays (or this device's own, [pending]), each only where
     * it wins over what is here now — compare and set in one transaction, so a
     * change the listener made while a sync was on its way is never written
     * over by an older one (security audit 2026-10-22, H-03). The keys taken.
     */
    suspend fun putIfNewer(doc: String, entries: Map<String, SyncEntry>, pending: Boolean = false): Set<String> = db.transactionWithResult {
        entries.filter { (key, e) ->
            val now = entry(doc, key)
            (now == null || e.winsOver(now)).also { if (it) put(doc, key, e, pending) }
        }.keys
    }

    /** Of [entries], those still newer than what is here — checked again right before they are applied. */
    suspend fun stillNewer(doc: String, entries: Map<String, SyncEntry>): Map<String, SyncEntry> =
        entries.filter { (key, e) -> entry(doc, key)?.let { e.winsOver(it) } ?: true }

    /** The relays hold these versions: entries changed here since stay pending. A removal keeps
     *  when it first went out ([SyncEntry.out]). */
    suspend fun markSent(doc: String, entries: Map<String, SyncEntry>) = db.transaction {
        entries.forEach { (key, e) -> q.markSent(e.out?.takeIf { e.del }, doc, key, e.t) }
    }

    private suspend fun put(doc: String, key: String, e: SyncEntry, pending: Boolean) =
        q.putEntry(doc, key, e.d.toString(), e.t, if (e.del) 1L else 0L, if (pending) 1L else 0L, e.out?.takeIf { e.del })

    /**
     * [keys] of [doc] noted as perhaps not in the app as the store has them — before another
     * device's entries are applied (delta audit 2 2026-10-22, HN-07/MN-07): a round cut off
     * halfway, or a change the listener made under it, is put right by the next round.
     */
    suspend fun markUnapplied(doc: String, keys: Collection<String>) = db.transaction { keys.forEach { q.markUnapplied(doc, it) } }

    /** Entries of [doc] the app may not hold as the store does, as the store has them now (none: the key only). */
    suspend fun unapplied(doc: String): Map<String, SyncEntry?> = db.transactionWithResult {
        q.unappliedKeys(doc).awaitAsList().associateWith { entry(doc, it) }
    }

    /** The app holds these versions now: noted so where the store still has them, and where it has nothing. */
    suspend fun applied(doc: String, entries: Map<String, SyncEntry?>) = db.transaction {
        entries.forEach { (key, e) -> if (entry(doc, key) == e) q.clearUnapplied(doc, key) }
    }

    /** [keys] of [doc] the app holds as the store does, whatever the store has. */
    suspend fun clearUnapplied(doc: String, keys: Collection<String>) = db.transaction { keys.forEach { q.clearUnapplied(doc, it) } }

    /** Entries no device needs any more, gone without a marker. */
    suspend fun remove(doc: String, keys: Set<String>) = db.transaction { keys.forEach { q.removeEntry(doc, it) } }

    /**
     * Of [entries], those still here as they were and taken by the relays — removed; the keys
     * removed. One changed here meanwhile stays (delta audit 2026-10-22, HN-04).
     */
    suspend fun removeIfSent(doc: String, entries: Map<String, SyncEntry>): Set<String> = db.transactionWithResult {
        entries.filter { (key, e) -> q.removeIfSent(doc, key, e.t); q.entry(doc, key).awaitAsOneOrNull() == null }.keys
    }

    /**
     * What this device had when sync was turned on, without a time (settings, the queue's
     * order): kept until the relays have a value for it or took it (delta audit 2026-10-22,
     * HN-01) — a first round that failed, or an app closed meanwhile, loses nothing.
     */
    suspend fun seed(doc: String, entries: Map<String, SyncEntry>) = db.transaction {
        entries.forEach { (key, e) -> if (q.entry(doc, key).awaitAsOneOrNull() == null) put(doc, key, e.copy(t = 0), pending = true) }
    }

    /** Of [keys], the seeds still waiting here. */
    suspend fun seeds(doc: String): Set<String> = db.transactionWithResult {
        val pending = pendingKeys(doc)
        entries(doc).filter { (k, e) -> e.t == 0L && k in pending }.keys
    }

    /** Seeds the relays hold a value for: theirs is taken instead. */
    suspend fun dropSeeds(doc: String, keys: Set<String>) = db.transaction { keys.forEach { q.removeSeed(doc, it) } }

    /** Removal markers older than [beforeMs] go: by then every device has seen them. */
    suspend fun pruneRemoved(doc: String, beforeMs: Long) = q.pruneRemoved(doc, beforeMs)

    /** The newest entry time of [doc] the relays took from here; null: never published. */
    suspend fun publishedUpTo(doc: String): Long? = q.docState(doc).awaitAsOneOrNull()?.published_up_to

    suspend fun fetchedAt(doc: String): Long? = q.docState(doc).awaitAsOneOrNull()?.fetched_at

    suspend fun markPublished(doc: String, upTo: Long) = db.transaction { q.ensureDoc(doc); q.setPublished(upTo, doc) }

    suspend fun markFetched(doc: String, at: Long) = db.transaction { q.ensureDoc(doc); q.setFetched(at, doc) }

    /** A revision of [doc] read with a gap while every relay answered, and since when (ms); nulls when none. */
    suspend fun stuck(doc: String): Pair<String?, Long?> = q.stuck(doc).awaitAsOneOrNull()?.let { it.stuck_rev to it.stuck_since } ?: (null to null)

    suspend fun markStuck(doc: String, rev: String?, since: Long?) = db.transaction { q.ensureDoc(doc); q.setStuck(rev, since, doc) }

    /** The revision of [doc] this device began to send and did not finish — some parts out, some not; null when none. */
    suspend fun brokenRev(doc: String): String? = q.brokenRev(doc).awaitAsOneOrNull()?.broken_rev

    suspend fun markBroken(doc: String, rev: String?) = db.transaction { q.ensureDoc(doc); q.setBrokenRev(rev, doc) }

    /** Entries changed here and not yet taken by the relays, over every document. */
    suspend fun pendingCount(): Int = q.pendingCount().awaitAsOne().toInt()

    /** The entries of [doc] changed here and not yet taken by the relays. */
    suspend fun pendingKeys(doc: String): Set<String> = q.pendingKeys(doc).awaitAsList().toSet()

    /** Whether an entry of [doc] changed here and the relays have not taken it — per entry, so an
     *  entry with a time far ahead cannot hide a later change to another (audit 2026-10-22, M-02). */
    suspend fun unpublished(doc: String): Boolean = q.anyPending(doc).awaitAsOne() > 0

    /** Sync turned off: what this device knew goes; the relays keep theirs (ADR 0021, 8). */
    suspend fun clear() = db.transaction { q.clearEntries(); q.clearDocs(); q.clearUnappliedAll() }

    private fun parse(text: String): JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) }
}
