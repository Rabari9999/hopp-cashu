package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.data.QueueRepository
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.platform.ioDispatcher
import space.sovereigncast.hopp.sync.SyncDocs.long
import space.sovereigncast.hopp.sync.SyncDocs.text

/**
 * "Up next" in step (package 5 of docs/tasks/nostr-sync.md): an episode put
 * in, taken out or moved on one device is so on the other. Each episode keeps
 * the place it was given where it was given, so a reorder on one device and an
 * addition on the other both survive. One the feed has not brought yet waits.
 * With it, what plays now ([SyncDocs.NOW_KEY]): [adoptNow] gets the episode and when it was started.
 * An episode not here yet has its show's feed read at once ([catchUp]).
 */
class QueueDocument(
    private val db: HoppDatabase,
    private val queue: QueueRepository,
    private val catchUp: FeedCatchUp? = null,
    private val adoptNow: suspend (episodeId: Long, atSeconds: Long) -> Unit = { _, _ -> },
) : SyncedDocument {
    override val name = SyncDocs.QUEUE

    /** What plays now, of an episode not here (yet): looked for each round, but no entry "waiting" on the sync page. */
    override suspend fun beyondReach(entries: Map<String, SyncEntry>): Set<String> = entries.keys.filter { it == SyncDocs.NOW_KEY }.toSet()

    override suspend fun prepare() { queue.noteUnknownOnce() }

    /** This device's queue joins only where the relays have nothing: its order has no time. */
    override suspend fun snapshot(): Map<String, SyncEntry> = withContext(ioDispatcher) {
        db.queueQueries.selectKeys().awaitAsList().mapNotNull { q ->
            val r = db.episodeQueries.syncKeysOf(q.episode_id).awaitAsOneOrNull() ?: return@mapNotNull null
            val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
            SyncDocs.queueKey(show, r.guid) to SyncEntry(0, d = SyncDocs.queued(show, r.guid, q.sort_key))
        }.toMap()
    }

    override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> {
        catchUp?.fetchFor(entries.mapNotNull { (key, e) ->
            if (e.del) return@mapNotNull null
            val show = e.d.text(if (key == SyncDocs.NOW_KEY) "nowShow" else "show") ?: return@mapNotNull null
            FeedCatchUp.Wanted(show, e.d.text(if (key == SyncDocs.NOW_KEY) "nowGuid" else "guid") ?: return@mapNotNull null, e.t)
        })
        val shows = withContext(ioDispatcher) { db.podcastQueries.selectAll().awaitAsList() }
        val done = mutableSetOf<String>()
        fun episodeOf(show: String, guid: String) = shows.firstOrNull { SyncDocs.showKey(it.podcast_guid, it.feed_url) == show }
        // What plays now last: the player then finds "Up next" as it is going to be.
        for ((key, e) in entries) {
            if (key == SyncDocs.NOW_KEY) continue
            val show = e.d.text("show")
            val guid = e.d.text("guid")
            if (show == null || guid == null) { done += key; continue }
            val podcast = shows.firstOrNull { SyncDocs.showKey(it.podcast_guid, it.feed_url) == show }
            val id = podcast?.let { p -> withContext(ioDispatcher) { db.episodeQueries.selectByGuid(p.id, guid).awaitAsOneOrNull()?.id } }
            if (id == null) {
                // Out of the queue and not here at all: nothing to do. Into it: when the feed has it.
                if (e.del) done += key
                continue
            }
            // A place within what Hopp itself gives — one at the edge of a Long would wrap with the next "play last" (MN-06).
            queue.applySynced(id, if (e.del) null else (e.d.long("sort") ?: 0).coerceIn(-QueueRepository.SORT_MAX, QueueRepository.SORT_MAX))
            done += key
        }
        entries[SyncDocs.NOW_KEY]?.let { e ->
            val show = e.d.text("nowShow")
            val guid = e.d.text("nowGuid")
            if (e.del || show == null || guid == null) done += SyncDocs.NOW_KEY
            else episodeOf(show, guid)?.let { p -> withContext(ioDispatcher) { db.episodeQueries.selectByGuid(p.id, guid).awaitAsOneOrNull()?.id } }?.let { id ->
                // The player follows as an aside: its failing leaves the entry applied, as with positions (M-06).
                runCatching { adoptNow(id, e.t / 1000) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                done += SyncDocs.NOW_KEY
            }
        }
        return done
    }
}

/** Listening habits in step ([space.sovereigncast.hopp.data.Settings.SYNCED]). [autoDownloadsOff]: what
 *  turning automatic downloads off does here as well — what waits for Wi-Fi unasked is dropped. */
class SettingsDocument(
    private val settings: SettingsRepository,
    private val autoDownloadsOff: suspend () -> Unit = {},
) : SyncedDocument {
    override val name = SyncDocs.SETTINGS

    /** This device's settings join only where the relays have nothing: on a second device
     *  turned on, the first one's habits are taken. */
    override suspend fun snapshot(): Map<String, SyncEntry> =
        settings.synced().filterNot { (k, v) -> k == NAME && v.isBlank() }.mapValues { (_, v) -> SyncEntry(0, d = SyncDocs.setting(v)) }

    /** A boost name nobody gave (a blank one from a device's start, time 0) never empties one
     *  here — device feedback 2026-10-21: a restore wiped the name just read from the Nostr
     *  profile, and Home greeted no one. A name cleared on purpose has a time and is taken. */
    override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> {
        val values = entries.filterValues { !it.del }
            .mapNotNull { (k, e) -> e.d.text("v")?.takeUnless { k == NAME && e.t == 0L && it.isBlank() }?.takeIf { valid(k, it) }?.let { k to it } }
            .toMap()
        settings.applySynced(values)
        // Asked whatever it was before: cut off between the setting and the clean-up, the next
        // apply of the same "off" cleans up still (delta audit 5 2026-10-24, SS-01).
        if (values["auto_downloads"] == "false") autoDownloadsOff()
        return entries.keys
    }

    private companion object {
        const val NAME = "sender_name"
        val SPEEDS = space.sovereigncast.hopp.player.PlayerController.SPEED_CHOICES.let { it.min()..it.max() }
        val HEX_ID = Regex("[0-9a-f]{64}")

        /** Only a value this Hopp could have set itself is taken (security audit 2026-10-22, M-04). */
        fun valid(key: String, v: String): Boolean = when (key) {
            "skip_back" -> v.toIntOrNull() in Settings.skipBackChoices
            "skip_forward" -> v.toIntOrNull() in Settings.skipForwardChoices
            "skip_silence", "volume_boost", "library_as_list", "auto_downloads", "downloads_to_queue", "refresh_wifi_only" ->
                v == "true" || v == "false"
            "refresh_hours" -> v.toIntOrNull() in Settings.refreshChoicesHours
            "boost_feedback", "quick_boost_feedback", "zap_feedback" -> v in Settings.feedbackChoices
            "default_speed" -> v.toFloatOrNull()?.let { it.isFinite() && it in SPEEDS } == true
            "sender_name" -> v.length <= 60
            "quick_boost_sats" -> v.toIntOrNull()?.let { it in 0..1_000_000 } == true
            "library_sort" -> v in Settings.sortChoices
            "home_order", "home_hidden", "home_collapsed" -> v.length <= 2_000
            "take_part_hidden" -> v.length <= 40_000
            // A key or an event's id, in hex (Settings.MUTED, Settings.HIDDEN).
            else -> v == "1" && (key.startsWith(Settings.MUTED) || key.startsWith(Settings.HIDDEN)) && HEX_ID.matches(key.substringAfter(':'))
        }
    }
}
