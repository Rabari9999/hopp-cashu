package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import space.sovereigncast.hopp.data.MAX_SKIP_SECONDS
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.platform.ioDispatcher

/** The documents' names and how their entries are keyed and filled (ADR 0021, 5). */
object SyncDocs {
    const val SUBSCRIPTIONS = "subscriptions"
    const val PROGRESS = "progress"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"
    /** Listening statistics, each device's own counts (maintainer 2026-10-26). */
    const val STATS = "stats"
    /** Not a document: the sync key's keyring, as a [RelayRound] names its requests. */
    const val KEYRING = "keyring"

    /** A show on every device: its `podcast:guid`, else its feed address. */
    fun showKey(guid: String?, feedUrl: String) = guid?.takeIf { it.isNotBlank() }?.let { "guid:$it" } ?: "url:$feedUrl"

    /** Title and image are for the restore offer only; kept short, so no feed's oddity makes an entry too large (M-04). */
    fun show(feedUrl: String, title: String?, image: String?, speed: Float?, autoDownload: Boolean, introSeconds: Int = 0, outroSeconds: Int = 0, contactsHidden: Boolean = false): JsonObject = buildJsonObject {
        put("url", feedUrl)
        title?.let { put("title", it.take(300)) }
        image?.takeIf { it.length <= 2048 }?.let { put("image", it) }
        speed?.let { put("speed", it) }
        put("autoDl", autoDownload)
        // Only when set: an entry without them is a show that skips nothing.
        if (introSeconds > 0) put("intro", introSeconds)
        if (outroSeconds > 0) put("outro", outroSeconds)
        // Left out of what is shared with mutual Nostr contacts (ADR 0024, 4a); only when so.
        if (contactsHidden) put("noShare", true)
    }

    /** An episode's position and played state; its favourite apart, so the two never overwrite each other. */
    fun progressKey(show: String, guid: String) = "pos\n$show\n$guid"
    fun favoriteKey(show: String, guid: String) = "fav\n$show\n$guid"

    fun progress(show: String, guid: String, positionMs: Long, played: Boolean, atSeconds: Long): JsonObject = buildJsonObject {
        put("show", show); put("guid", guid); put("pos", positionMs); put("played", played); put("at", atSeconds)
    }

    /**
     * Every episode of a show published up to [beforeSeconds] heard, as of [atSeconds] — "this and older"
     * or "all" as one entry instead of one per episode (maintainer 2026-10-30: hundreds of entries made
     * the progress document several events long, sent again with every change). Hopp before 0.3.2
     * passes over it. [fromSeconds]: one found from a device's copy of the feed covers only what that
     * copy held — from its oldest episode on (review 2026-10-31); without it, from the first.
     */
    fun playedThroughKey(show: String) = "upto\n$show"

    fun playedThrough(show: String, beforeSeconds: Long, atSeconds: Long, fromSeconds: Long? = null): JsonObject = buildJsonObject {
        put("show", show); put("before", beforeSeconds); put("at", atSeconds); fromSeconds?.let { put("from", it) }
    }

    /** [atSeconds] null: no favourite (any more). */
    fun favorite(show: String, guid: String, atSeconds: Long?): JsonObject = buildJsonObject {
        put("show", show); put("guid", guid); atSeconds?.let { put("at", it) }
    }

    /** An episode's place in "Up next": the sort key it has where it was put there. */
    fun queueKey(show: String, guid: String) = "q\n$show\n$guid"

    fun queued(show: String, guid: String, sortKey: Long): JsonObject = buildJsonObject {
        put("show", show); put("guid", guid); put("sort", sortKey)
    }

    fun setting(value: String): JsonObject = buildJsonObject { put("v", value) }

    internal fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

    internal fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    internal fun JsonObject.float(key: String) = (this[key] as? JsonPrimitive)?.floatOrNull
    internal fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/**
 * Subscriptions in step (package 3 of docs/tasks/nostr-sync.md): a show
 * subscribed elsewhere is subscribed here — its feed fetched as by hand — and
 * one given up elsewhere goes here, with the show's own speed and download
 * setting. On a new device this fills the library.
 */
class SubscriptionsDocument(private val db: HoppDatabase, private val podcasts: PodcastRepository) : SyncedDocument {
    override val name = SyncDocs.SUBSCRIPTIONS

