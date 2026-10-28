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
    var onChange: (doc: String, urgent: Boolean) -> Unit = { _, _ -> }

    /** What sync holds for [key] now — to note only what changed (the listening statistics). */
    fun current(doc: String, key: String): SyncEntry? = if (enabled) store?.entry(doc, key) else null

    fun record(doc: String, key: String, data: JsonObject, deleted: Boolean = false, urgent: Boolean = true) {
        if (!enabled || store == null) return
        store.record(doc, key, data, deleted)
        onChange(doc, urgent)
    }

    companion object {
        /** For repositories without sync — tests, previews. */
        val Off = SyncJournal(null)
    }
}
