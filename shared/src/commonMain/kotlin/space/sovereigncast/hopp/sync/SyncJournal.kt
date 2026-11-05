package space.sovereigncast.hopp.sync

import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile

/**
 * Where the repositories note what the listener changed on this device, for
 * sync (ADR 0021, 5). With sync off nothing is kept; changes that came from
 * another device are applied without a note, or the two would answer each
 * other for ever.
 */
class SyncJournal(private val store: SyncStore?) {
    @Volatile
    var enabled: Boolean = false

    /** Called after each note: sync sends it out a little later — or, not [urgent]
     *  (a position while playing), with the next round, at most every few minutes. */
    var onChange: suspend (doc: String, urgent: Boolean) -> Unit = { _, _ -> }

    /** Whether sync holds anything of [doc] yet — a device that never synced it seeds it from a snapshot instead. */
    suspend fun knows(doc: String): Boolean = enabled && store?.entries(doc)?.isNotEmpty() == true

    /** What sync holds for [key] now — to note only what changed (the listening statistics). */
    suspend fun current(doc: String, key: String): SyncEntry? = if (enabled) store?.entry(doc, key) else null

    /** [notify] false inside a database transaction, and [changed] once it is through: [onChange] may
     *  suspend, and on the apps' drivers a transaction belongs to its thread — resumed on another, the rest
     *  of it would run outside it (review 2026-10-31). */
    /** [atMs]: as of then, not now — for what other devices may have changed since ([QueueRepository.addNew]). */
    suspend fun record(doc: String, key: String, data: JsonObject, deleted: Boolean = false, urgent: Boolean = true, notify: Boolean = true, atMs: Long? = null) {
        if (!enabled || store == null) return
        store.record(doc, key, data, deleted, atMs)
        if (notify) onChange(doc, urgent)
    }

    /** What [record] with notify false did not say yet. */
    suspend fun changed(doc: String, urgent: Boolean = true) {
        if (enabled && store != null) onChange(doc, urgent)
    }

    companion object {
        /** For repositories without sync — tests, previews. */
        val Off = SyncJournal(null)
    }
}
