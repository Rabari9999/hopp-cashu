package space.sovereigncast.hopp.data

/**
 * The listener's own tags on shows (maintainer 2026-11-03, as in AntennaPod): any number per show, made by the
 * listener alone — no suggestions from the feeds' categories. Kept one per line; the same tag in another case is
 * one tag, written as first given.
 */
object Tags {
    const val MAX_LENGTH = 40
    const val MAX_PER_SHOW = 20

    fun parse(stored: String): List<String> = clean(stored.lines())

    fun join(tags: List<String>): String = tags.joinToString("\n")

    /** Trimmed, one line, not empty, each once whatever its case, A–Z. */
    fun clean(tags: Collection<String>): List<String> = tags.map { it.replace('\n', ' ').trim().take(MAX_LENGTH) }
        .filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER).take(MAX_PER_SHOW)

    /** Every tag of [shows], A–Z. */
    fun all(shows: List<Show>): List<String> = clean(shows.flatMap { it.tags })

    /** [shows] as the library's [filter] picks them (Settings.libraryTag) — all of them for a tag no show has any more. */
    fun filter(shows: List<Show>, filter: String): List<Show> = when {
        filter.isEmpty() -> shows
        filter == Settings.TAG_UNTAGGED -> shows.filter { it.tags.isEmpty() }
        shows.none { s -> s.tags.any { it.equals(filter, ignoreCase = true) } } -> shows
        else -> shows.filter { s -> s.tags.any { it.equals(filter, ignoreCase = true) } }
    }
}
