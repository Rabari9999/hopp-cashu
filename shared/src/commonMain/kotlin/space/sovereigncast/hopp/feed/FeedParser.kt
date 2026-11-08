package space.sovereigncast.hopp.feed

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.core.KtXmlReader
import space.sovereigncast.hopp.model.Enclosure
import space.sovereigncast.hopp.model.Episode
import space.sovereigncast.hopp.model.Feed
import space.sovereigncast.hopp.model.Funding
import space.sovereigncast.hopp.model.Person
import space.sovereigncast.hopp.model.Podcast
import space.sovereigncast.hopp.model.RemoteItem
import space.sovereigncast.hopp.model.SocialInteract
import space.sovereigncast.hopp.model.Soundbite
import space.sovereigncast.hopp.model.Trailer
import space.sovereigncast.hopp.model.TranscriptRef
import space.sovereigncast.hopp.model.TxtRecord
import space.sovereigncast.hopp.model.UpdateFrequency
import space.sovereigncast.hopp.model.ValueBlock
import space.sovereigncast.hopp.model.ValueRecipient
import space.sovereigncast.hopp.model.ValueTimeSplit

class FeedParseException(message: String) : Exception(message)

/**
 * RSS 2.0 + iTunes + Podcasting 2.0 → [Feed], in common code.
 *
 * Pull parsing with xmlutil's [KtXmlReader] in relaxed mode: no DOM, so a
 * 2 MB feed costs one pass, and syntax slips that are common in the wild do
 * not stop it. What it still cannot read — a feed cut off mid-download, say —
 * ends the parse, and everything completed up to that point is kept, with a
 * warning. Only a document that yields neither a title nor a single episode
 * is an error.
 *
 * Namespaces are matched by URI, never by prefix (`pc:` works as well as
 * `podcast:`), and the URI variants found in real feeds are normalised.
 */
@OptIn(ExperimentalXmlUtilApi::class)
object FeedParser {

    fun parse(bytes: ByteArray): Feed {
        val decoded = FeedText.decode(bytes)
        return parse(decoded.text, decoded.warnings)
    }

    fun parse(text: String, initialWarnings: List<String> = emptyList()): Feed {
        val state = State(initialWarnings.toMutableList())
        val reader = KtXmlReader(escapeBareAmpersands(text), expandEntities = false, relaxed = true)
        try {
            while (reader.hasNext()) {
                state.onEvent(reader, reader.next())
            }
        } catch (e: Exception) {
            val where = reader.extLocationInfo?.let { " at $it" } ?: ""
            state.warnings += "stopped reading$where: ${e.message ?: e::class.simpleName}"
        }
        if (state.podcast.title == null && state.episodes.isEmpty()) {
            throw FeedParseException(state.warnings.lastOrNull() ?: "no RSS channel found")
        }
        return Feed(state.podcast.build(), state.episodes, state.warnings)
    }

    // An "&" that does not start an entity ("Q&A live", an unescaped query
    // string). Relaxed mode would read "&A live" as a broken entity and
    // mangle it into "&A;live"; escaping it first keeps the text as written.
    private val BARE_AMPERSAND = Regex("""&(?!#[0-9]+;|#[xX][0-9a-fA-F]+;|[A-Za-z][A-Za-z0-9]*;)""")

