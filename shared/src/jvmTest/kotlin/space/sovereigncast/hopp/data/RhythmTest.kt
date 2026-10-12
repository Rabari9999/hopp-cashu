package space.sovereigncast.hopp.data

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When a show usually comes out (Home, device feedback 2026-10-12). */
class RhythmTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val day = 86_400L
    // Saturday, 2026-10-10 06:00 UTC (08:00 in Berlin).
    private val saturday = 1_791_612_000L

    private fun every(days: Int, n: Int, from: Long = saturday) = List(n) { from - it * days * day }

    @Test fun weeklyOnSaturdays() = assertEquals(Rhythm.Weekly(6), rhythmOf(every(7, 9), berlin))

    @OptIn(kotlin.time.ExperimentalTime::class) @Test fun weeklyOnNoOneDay() {
        // Alternating Saturday and Monday, about a week apart: weekly, no day to name.
        val dates = List(8) { i -> saturday - i * 7 * day + (if (i % 2 == 0) 0 else 2 * day) }
        assertEquals(Rhythm.Weekly(null), rhythmOf(dates, berlin))
    }

    @Test fun oneSpecialEpisodeDoesNotDecide() =
        assertEquals(Rhythm.Weekly(6), rhythmOf(every(7, 8) + (saturday - 3 * day), berlin))

    @Test fun otherRhythms() {
        assertEquals(Rhythm.Daily, rhythmOf(every(1, 9), berlin))
        assertEquals(Rhythm.Biweekly, rhythmOf(every(14, 6), berlin))
        assertEquals(Rhythm.Monthly, rhythmOf(every(30, 6), berlin))
    }

    @Test fun noneWithoutOne() {
        assertNull(rhythmOf(every(7, 3), berlin), "too few")
        assertNull(rhythmOf(every(60, 6), berlin), "rarer than monthly")
        assertNull(rhythmOf(every(21, 6), berlin), "every three weeks has no name here")
    }

    /** Nodesignal's last nine (device feedback 2026-10-12): Friday talks, recaps on
     *  Wednesdays and specials in between — usually Friday, not nothing. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test fun nodesignalIsUsuallyFriday() {
        val dates = listOf("2026-10-09T13:51:00Z", "2026-10-07T04:00:00Z", "2026-10-02T09:03:00Z", "2026-09-25T20:35:00Z", "2026-09-18T08:00:00Z",
            "2026-09-14T15:35:00Z", "2026-09-11T08:40:00Z", "2026-09-04T13:45:00Z", "2026-09-02T04:00:00Z")
            .map { kotlin.time.Instant.parse(it).epochSeconds }
        assertEquals(Rhythm.Weekly(5), rhythmOf(dates, berlin))
    }

    @Test fun severalAWeekOnNoOneDay() {
        val dates = List(9) { saturday - it * 3 * day } // every three days: no day repeats much
        assertEquals(Rhythm.SeveralWeekly, rhythmOf(dates, berlin))
    }
}