    private companion object {
        val SPEEDS = space.sovereigncast.hopp.player.PlayerController.SPEED_CHOICES.let { it.min()..it.max() }
    }

    private data class Local(val id: Long, val key: String, val url: String, val speed: Float?, val autoDownload: Boolean, val intro: Int, val outro: Int, val contactsHidden: Boolean = false)

    private suspend fun local(): List<Local> = withContext(ioDispatcher) {
        db.podcastQueries.selectAll().awaitAsList().map {
            Local(it.id, SyncDocs.showKey(it.podcast_guid, it.feed_url), it.feed_url, it.playback_speed?.toFloat(), it.auto_download == 1L,
                it.skip_intro_seconds.toInt(), it.skip_outro_seconds.toInt(), it.contacts_hidden == 1L)
        }
    }

    override suspend fun snapshot(): Map<String, SyncEntry> = withContext(ioDispatcher) {
        db.podcastQueries.selectAll().awaitAsList().associate {
            SyncDocs.showKey(it.podcast_guid, it.feed_url) to
                SyncEntry(it.subscribed_at * 1000, d = SyncDocs.show(it.feed_url, it.title, it.image_url, it.playback_speed?.toFloat(), it.auto_download == 1L,
                    it.skip_intro_seconds.toInt(), it.skip_outro_seconds.toInt(), it.contacts_hidden == 1L))
        }
    }

    override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> = apply(entries) { _, _ -> true }

    /** A show given up takes its episodes, progress and place in "Up next" with it: only while the
     *  removal still wins, asked in the same transaction (delta audit 2 2026-10-22, HN-07). */
    override suspend fun apply(entries: Map<String, SyncEntry>, stillWins: suspend (key: String, entry: SyncEntry) -> Boolean): Set<String> = coroutineScope {
        val here = local()
        val gate = Semaphore(4)
        entries.map { (key, e) ->
            async {
                gate.withPermit {
                    with(SyncDocs) {
                        val url = e.d.text("url")
                        val mine = here.firstOrNull { it.key == key } ?: here.firstOrNull { it.url == url }
                        when {
                            e.del -> { mine?.let { podcasts.unsubscribe(it.id, sync = false) { stillWins(key, e) } }; key }
                            url == null -> key
                            else -> {
                                val autoDownload = e.d.bool("autoDl") ?: true
                                val id = mine?.id ?: when (val r = podcasts.subscribe(url, sync = false, autoDownload = autoDownload)) {
                                    is SubscribeResult.Subscribed -> r.podcastId
                                    is SubscribeResult.AlreadySubscribed -> r.podcastId
                                    // Unreachable now: tried again on the next sync.
                                    is SubscribeResult.Failed -> return@withPermit null
                                }
                                // Only a speed Hopp plays (audit 2026-10-22, M-04): another device's odd value is not taken.
                                val speed = e.d.float("speed")?.takeIf { it.isFinite() && it in SPEEDS }
                                if (mine == null || mine.speed != speed) podcasts.setSpeed(id, speed, sync = false)
                                if (mine != null && mine.autoDownload != autoDownload) podcasts.setAutoDownload(id, autoDownload, sync = false)
                                // Seconds skipped at start and end: whole, from none to ten minutes; anything else is none.
                                val intro = e.d.long("intro")?.takeIf { it in 0..MAX_SKIP_SECONDS }?.toInt() ?: 0
                                val outro = e.d.long("outro")?.takeIf { it in 0..MAX_SKIP_SECONDS }?.toInt() ?: 0
                                if ((mine?.intro ?: 0) != intro || (mine?.outro ?: 0) != outro) podcasts.setSkip(id, intro, outro, sync = false)
                                val hidden = e.d.bool("noShare") == true
                                if ((mine?.contactsHidden ?: false) != hidden) podcasts.setContactsHidden(id, hidden, sync = false)
                                key
                            }
                        }
                    }
                }
            }
        }.awaitAll().filterNotNull().toSet()
    }
}
