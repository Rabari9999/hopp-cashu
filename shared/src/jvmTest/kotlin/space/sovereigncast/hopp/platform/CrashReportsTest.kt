package space.sovereigncast.hopp.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Crash reports, shared by hand and free of what is private (ADR 0015). */
class CrashReportsTest {
    @Test
    fun aReportSaysWhereNotWhat() {
        val error = IllegalStateException(
            "feed https://pod.example/feed.xml failed for nostr+walletconnect://abc?secret=" + "a".repeat(64) +
                " and karsten@example.com, invoice lnbc210n1pjexampleexampleexampleexample",
        )
        val report = CrashReports.format(AppInfo("0.1.0", "Android", "Phone"), "15 (SDK 35)", "2026-10-09T12:00:00Z", "main", error)
        assertTrue(report.startsWith("Hopp crash report\nVersion: 0.1.0\nSystem: Android 15 (SDK 35); Phone\nTime: 2026-10-09T12:00:00Z\nThread: main\n"))
        assertTrue("IllegalStateException" in report && "aReportSaysWhereNotWhat" in report, "the trace stays")
        listOf("pod.example", "walletconnect", "a".repeat(64), "karsten@", "lnbc210n1").forEach { assertFalse(it in report, "$it leaked") }
    }

    @Test
    fun theLastReportWaitsUntilDeleted() {
        val dir = kotlin.io.path.createTempDirectory("crash").toString()
        val reports = CrashReports("$dir/crash")
        assertNull(reports.pending())
        reports.write("Hopp crash report\nboom")
        assertEquals("Hopp crash report\nboom", reports.pending())
        reports.clear()
        assertNull(reports.pending())
        assertNull(CrashReports(null).pending(), "a platform without a place keeps none")
    }
}
