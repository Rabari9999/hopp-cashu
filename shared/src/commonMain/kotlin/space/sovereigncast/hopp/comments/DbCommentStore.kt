package space.sovereigncast.hopp.comments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.nostr.Event

/**
 * Comments in the database (device feedback 2026-10-16): opened again — after
 * a restart too, the download long deleted — they show at once, and only what
 * is new is asked for. They go when the show is unsubscribed.
 */
class DbCommentStore(private val db: HoppDatabase) : CommentStore {
    private val q = db.commentQueries
    private val json = Json { ignoreUnknownKeys = true }
    private val strings = ListSerializer(String.serializer())

    override suspend fun seen(itemTag: String): CommentStore.Seen? = withContext(Dispatchers.IO) {
        val fetch = q.fetch(itemTag).executeAsOneOrNull() ?: return@withContext null
        val events = q.events(itemTag).executeAsList().mapNotNull { runCatching { json.decodeFromString(Event.serializer(), it) }.getOrNull() }
        CommentStore.Seen(events, json.decodeFromString(strings, fetch.relays_json), json.decodeFromString(strings, fetch.muted_json).toSet(), fetch.fetched_at,
            json.decodeFromString(strings, fetch.roots_json).toSet())
    }

    override suspend fun save(itemTag: String, seen: CommentStore.Seen) = withContext(Dispatchers.IO) {
        db.transaction {
            seen.events.forEach { q.insertEvent(itemTag, it.id, json.encodeToString(Event.serializer(), it)) }
            q.upsertFetch(itemTag, seen.at, json.encodeToString(strings, seen.relays), json.encodeToString(strings, seen.muted.toList()), json.encodeToString(strings, seen.roots.toList()))
        }
    }

    override suspend fun authors(keys: Set<String>): Map<String, Pair<CommentAuthor, Long>> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyMap()
        keys.chunked(500).flatMap { q.authors(it).executeAsList() }.associate { it.pubkey to (CommentAuthor(it.name, it.picture) to it.fetched_at) }
    }

    override suspend fun saveAuthors(found: Map<String, CommentAuthor>, at: Long) = withContext(Dispatchers.IO) {
        db.transaction { found.forEach { (key, a) -> q.upsertAuthor(key, a.name, a.picture, at) } }
    }

    override suspend fun boosts(ids: Collection<String>): Map<String, Long?> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        ids.distinct().chunked(500).flatMap { q.boosts(it).executeAsList() }.associate { it.comment_id to it.sats }
    }

    override suspend fun saveBoosts(found: Map<String, Long?>) = withContext(Dispatchers.IO) {
        db.transaction { found.forEach { (id, sats) -> q.upsertBoost(id, sats) } }
    }
}
