package space.sovereigncast.hopp.ui

import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.ui.components.formatDateTime
import space.sovereigncast.hopp.ui.components.formatIsoShort
import space.sovereigncast.hopp.ui.components.isoToEpochSeconds
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Dates and times on the listener's clock (device feedback 2026-10-09). */
class FormatTest {
    private val saved = TimeZone.getDefault()
    @BeforeTest fun berlin() = TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    @AfterTest fun restore() = TimeZone.setDefault(saved)

    // 2026-10-08T22:30:00Z — already the 9th in Berlin.
    private val lateEvening = 1_791_498_600L

    @Test
    fun aPaymentShowsDateAndLocalTime() {
        assertEquals("09.10.2026 · 00:30", formatDateTime(lateEvening))
        assertEquals("09.10.2026", formatDate(lateEvening), "the date follows the local clock too")
    }

    @Test
    fun isoTimesFromTheInstance() {
        assertEquals("09.10. 23:50", formatIsoShort("2026-10-09T21:50:00Z"))
        assertEquals("soon", formatIsoShort("soon"))
        assertEquals(lateEvening, isoToEpochSeconds("2026-10-08T22:30:00Z"))
        assertNull(isoToEpochSeconds(""))
    }
}
