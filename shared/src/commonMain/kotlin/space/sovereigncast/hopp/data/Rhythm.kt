package space.sovereigncast.hopp.data

import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** How often a show publishes — for "next: usually Mon." on Home (device feedback 2026-10-12). */
sealed interface Rhythm {
    data object Daily : Rhythm
    /** Weekly; [weekday] (1 = Monday … 7) when most of its episodes come out on one day. */
    data class Weekly(val weekday: Int?) : Rhythm
    data object Biweekly : Rhythm
    data object Monthly : Rhythm
}

/**
 * The rhythm of a show from its episodes' publication times (epoch seconds):
 * the median gap of the last nine, so one special episode or a summer break
 * does not decide it. Null when it has no clear one, or too few episodes.
 */
@OptIn(ExperimentalTime::class)
fun rhythmOf(published: List<Long>, zone: TimeZone = TimeZone.currentSystemDefault()): Rhythm? {
    val recent = published.sortedDescending().take(9)
    if (recent.size < 4) return null
    val gaps = recent.zipWithNext { a, b -> (a - b) / 86_400.0 }.sorted()
    val median = gaps[gaps.size / 2]
    return when {
        median <= 1.5 -> Rhythm.Daily
        median in 5.0..9.0 -> {
            val days = recent.map { Instant.fromEpochSeconds(it).toLocalDateTime(zone).dayOfWeek.isoDayNumber }
            val (day, count) = days.groupingBy { it }.eachCount().maxBy { it.value }
            Rhythm.Weekly(day.takeIf { count * 10 >= days.size * 6 })
        }
        median in 12.0..16.0 -> Rhythm.Biweekly
        median in 26.0..35.0 -> Rhythm.Monthly
        else -> null
    }
}
