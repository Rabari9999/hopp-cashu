package space.sovereigncast.hopp.feed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.sovereigncast.hopp.model.TranscriptRef
import space.sovereigncast.hopp.ui.components.decodeEntities

/** One line of a transcript; [startMs] is null where the source has no times. */
data class Cue(val startMs: Long?, val endMs: Long?, val speaker: String?, val text: String)

/**
 * `podcast:transcript` in the formats the feature matrix names: WebVTT, SRT,
 * the namespace's JSON, HTML (with PodHome's `<time>` marks and `<cite>`
 * speakers) and plain text (ADR 0012).
 */
object TranscriptParser {
    /** The one to load: timed before untimed, VTT and JSON first (feature matrix). */
    fun pick(refs: List<TranscriptRef>): TranscriptRef? = refs.minByOrNull { r ->
        when (kind(r.type, r.url)) {
            Kind.VTT -> 0; Kind.JSON -> 1; Kind.SRT -> 2; Kind.HTML -> 3; Kind.TEXT -> 4
        }
    }

    fun parse(text: String, type: String = "", url: String = ""): List<Cue> {
        val body = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val k = kind(type, url).let { declared ->
            val head = body.trimStart()
            when {
                head.startsWith("WEBVTT") -> Kind.VTT
                head.startsWith("{") -> Kind.JSON
                declared == Kind.TEXT && "-->" in head.take(200) -> Kind.SRT
                else -> declared
            }
        }
        return when (k) {
            Kind.VTT, Kind.SRT -> cues(body)
            Kind.JSON -> json(body)
            Kind.HTML -> html(body)
            Kind.TEXT -> body.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }.map { Cue(null, null, null, it) }
        }
    }

    private enum class Kind { VTT, SRT, JSON, HTML, TEXT }

    private fun kind(type: String, url: String): Kind {
        val t = type.lowercase(); val u = url.lowercase().substringBefore('?')
        return when {
            "vtt" in t || u.endsWith(".vtt") -> Kind.VTT
            "subrip" in t || "srt" in t || u.endsWith(".srt") -> Kind.SRT
            "json" in t || u.endsWith(".json") -> Kind.JSON
            "html" in t || u.endsWith(".html") || u.endsWith(".htm") -> Kind.HTML
            else -> Kind.TEXT
        }
    }

    private val TIMING = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val VOICE = Regex("""<v(?:\.[^\s>]*)?\s+([^>]+)>""")
    private val TAG = Regex("<[^>]+>")

    /** WebVTT and SRT: blocks of an optional id, a timing line and text. */
    private fun cues(body: String): List<Cue> = body.split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.lines().filter { it.isNotBlank() }
        val at = lines.indexOfFirst { TIMING.containsMatchIn(it) }
        if (at < 0) return@mapNotNull null
        val m = TIMING.find(lines[at])!!
        val raw = lines.drop(at + 1).joinToString("\n")
        // Only VTT's voice tag names a speaker; "Name: …" at the start of a
        // line is too often "Wichtig: …" to be read as one.
        val speaker = VOICE.find(raw)?.groupValues?.get(1)?.trim()
        val text = decodeEntities(TAG.replace(raw, "")).trim()
        if (text.isEmpty()) null else Cue(time(m.groupValues[1]), time(m.groupValues[2]), speaker, text)
    }

    private fun time(s: String): Long? {
        val parts = s.replace(',', '.').split(':')
        val sec = parts.last().toDoubleOrNull() ?: return null
        val min = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0
        val hour = if (parts.size == 3) parts[0].toLongOrNull() ?: 0 else 0
        return (hour * 3600 + min * 60) * 1000 + (sec * 1000).toLong()
    }

    /**
     * The namespace's JSON: segments, often one word each. Words of one
     * speaker are joined until a sentence ends or the line gets long.
     */
    private fun json(body: String): List<Cue> {
        val segments = runCatching { Json.parseToJsonElement(body).jsonObject["segments"]!!.jsonArray }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Cue>()
        var start: Long? = null; var end: Long? = null; var speaker: String? = null
        val words = StringBuilder()
        fun flush() {
            val t = words.toString().trim()
            if (t.isNotEmpty()) out += Cue(start, end, speaker, t)
            words.clear(); start = null
        }
        segments.forEach { el ->
            val o = el.jsonObject
            val text = o["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) return@forEach
            val who = o["speaker"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (words.isNotEmpty() && who != speaker) flush()
            speaker = who
            if (start == null) start = o["startTime"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() }
            end = o["endTime"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() }
            if (words.isNotEmpty()) words.append(' ')
            words.append(text)
            if (text.last() in ".?!" || words.length > 240) flush()
        }
        flush()
        return out
    }

    private val HTML_PART = Regex("""<(time|cite|p)\b[^>]*>(.*?)</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** HTML: `<cite>` names the speaker, `<time>` marks the next paragraph. */
    private fun html(body: String): List<Cue> {
        val clean = body.replace(Regex("<(script|style)\\b.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        val out = mutableListOf<Cue>()
        var speaker: String? = null; var at: Long? = null
        HTML_PART.findAll(clean).forEach { m ->
            val text = decodeEntities(TAG.replace(m.groupValues[2], " ")).replace(Regex("\\s+"), " ").trim()
            when (m.groupValues[1].lowercase()) {
                "cite" -> speaker = text.removeSuffix(":").trim().ifEmpty { null }
                "time" -> at = time(if (text.contains('.') || text.contains(',')) text else "$text.0")
                else -> if (text.isNotEmpty()) { out += Cue(at, null, speaker, text); at = null }
            }
        }
        if (out.isEmpty()) {
            val text = decodeEntities(TAG.replace(clean.replace(Regex("<br\\s*/?>|</p>|</div>", RegexOption.IGNORE_CASE), "\n\n"), ""))
            return parse(text, "text/plain")
        }
        return out
    }
}

/** The cue playing at [positionMs]: the last one that has started. */
fun List<Cue>.indexAt(positionMs: Long): Int {
    var lo = 0; var hi = size - 1; var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val s = this[mid].startMs ?: return -1
        if (s <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}
