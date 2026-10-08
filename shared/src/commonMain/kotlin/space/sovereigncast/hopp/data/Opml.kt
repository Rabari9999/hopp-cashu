package space.sovereigncast.hopp.data

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.core.KtXmlReader

/** OPML import and export (ADR 0008): the subscription list other apps use. */
@OptIn(ExperimentalXmlUtilApi::class)
object Opml {
    data class Entry(val feedUrl: String, val title: String?)

    /** Every outline with a feed URL, nested folders included, de-duplicated. */
    fun parse(text: String): List<Entry> {
        val reader = KtXmlReader(text, expandEntities = false, relaxed = true)
        val out = LinkedHashMap<String, Entry>()
        try {
            while (reader.hasNext()) {
                if (reader.next() == EventType.START_ELEMENT && reader.localName.equals("outline", ignoreCase = true)) {
                    var url: String? = null
                    var title: String? = null
                    for (i in 0 until reader.attributeCount) {
                        when (reader.getAttributeLocalName(i).lowercase()) {
                            "xmlurl" -> url = reader.getAttributeValue(i).trim()
                            "title" -> title = reader.getAttributeValue(i).trim()
                            "text" -> if (title == null) title = reader.getAttributeValue(i).trim()
                        }
                    }
                    if (!url.isNullOrEmpty()) out.getOrPut(url) { Entry(url, title?.ifEmpty { null }) }
                }
            }
        } catch (_: Exception) {
            // A broken tail costs the entries after it, not the ones read.
        }
        return out.values.toList()
    }

    fun export(entries: List<Entry>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<opml version="2.0">""").append('\n')
        append("  <head><title>Hopp subscriptions</title></head>\n  <body>\n")
        for (e in entries) {
            append("""    <outline type="rss" text="""").append(esc(e.title ?: e.feedUrl))
                .append("""" title="""").append(esc(e.title ?: e.feedUrl))
                .append("""" xmlUrl="""").append(esc(e.feedUrl)).append("\"/>\n")
        }
        append("  </body>\n</opml>\n")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