    // Only outside CDATA: an "&" in a CDATA section is text already, and escaped
    // it would stay "&amp;" — "Tom &amp; Jerry", broken links (code review
    // 2026-10-17, M14).
    internal fun escapeBareAmpersands(text: String): String {
        if ('&' !in text) return text
        return buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val start = text.indexOf("<![CDATA[", i).let { if (it < 0) text.length else it }
                append(BARE_AMPERSAND.replace(text.substring(i, start), "&amp;"))
                if (start == text.length) break
                val end = text.indexOf("]]>", start).let { if (it < 0) text.length else it + 3 }
                append(text, start, end)
                i = end
            }
        }
    }

    private enum class Ns { NONE, ITUNES, PODCAST, CONTENT, ATOM, PSC, OTHER }

    private fun ns(uri: String): Ns {
        val u = uri.trim().lowercase().removeSuffix("/")
        return when {
            u.isEmpty() -> Ns.NONE
            u.endsWith("itunes.com/dtds/podcast-1.0.dtd") -> Ns.ITUNES
            u.endsWith("podcastindex.org/namespace/1.0") -> Ns.PODCAST
            u.endsWith("podlove.org/simple-chapters") -> Ns.PSC
            // Early feeds declared the spec document itself as the namespace.
            u.contains("podcastindex-org/podcast-namespace") -> Ns.PODCAST
            u == "http://purl.org/rss/1.0/modules/content" -> Ns.CONTENT
            u == "http://www.w3.org/2005/atom" -> Ns.ATOM
            else -> Ns.OTHER
        }
    }

    private class Element(val ns: Ns, val name: String, val attrs: Map<String, String>) {
        val text = StringBuilder()
    }

    private class State(val warnings: MutableList<String>) {
        val stack = ArrayDeque<Element>()
        val podcast = PodcastBuilder()
        val episodes = mutableListOf<Episode>()
        var episode: EpisodeBuilder? = null
        var value: ValueBuilder? = null
        var split: SplitBuilder? = null
        var inPodroll = false

        fun onEvent(r: XmlReader, type: EventType) {
            when (type) {
                EventType.START_ELEMENT -> start(r)
                EventType.END_ELEMENT -> end()
                EventType.TEXT, EventType.CDSECT, EventType.IGNORABLE_WHITESPACE ->
                    stack.lastOrNull()?.text?.append(r.text)
                EventType.ENTITY_REF -> stack.lastOrNull()?.text?.append(
                    if (r.isKnownEntity) r.text else HTML_ENTITIES[r.localName] ?: "&${r.localName};",
                )
                else -> Unit
            }
        }

        private fun start(r: XmlReader) {
            val attrs = buildMap {
                for (i in 0 until r.attributeCount) put(r.getAttributeLocalName(i), r.getAttributeValue(i).trim())
            }
            val el = Element(ns(r.namespaceURI), r.localName, attrs)
            val underChannel = parentIs("channel")
            stack.addLast(el)
            val inItem = episode != null
            when {
                el.ns == Ns.NONE && el.name == "item" && underChannel -> episode = EpisodeBuilder()
                el.ns == Ns.PODCAST -> when (el.name) {
                    "value" -> value = ValueBuilder(attrs["type"] ?: "lightning", attrs["method"] ?: "keysend", attrs["suggested"])
                    "valueTimeSplit" -> if (value != null) split = SplitBuilder(attrs)
                    "valueRecipient" -> recipient(attrs)?.let { r2 ->
                        val s = split
                        if (s != null) s.recipients += r2 else value?.recipients?.add(r2)
                    }
                    "remoteItem" -> remoteItem(attrs)?.let { item ->
                        when {
                            split != null -> split!!.remoteItem = item
                            inPodroll -> podcast.podroll += item
                        }
                    }
                    "podroll" -> inPodroll = true
                    "transcript" -> if (inItem) attrs["url"]?.takeIf { it.isNotEmpty() }?.let {
                        episode!!.transcripts += TranscriptRef(it, attrs["type"].orEmpty(), attrs["language"], attrs["rel"])
                    }
                    // Podigee writes `href`, the spec `url`.
                    "chapters" -> if (inItem) episode!!.chaptersUrl = (attrs["url"] ?: attrs["href"])?.takeIf { it.isNotEmpty() }
                    "socialInteract" -> if (inItem) attrs["uri"]?.takeIf { it.isNotEmpty() }?.let {
                        episode!!.socialInteracts += SocialInteract(it, attrs["protocol"] ?: "", attrs["accountId"], attrs["priority"]?.toIntOrNull())
                    }
                    "podping" -> podcast.usesPodping = attrs["usesPodping"].equals("true", ignoreCase = true)
                }
                // A web address only (review 2026-10-31): the players load it, and on the web "Save audio file"
                // hands it to the browser as a link — `javascript:` or `data:` from a feed has no place there.
                el.ns == Ns.NONE && el.name == "enclosure" && inItem -> attrs["url"]?.takeIf { it.trim().let { u -> u.startsWith("https://", true) || u.startsWith("http://", true) } }?.let {
                    episode!!.enclosure = Enclosure(it, attrs["length"]?.toLongOrNull()?.takeIf { n -> n > 0 }, attrs["type"]?.ifEmpty { null })
                }
                el.ns == Ns.PSC && el.name == "chapter" && inItem -> ChaptersParser.normalPlayTime(attrs["start"].orEmpty())?.let { start ->
                    fun web(key: String) = attrs[key]?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
                    episode!!.chapters += Chapter(start, attrs["title"].orEmpty(), web("image"), web("href"))
                }
                el.ns == Ns.ITUNES && el.name == "category" && !inItem -> attrs["text"]?.takeIf { it.isNotEmpty() }?.let {
                    if (it !in podcast.categories) podcast.categories += it
                }
                el.ns == Ns.ITUNES && el.name == "image" -> attrs["href"]?.takeIf { it.isNotEmpty() }?.let {
                    if (inItem) episode!!.imageUrl = episode!!.imageUrl ?: it else podcast.itunesImage = it
                }
            }
        }

        private fun end() {
            val el = stack.removeLastOrNull() ?: return
            val text = el.text.toString().trim()
            val inItem = episode != null
            when (el.ns) {
                Ns.NONE -> when (el.name) {
                    "item" -> if (parentIs("channel")) episode?.build(warnings)?.let { episodes += it }.also { episode = null }
                    "title" -> if (inItem && parentIs("item")) episode!!.title = text.ifEmpty { null }
                        else if (parentIs("channel")) podcast.title = text.ifEmpty { null }
                    "link" -> if (inItem && parentIs("item")) episode!!.link = text.ifEmpty { null }
                        else if (parentIs("channel")) podcast.link = text.ifEmpty { null }
                    "description" -> if (inItem && parentIs("item")) episode!!.description = episode!!.description ?: text.ifEmpty { null }
                        else if (parentIs("channel")) podcast.description = text.ifEmpty { null }
                    "language" -> if (parentIs("channel")) podcast.language = text.ifEmpty { null }
                    "guid" -> if (inItem) episode!!.guid = text.ifEmpty { null }
                    "pubDate" -> if (inItem) {
                        episode!!.publishedAt = Rfc822.parse(text)
                        if (text.isNotEmpty() && episode!!.publishedAt == null) warnings += "unreadable pubDate \"$text\""
                    }
                    "url" -> if (parentIs("image") && !inItem) podcast.rssImage = text.ifEmpty { null }
                }
                Ns.CONTENT -> if (el.name == "encoded" && inItem) episode!!.contentEncoded = text.ifEmpty { null }
                Ns.ITUNES -> when (el.name) {
                    "author" -> if (!inItem) podcast.author = text.ifEmpty { null }
                    "explicit" -> explicit(text)?.let { if (inItem) episode!!.explicit = it else podcast.explicit = it }
                    "duration" -> if (inItem) episode!!.duration = parseDurationSeconds(text)
                    "season" -> if (inItem) episode!!.season = episode!!.season ?: text.toIntOrNull()
                    "episode" -> if (inItem) episode!!.number = episode!!.number ?: text.toDoubleOrNull()
                    "episodeType" -> if (inItem) episode!!.episodeType = text.lowercase().ifEmpty { null }
                    "summary" -> if (inItem) episode!!.summary = text.ifEmpty { null }
                }
                Ns.PODCAST -> when (el.name) {
                    "guid" -> if (!inItem) podcast.guid = text.ifEmpty { null }
                    "locked" -> if (!inItem) podcast.locked = text.equals("yes", ignoreCase = true)
                    "medium" -> if (!inItem) podcast.medium = text.lowercase().ifEmpty { null }
                    "funding" -> if (!inItem) el.attrs["url"]?.takeIf { it.isNotEmpty() }?.let { podcast.funding += Funding(it, text.ifEmpty { null }) }
                    "txt" -> if (!inItem && text.isNotEmpty()) podcast.txt += TxtRecord(el.attrs["purpose"], text)
                    "updateFrequency" -> if (!inItem) podcast.updateFrequency =
                        UpdateFrequency(text.ifEmpty { null }, el.attrs["rrule"], el.attrs["complete"].equals("true", true))
                    "image" -> el.attrs["href"]?.takeIf { it.isNotEmpty() }?.let {
                        if (inItem) episode!!.podcastImage = episode!!.podcastImage ?: it else podcast.podcastImage = podcast.podcastImage ?: it
                    }
                    "season" -> if (inItem) episode!!.season = text.toIntOrNull() ?: episode!!.season
                    "episode" -> if (inItem) episode!!.number = text.toDoubleOrNull() ?: episode!!.number
                    "person" -> if (text.isNotEmpty()) {
                        // Web addresses only, and all of it of a sensible length (review 2026-10-27): the
                        // person goes into the navigation state, the picture to the image loader.
                        fun web(v: String?) = v?.trim()?.takeIf { it.length <= 2048 && (it.startsWith("https://", true) || it.startsWith("http://", true)) }
                        val p = Person(text.take(200), el.attrs["role"]?.lowercase()?.take(64), el.attrs["group"]?.lowercase()?.take(64), web(el.attrs["img"]), web(el.attrs["href"]))
                        if (inItem) episode!!.persons += p else podcast.persons += p
                    }
                    "soundbite" -> if (inItem) {
                        val start = el.attrs["startTime"]?.toDoubleOrNull()
                        val duration = el.attrs["duration"]?.toDoubleOrNull()
                        if (start != null && duration != null) episode!!.soundbites += Soundbite(start, duration, text.ifEmpty { null })
                    }
                    "trailer" -> if (!inItem) el.attrs["url"]?.takeIf { it.isNotEmpty() }?.let {
                        podcast.trailers += Trailer(text.ifEmpty { null }, it, Rfc822.parse(el.attrs["pubdate"]),
                            el.attrs["length"]?.toLongOrNull(), el.attrs["type"], el.attrs["season"]?.toIntOrNull())
                    }
                    "valueTimeSplit" -> {
                        split?.build()?.let { value?.splits?.add(it) }
                        split = null
                    }
                    "value" -> {
                        val block = value?.build()
                        if (block != null) {
                            if (inItem) episode!!.value = block else podcast.value = block
                        }
                        value = null
                    }
                    "podroll" -> inPodroll = false
                }
                else -> Unit
            }
        }

        private fun parentIs(name: String): Boolean = stack.lastOrNull()?.let { it.ns == Ns.NONE && it.name == name } ?: false

        private fun explicit(text: String): Boolean? = when (text.lowercase()) {
            "yes", "true", "explicit" -> true
            "no", "false", "clean" -> false
            else -> null
        }

        private fun recipient(a: Map<String, String>): ValueRecipient? {
            val address = a["address"]?.takeIf { it.isNotEmpty() } ?: return null
            return ValueRecipient(
                name = a["name"]?.ifEmpty { null },
                type = a["type"]?.ifEmpty { null } ?: if ('@' in address) "lnaddress" else "node",
                address = address,
                split = a["split"]?.toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0,
                fee = a["fee"].equals("true", ignoreCase = true),
                customKey = a["customKey"]?.ifEmpty { null },
                customValue = a["customValue"]?.ifEmpty { null },
            )
        }

        private fun remoteItem(a: Map<String, String>): RemoteItem? {
            val feedGuid = a["feedGuid"]?.takeIf { it.isNotEmpty() } ?: return null
            return RemoteItem(feedGuid, a["itemGuid"]?.ifEmpty { null }, a["feedUrl"]?.ifEmpty { null }, a["medium"]?.ifEmpty { null })
        }
    }

    private class PodcastBuilder {
        var title: String? = null; var link: String? = null; var description: String? = null
        var language: String? = null; var author: String? = null; var explicit: Boolean? = null
        var itunesImage: String? = null; var podcastImage: String? = null; var rssImage: String? = null
        var guid: String? = null; var locked: Boolean? = null; var medium: String? = null
        var updateFrequency: UpdateFrequency? = null; var usesPodping = false
        val funding = mutableListOf<Funding>(); val txt = mutableListOf<TxtRecord>()
        val persons = mutableListOf<Person>(); var value: ValueBlock? = null
        val podroll = mutableListOf<RemoteItem>(); val trailers = mutableListOf<Trailer>()
        val categories = mutableListOf<String>()

        fun build() = Podcast(
            title = title, link = link, description = description, language = language, author = author,
            imageUrl = podcastImage ?: itunesImage ?: rssImage, explicit = explicit, podcastGuid = guid,
            locked = locked, medium = medium, updateFrequency = updateFrequency, usesPodping = usesPodping,
            funding = funding, txt = txt, persons = persons, value = value, podroll = podroll, trailers = trailers,
            categories = categories,
        )
    }

    private class EpisodeBuilder {
        var guid: String? = null; var title: String? = null; var link: String? = null
        var description: String? = null; var contentEncoded: String? = null; var summary: String? = null
        var publishedAt: Long? = null; var enclosure: Enclosure? = null; var duration: Long? = null
        var imageUrl: String? = null; var podcastImage: String? = null
        var season: Int? = null; var number: Double? = null; var episodeType: String? = null
        var explicit: Boolean? = null; var chaptersUrl: String? = null; val chapters = mutableListOf<Chapter>()
        val transcripts = mutableListOf<TranscriptRef>(); val persons = mutableListOf<Person>()
        val soundbites = mutableListOf<Soundbite>(); var value: ValueBlock? = null
        val socialInteracts = mutableListOf<SocialInteract>()

        fun build(warnings: MutableList<String>): Episode? {
            val id = guid ?: enclosure?.url
            if (id == null) {
                warnings += "item \"${title ?: "?"}\" has neither guid nor enclosure — skipped"
                return null
            }
            return Episode(
                guid = id, title = title, description = contentEncoded ?: description ?: summary, link = link,
                publishedAt = publishedAt, enclosure = enclosure, durationSeconds = duration,
                imageUrl = podcastImage ?: imageUrl, season = season, episodeNumber = number,
                episodeType = episodeType, explicit = explicit, chaptersUrl = chaptersUrl, chapters = chapters.sortedBy { it.startSeconds },
                transcripts = transcripts, persons = persons, soundbites = soundbites, value = value,
                socialInteracts = socialInteracts,
            )
        }
    }

    private class ValueBuilder(val type: String, val method: String, val suggested: String?) {
        val recipients = mutableListOf<ValueRecipient>()
        val splits = mutableListOf<ValueTimeSplit>()
        fun build(): ValueBlock? =
            if (recipients.isEmpty() && splits.isEmpty()) null
            else ValueBlock(type, method, suggested?.ifEmpty { null }, recipients, splits)
    }

    private class SplitBuilder(val a: Map<String, String>) {
        val recipients = mutableListOf<ValueRecipient>()
        var remoteItem: RemoteItem? = null
        fun build(): ValueTimeSplit? {
            val start = a["startTime"]?.toDoubleOrNull() ?: return null
            val duration = a["duration"]?.toDoubleOrNull() ?: return null
            if (recipients.isEmpty() && remoteItem == null) return null
            return ValueTimeSplit(start, duration, a["remoteStartTime"]?.toDoubleOrNull(),
                a["remotePercentage"]?.toIntOrNull(), recipients, remoteItem)
        }
    }
}
