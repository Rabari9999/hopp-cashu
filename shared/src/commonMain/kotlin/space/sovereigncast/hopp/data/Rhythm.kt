package space.sovereigncast.hopp.data

import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** How often a show publishes — for "next: usually Mon." on Home (device feedback 2026-10-12). */
sealed interface Rhythm {
    data object Daily : Rhythm
    /** More than weekly, on no one day. */
    data object SeveralWeekly : Rhythm
    /** Weekly; [weekday] (1 = Monday … 7) when most of its episodes come out on one day. */
    data class Weekly(val weekday: Int?) : Rhythm
    data object Biweekly : Rhythm
    data object Monthly : Rhythm
}

/**
 * The rhythm of a show from its episodes' publication times (epoch seconds),
 * over its last nine. A weekday that most of them share wins — Nodesignal's
 * Friday talk with recaps and specials in between (device feedback
 * 2026-10-12: median gap 4.8 days, shown as nothing) is "usually Fri.", as
 * long as it comes out about weekly or more. Otherwise the median gap decides,
 * so one special episode or a summer break does not. Null when there is no
 * clear rhythm, or too few episodes.
 */
@OptIn(ExperimentalTime::class)
fun rhythmOf(published: List<Long>, zone: TimeZone = TimeZone.currentSystemDefault()): Rhythm? {
    val recent = published.sortedDescending().take(9)
    if (recent.size < 4) return null
    val gaps = recent.zipWithNext { a, b -> (a - b) / 86_400.0 }.sorted()
    val median = gaps[gaps.size / 2]
    val days = recent.map { Instant.fromEpochSeconds(it).toLocalDateTime(zone).dayOfWeek.isoDayNumber }
    val (day, count) = days.groupingBy { it }.eachCount().maxBy { it.value }
    val usualDay = day.takeIf { count * 10 >= days.size * 6 }
    return when {
        median <= 1.5 -> Rhythm.Daily
        median <= 9.0 && usualDay != null -> Rhythm.Weekly(usualDay)
        median < 5.0 -> Rhythm.SeveralWeekly
        median <= 9.0 -> Rhythm.Weekly(null)
        median in 12.0..16.0 -> Rhythm.Biweekly
        median in 26.0..35.0 -> Rhythm.Monthly
        else -> null
    }
}
