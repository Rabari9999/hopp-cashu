package space.sovereigncast.hopp.feed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One entry of a Podcasting 2.0 JSON chapters file. */
data class Chapter(
    val startSeconds: Double,
    val title: String,
    val imageUrl: String? = null,
    val url: String? = null,
    /** `toc: false` — e.g. an image change — is not a navigation stop. */
    val inToc: Boolean = true,
)

object ChaptersParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Lenient: entries without a start time are dropped, the rest sorted. */
    fun parse(text: String): List<Chapter> {
        val root = json.parseToJsonElement(text).jsonObject
        val list = root["chapters"]?.jsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el.jsonObject
            val start = o["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            Chapter(
                startSeconds = start.coerceAtLeast(0.0),
                title = o["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                imageUrl = o["img"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                inToc = o["toc"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }.sortedBy { it.startSeconds }
    }
}

/**
 * Chapter navigation (ADR 0019): the cover label, the swipe and the
 * long-press on the skip buttons all go through here, so they agree.
 */
class ChapterIndex(chapters: List<Chapter>) {
    /** Navigation stops only. */
    val stops: List<Chapter> = chapters.filter { it.inToc }
    private val all = chapters

    fun currentIndex(positionMs: Long): Int {
        val s = positionMs / 1000.0
        return stops.indexOfLast { it.startSeconds <= s + 0.001 }
    }

    fun current(positionMs: Long): Chapter? = stops.getOrNull(currentIndex(positionMs))

    /** The image to show: the latest chapter (including non-toc ones) that has one. */
    fun imageAt(positionMs: Long): String? {
        val s = positionMs / 1000.0
        return all.lastOrNull { it.startSeconds <= s + 0.001 && it.imageUrl != null }?.imageUrl
    }

    fun nextStartMs(positionMs: Long): Long? =
        stops.getOrNull(currentIndex(positionMs) + 1)?.let { (it.startSeconds * 1000).toLong() }

    /**
     * Back: to the start of the current chapter, unless we are within the
     * first [graceMs] of it — then to the previous one (the convention of
     * every music player's "previous track").
     */
    fun previousStartMs(positionMs: Long, graceMs: Long = 3_000): Long? {
        val i = currentIndex(positionMs)
        if (i < 0) return 0
        val start = (stops[i].startSeconds * 1000).toLong()
        return if (positionMs - start > graceMs || i == 0) start else (stops[i - 1].startSeconds * 1000).toLong()
    }

    fun remainingInChapterMs(positionMs: Long, durationMs: Long): Long {
        val end = nextStartMs(positionMs) ?: durationMs
        return (end - positionMs).coerceAtLeast(0)
    }

    /** Chapter boundaries as fractions of the duration, for the progress bar gaps. */
    fun boundaryFractions(durationMs: Long): List<Float> =
        if (durationMs <= 0) emptyList()
        else stops.drop(1).map { (it.startSeconds * 1000 / durationMs).toFloat() }.filter { it in 0f..1f }
}
