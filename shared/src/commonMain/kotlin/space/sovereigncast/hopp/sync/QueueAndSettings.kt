package space.sovereigncast.hopp.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.data.QueueRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.sync.SyncDocs.long
import space.sovereigncast.hopp.sync.SyncDocs.text

/**
 * "Up next" in step (package 5 of docs/tasks/nostr-sync.md): an episode put
 * in, taken out or moved on one device is so on the other. Each episode keeps
 * the place it was given where it was given, so a reorder on one device and an
 * addition on the other both survive. One the feed has not brought yet waits.
 */
class QueueDocument(private val db: HoppDatabase, private val queue: QueueRepository) : SyncedDocument {
    override val name = SyncDocs.QUEUE

    /** This device's queue joins only where the relays have nothing: its order has no time. */
    override suspend fun snapshot(): Map<String, SyncEntry> = withContext(Dispatchers.IO) {
        db.queueQueries.selectKeys().executeAsList().mapNotNull { q ->
            val r = db.episodeQueries.syncKeysOf(q.episode_id).executeAsOneOrNull() ?: return@mapNotNull null
            val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
            SyncDocs.queueKey(show, r.guid) to SyncEntry(0, d = SyncDocs.queued(show, r.guid, q.sort_key))
        }.toMap()
    }

    override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> {
        val shows = withContext(Dispatchers.IO) { db.podcastQueries.selectAll().executeAsList() }
        val done = mutableSetOf<String>()
        for ((key, e) in entries) {
            val show = e.d.text("show")
            val guid = e.d.text("guid")
            if (show == null || guid == null) { done += key; continue }
            val podcast = shows.firstOrNull { SyncDocs.showKey(it.podcast_guid, it.feed_url) == show }
            val id = podcast?.let { p -> withContext(Dispatchers.IO) { db.episodeQueries.selectByGuid(p.id, guid).executeAsOneOrNull()?.id } }
            if (id == null) {
                // Out of the queue and not here at all: nothing to do. Into it: when the feed has it.
                if (e.del) done += key
                continue
            }
            queue.applySynced(id, if (e.del) null else e.d.long("sort") ?: 0)
            done += key
        }
        return done
    }
}

/** Listening habits in step ([space.sovereigncast.hopp.data.Settings.SYNCED]). */
class SettingsDocument(private val settings: SettingsRepository) : SyncedDocument {
    override val name = SyncDocs.SETTINGS

    /** This device's settings join only where the relays have nothing: on a second device
     *  turned on, the first one's habits are taken. */
    override suspend fun snapshot(): Map<String, SyncEntry> =
        settings.synced().mapValues { (_, v) -> SyncEntry(0, d = SyncDocs.setting(v)) }

    override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> {
        settings.applySynced(entries.filterValues { !it.del }.mapNotNull { (k, e) -> e.d.text("v")?.let { k to it } }.toMap())
        return entries.keys
    }
}
