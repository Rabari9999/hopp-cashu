package space.sovereigncast.hopp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.model.Episode
import space.sovereigncast.hopp.model.Person
import space.sovereigncast.hopp.model.Podcast

/** Someone the subscribed feeds name (`podcast:person`), as the listener's library knows them. */
data class PersonMatch(
    val name: String, val img: String?, val href: String?, val role: String?,
    /** The show they are named in most. */
    val show: String?,
    val episodes: Int,
)

/** An episode naming a person, and as what. */
data class PersonEpisode(val item: EpisodeItem, val role: String?)

/**
 * People across the library (maintainer 2026-10-26): every episode of the subscribed shows a
 * person is named in — by the episode, or by its show for episodes that name no one of their
 * own, as the episode page shows them. Read from the stored feeds: nothing leaves the device.
 * Matched by name, letters and digits only ("Thorsten ⚡️" is "Thorsten"): feeds give one
 * person different links and pictures, so those cannot be the key.
 */
class PeopleRepository(private val db: HoppDatabase) {
    private val eq = db.episodeQueries

    /** Every episode in the library naming [name], newest first. */
    suspend fun episodes(name: String): List<PersonEpisode> {
        val key = key(name).takeIf { it.isNotEmpty() } ?: return emptyList()
        return scan(key) { it == key }[key].orEmpty().map { (item, p) -> PersonEpisode(item, p.role) }
    }

    /** The people whose name holds [term], the most named first — for Discover's search. */
    suspend fun find(term: String, max: Int = 3): List<PersonMatch> {
        val wanted = key(term).takeIf { it.length >= MIN_TERM } ?: return emptyList()
        return scan(wanted) { wanted in it }.map { (_, found) ->
            val person = found.first().second
            PersonMatch(
                person.name.trim(), found.firstNotNullOfOrNull { it.second.img }, found.firstNotNullOfOrNull { it.second.href },
                person.role, found.groupingBy { it.first.podcastTitle }.eachCount().maxByOrNull { it.value }?.key, found.size,
            )
        }.sortedByDescending { it.episodes }.take(max)
    }

    private suspend fun scan(key: String, match: (String) -> Boolean): Map<String, List<Pair<EpisodeItem, Person>>> = withContext(Dispatchers.IO) {
        val pattern = "%" + sieve(key).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val found = mutableMapOf<String, MutableList<Pair<EpisodeItem, Person>>>()
        fun add(item: EpisodeItem, persons: List<Person>) {
            persons.distinctBy { key(it.name) }.forEach { p -> key(p.name).takeIf(match)?.let { found.getOrPut(it) { mutableListOf() } += item to p } }
        }
        eq.naming(pattern).executeAsList().forEach { r ->
            val persons = runCatching { json.decodeFromString(Episode.serializer(), r.meta_json).persons }.getOrDefault(emptyList())
            add(episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url, r.chapters_url,
                r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at, r.enclosure_length), persons)
        }
        db.podcastQueries.selectAll().executeAsList().forEach { show ->
            val persons = runCatching { json.decodeFromString(Podcast.serializer(), show.meta_json).persons }.getOrDefault(emptyList())
            if (persons.none { match(key(it.name)) }) return@forEach
            eq.namingNoOne(show.id).executeAsList().forEach { r ->
                add(episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url, r.chapters_url,
                    r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at, r.enclosure_length), persons)
            }
        }
        found.mapValues { (_, l) -> l.distinctBy { it.first.id }.sortedByDescending { it.first.publishedAt ?: 0 } }
    }

    companion object {
        /** Typed shorter, a name matches half the library. */
        const val MIN_TERM = 3

        /** A name as it is compared: lower case, letters, digits and single spaces. */
        fun key(name: String): String =
            name.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

        /** What SQL can look for case-blind: the longest run of ASCII letters and digits — SQLite's
         *  LIKE folds only those; empty, every episode naming anyone is read. */
        internal fun sieve(key: String): String =
            Regex("[a-z0-9]+").findAll(key).map { it.value }.maxByOrNull { it.length }?.takeIf { it.length >= MIN_TERM } ?: ""
    }
}
