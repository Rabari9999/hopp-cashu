package space.sovereigncast.hopp.data

/**
 * "What's new" (maintainer 2026-11-03): CHANGELOG.md and CHANGELOG.de.md as this build holds them
 * ([ChangelogText], generated at build time) — read in Hopp, offline, never fetched from GitLab.
 * The format is ADR 0017's: `## [version] - date`, then `### 🆕 New Features` and `### 🐛 Fixes`,
 * each entry `- **Area:** one sentence`.
 */
object Changelog {
    const val UNRELEASED = "Unreleased"

    data class Item(val area: String?, val text: String)

    data class Section(val fixes: Boolean, val items: List<Item>)

    data class Release(val version: String, val date: String?, val sections: List<Section>) {
        val unreleased: Boolean get() = version == UNRELEASED
        val newCount: Int get() = sections.filter { !it.fixes }.sumOf { it.items.size }
        val fixCount: Int get() = sections.filter { it.fixes }.sumOf { it.items.size }
        /** What a line on Home pointed to: a release once, a build between releases again as entries are added. */
        val seenKey: String get() = "$version:${newCount + fixCount}"
    }

    private val heading = Regex("""^## \[([^\]]+)\](?:\s*-\s*(\S+))?""")
    private val entry = Regex("""^- \*\*(.+?):\*\*\s*(.*)$""")

    fun parse(text: String): List<Release> {
        val releases = mutableListOf<Release>()
        var version: String? = null
        var date: String? = null
        val sections = mutableListOf<Section>()
        var fixes = false
        var items = mutableListOf<Item>()
        fun endSection() { if (items.isNotEmpty()) sections += Section(fixes, items); items = mutableListOf() }
        fun endRelease() {
            endSection()
            version?.let { releases += Release(it, date, sections.toList()) }
            sections.clear()
        }
        text.lineSequence().map { it.trimEnd() }.forEach { line ->
            val h = heading.find(line)
            when {
                h != null -> { endRelease(); version = h.groupValues[1]; date = h.groupValues[2].ifEmpty { null }; fixes = false }
                version == null -> Unit
                line.startsWith("### ") -> { endSection(); fixes = "Fix" in line || "🐛" in line }
                line.startsWith("- ") -> {
                    val e = entry.find(line)
                    items += if (e != null) Item(e.groupValues[1].plain(), e.groupValues[2].plain()) else Item(null, line.removePrefix("- ").plain())
                }
                // A line that carries the entry above on.
                line.isNotBlank() && items.isNotEmpty() && line.first().isWhitespace() -> items[items.lastIndex] = items.last().let { it.copy(text = it.text + " " + line.trim().plain()) }
            }
        }
        endRelease()
        return releases
    }

    /** Code marks and bold dropped: plain text as Hopp shows it. */
    private fun String.plain() = replace("`", "").replace("**", "")

    private val cache = mutableMapOf<Boolean, List<Release>>()

    /** The releases, newest first, in German for German and in English otherwise. */
    fun releases(german: Boolean): List<Release> = cache.getOrPut(german) { parse(if (german) ChangelogText.DE else ChangelogText.EN) }

    /** A build between releases — a beta, Hopp Web, a development build: its version carries a suffix. */
    fun between(version: String): Boolean = '-' in version

    /** What the installed [version] brought: the release of that name, or — [between] releases — what is in
     *  work ("Unreleased") while it holds anything, else the release it builds on. Hopp Web is always between:
     *  its version is the apps' last one, its code the branch ahead of it. */
    fun yours(releases: List<Release>, version: String, between: Boolean = between(version)): Release? {
        val release = releases.firstOrNull { it.version == version.substringBefore('-') }
        return if (between) releases.firstOrNull { it.unreleased && it.sections.isNotEmpty() } ?: release else release
    }

    /** [Release.seenKey] of what [version] brought — the same in every language. */
    fun seenKey(version: String, between: Boolean = between(version)): String? = yours(releases(german = false), version, between)?.seenKey
}
