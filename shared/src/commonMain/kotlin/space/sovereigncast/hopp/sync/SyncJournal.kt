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

    /** Called after each note: sync sends it out a little later. */
    var onChange: (doc: String) -> Unit = {}

    fun record(doc: String, key: String, data: JsonObject, deleted: Boolean = false) {
        if (!enabled || store == null) return
        store.record(doc, key, data, deleted)
        onChange(doc)
    }

    companion object {
        /** For repositories without sync — tests, previews. */
        val Off = SyncJournal(null)
    }
}
