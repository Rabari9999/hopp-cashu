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

    /** Device check 2026-10-20: an English phone showed the speed as "1,1×". */
    @Test
    fun decimalsInTheListenersLanguage() {
        assertEquals("1.1", space.sovereigncast.hopp.ui.components.formatTenths(11, "en"))
        assertEquals("1,1", space.sovereigncast.hopp.ui.components.formatTenths(11, "de"))
        assertEquals("2", space.sovereigncast.hopp.ui.components.formatTenths(20, "de"))
        assertEquals("1,5 GB", space.sovereigncast.hopp.ui.components.formatBytes(1536L * 1024 * 1024, "de"))
        assertEquals("80 MB", space.sovereigncast.hopp.ui.components.formatBytes(80L * 1024 * 1024, "en"))
    }

    @Test
    fun isoTimesFromTheInstance() {
        assertEquals("09.10. 23:50", formatIsoShort("2026-10-09T21:50:00Z"))
        assertEquals("soon", formatIsoShort("soon"))
        assertEquals(lateEvening, isoToEpochSeconds("2026-10-08T22:30:00Z"))
        assertNull(isoToEpochSeconds(""))
    }
}

/** What a boost reports — quick boost (device feedback 2026-10-10) and the boost sheet (2026-10-11). */
class QuickBoostFeedbackTest {
    private fun s(f: String) = space.sovereigncast.hopp.data.Settings(boostFeedback = f)

    @Test
    fun onlyFailuresByDefault() {
        val d = space.sovereigncast.hopp.data.Settings()
        kotlin.test.assertFalse(d.reportsBoost(ok = true))
        kotlin.test.assertTrue(d.reportsBoost(ok = false))
    }

    @Test
    fun nothingOrEverything() {
        kotlin.test.assertFalse(s("off").reportsBoost(ok = false))
        kotlin.test.assertTrue(s("always").reportsBoost(ok = true))
    }

    @Test
    fun eachCaseOnItsOwn() {
        val z = space.sovereigncast.hopp.data.Settings(zapFeedback = "always", quickBoostFeedback = "off")
        kotlin.test.assertTrue(z.reportsZap(ok = true))
        kotlin.test.assertFalse(z.reportsBoost(ok = false, quick = true))
        kotlin.test.assertTrue(z.reportsBoost(ok = false))
    }

    @Test
    fun theOneChoiceOfBeforeCarriesOver() {
        val s = space.sovereigncast.hopp.data.Settings.from(mapOf("boost_feedback" to "always"))
        kotlin.test.assertEquals("always", s.zapFeedback)
        kotlin.test.assertEquals("always", s.quickBoostFeedback)
    }
}
