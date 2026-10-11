package space.sovereigncast.hopp.data

import space.sovereigncast.hopp.directory.spellings
import space.sovereigncast.hopp.ui.components.decodeEntities

/** The words of a search; case does not count. */
fun searchWords(query: String): List<String> = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * Whether an episode has every word in its title or description (device
 * feedback 2026-10-11: looking for a topic) — "ue" finds "ü" and the other
 * way round, as in Discover.
 */
fun episodeMatches(title: String?, descriptionHtml: String?, words: List<String>): Boolean {
    if (words.isEmpty()) return false
    val text = ((title ?: "") + " " + decodeEntities((descriptionHtml ?: "").replace(Regex("<[^>]+>"), " "))).lowercase()
    return words.all { w -> text.contains(w) || spellings(w).any { text.contains(it) } }
}
