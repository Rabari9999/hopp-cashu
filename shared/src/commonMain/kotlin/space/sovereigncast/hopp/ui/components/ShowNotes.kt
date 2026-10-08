package space.sovereigncast.hopp.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Show notes as styled text: paragraphs, line breaks, lists, bold and italic,
 * links — and time marks ("12:34", "1:02:03") that jump there, as in Pocket
 * Casts. Feeds' HTML is small and simple; a web view would be heavier than the
 * notes are, and there is none in Compose Multiplatform. Unknown tags are
 * dropped, their text kept; script and style vanish with their content.
 */
fun showNotes(html: String, link: SpanStyle, timestamp: SpanStyle, onTimestamp: (Long) -> Unit): AnnotatedString {
    val out = AnnotatedString.Builder()
    val hasTags = TAG.containsMatchIn(html)
    var bold = 0
    var italic = 0
    var skip = 0
    val links = ArrayDeque<Pair<String, Int>>()
    val lists = ArrayDeque<IntArray>() // [ordered 0/1, counter]
    var styleStart = -1

    // The last two characters written — reading them back from the builder
    // would copy the whole text each time.
    var tail = ""
    fun put(s: String) { out.append(s); tail = (tail + s).takeLast(2) }
    fun last(): Char? = tail.lastOrNull()
    fun newline() { if (out.length > 0 && last() != '\n') put("\n") }
    fun paragraph() {
        if (out.length == 0) return
        if (tail != "\n\n") put(if (last() == '\n') "\n" else "\n\n")
    }

    fun text(raw: String) {
        if (skip > 0 || raw.isEmpty()) return
        // &nbsp; in show notes is spacing, not a promise not to break.
        var t = decodeEntities(raw).replace('\u00A0', ' ')
        if (hasTags) {
            t = t.replace(WHITESPACE, " ")
            if (last() == null || last() == '\n' || last() == ' ') t = t.trimStart()
        }
        if (t.isEmpty()) return
        val start = out.length
        put(t)
        val end = out.length
        if (bold > 0) out.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
        if (italic > 0) out.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
        if (links.isEmpty()) linkify(out, t, start, link, timestamp, onTimestamp)
    }

    var pos = 0
    for (m in TAG.findAll(html)) {
        text(html.substring(pos, m.range.first))
        pos = m.range.last + 1
        val closing = m.groupValues[1] == "/"
        val name = m.groupValues[2].lowercase()
        val attrs = m.groupValues[3]
        when (name) {
            "script", "style" -> if (closing) skip = (skip - 1).coerceAtLeast(0) else if (!attrs.trimEnd().endsWith("/")) skip++
            "br" -> put("\n")
            "p", "div", "blockquote", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "table", "tr" -> {
                paragraph()
                if (name.startsWith("h") && name.length == 2) {
                    if (closing) { if (styleStart >= 0) out.addStyle(SpanStyle(fontWeight = FontWeight.Bold), styleStart, out.length); styleStart = -1 }
                    else styleStart = out.length
                }
            }
            "ul", "ol" -> if (closing) { lists.removeLastOrNull(); paragraph() } else { paragraph(); lists.addLast(intArrayOf(if (name == "ol") 1 else 0, 0)) }
            "li" -> if (!closing) {
                newline()
                val list = lists.lastOrNull()
                if (list != null && list[0] == 1) { list[1]++; put("${list[1]}. ") } else put("• ")
            }
            "b", "strong" -> bold = (bold + if (closing) -1 else 1).coerceAtLeast(0)
            "i", "em" -> italic = (italic + if (closing) -1 else 1).coerceAtLeast(0)
            "a" -> if (closing) {
                links.removeLastOrNull()?.let { (href, start) ->
                    if (out.length > start) out.addLink(LinkAnnotation.Url(href, TextLinkStyles(link)), start, out.length)
                }
            } else {
                HREF.find(attrs)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let { href ->
                    if (href.startsWith("http://") || href.startsWith("https://")) links.addLast(decodeEntities(href) to out.length)
                }
            }
        }
    }
    text(html.substring(pos))
    // Trailing blank lines are not content.
    val result = out.toAnnotatedString()
    val end = result.text.trimEnd().length
    return if (end < result.length) result.subSequence(0, end) else result
}

/** Plain URLs and time marks inside a text run become links. */
private fun linkify(out: AnnotatedString.Builder, text: String, offset: Int, link: SpanStyle, timestamp: SpanStyle, onTimestamp: (Long) -> Unit) {
    val taken = mutableListOf<IntRange>()
    for (m in URL.findAll(text)) {
        val url = m.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
        out.addLink(LinkAnnotation.Url(url, TextLinkStyles(link)), offset + m.range.first, offset + m.range.first + url.length)
        taken += m.range
    }
    for (m in TIME.findAll(text)) {
        if (taken.any { m.range.first in it }) continue
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val seconds = h * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()
        out.addLink(
            LinkAnnotation.Clickable("t$seconds", TextLinkStyles(timestamp)) { onTimestamp(seconds) },
            offset + m.range.first, offset + m.range.last + 1,
        )
    }
}

internal fun decodeEntities(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
    val name = m.groupValues[1]
    when {
        name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
        name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
        name == "amp" -> "&"
        name == "lt" -> "<"
        name == "gt" -> ">"
        name == "quot" -> "\""
        name == "apos" -> "'"
        else -> space.sovereigncast.hopp.feed.HTML_ENTITIES[name]
    } ?: m.value
}

private fun codePoint(cp: Int): String? = when {
    cp in 0..0xFFFF -> cp.toChar().toString()
    cp in 0x10000..0x10FFFF -> { val v = cp - 0x10000; charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString() }
    else -> null
}

private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
private val HREF = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")
private val WHITESPACE = Regex("\\s+")
private val URL = Regex("https?://[^\\s<>\"]+")
private val TIME = Regex("(?<![\\d:])(?:(\\d{1,2}):)?([0-5]?\\d):([0-5]\\d)(?![\\d:])")
