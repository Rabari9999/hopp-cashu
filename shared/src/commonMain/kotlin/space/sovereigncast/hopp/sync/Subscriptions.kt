package space.sovereigncast.hopp.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import kotlinx.serialization.json.put
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase

/** The documents' names and how their entries are keyed and filled (ADR 0021, 5). */
object SyncDocs {
    const val SUBSCRIPTIONS = "subscriptions"
    const val PROGRESS = "progress"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"

    /** A show on every device: its `podcast:guid`, else its feed address. */
    fun showKey(guid: String?, feedUrl: String) = guid?.takeIf { it.isNotBlank() }?.let { "guid:$it" } ?: "url:$feedUrl"

    fun show(feedUrl: String, title: String?, image: String?, speed: Float?, autoDownload: Boolean): JsonObject = buildJsonObject {
        put("url", feedUrl)
        title?.let { put("title", it) }
        image?.let { put("image", it) }
        speed?.let { put("speed", it) }
        put("autoDl", autoDownload)
    }

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

    private data class Local(val id: Long, val key: String, val url: String, val speed: Float?, val autoDownload: Boolean)

    private suspend fun local(): List<Local> = withContext(Dispatchers.IO) {
        db.podcastQueries.selectAll().executeAsList().map {
            Local(it.id, SyncDocs.showKey(it.podcast_guid, it.feed_url), it.feed_url, it.playback_speed?.toFloat(), it.auto_download == 1L)
        }
    }

    override suspend fun snapshot(): Map<String, SyncEntry> = withContext(Dispatchers.IO) {
        db.podcastQueries.selectAll().executeAsList().associate {
            SyncDocs.showKey(it.podcast_guid, it.feed_url) to
                SyncEntry(it.subscribed_at * 1000, d = SyncDocs.show(it.feed_url, it.title, it.image_url, it.playback_speed?.toFloat(), it.auto_download == 1L))
        }
    }

    override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> = coroutineScope {
        val here = local()
        val gate = Semaphore(4)
        entries.map { (key, e) ->
            async {
                gate.withPermit {
                    with(SyncDocs) {
                        val url = e.d.text("url")
                        val mine = here.firstOrNull { it.key == key } ?: here.firstOrNull { it.url == url }
                        when {
                            e.del -> { mine?.let { podcasts.unsubscribe(it.id, sync = false) }; key }
                            url == null -> key
                            else -> {
                                val id = mine?.id ?: when (val r = podcasts.subscribe(url, sync = false)) {
                                    is SubscribeResult.Subscribed -> r.podcastId
                                    is SubscribeResult.AlreadySubscribed -> r.podcastId
                                    // Unreachable now: tried again on the next sync.
                                    is SubscribeResult.Failed -> return@withPermit null
                                }
                                val speed = e.d.float("speed")
                                if (mine == null || mine.speed != speed) podcasts.setSpeed(id, speed, sync = false)
                                val autoDownload = e.d.bool("autoDl") ?: true
                                if (mine == null || mine.autoDownload != autoDownload) podcasts.setAutoDownload(id, autoDownload, sync = false)
                                key
                            }
                        }
                    }
                }
            }
        }.awaitAll().filterNotNull().toSet()
    }
}
