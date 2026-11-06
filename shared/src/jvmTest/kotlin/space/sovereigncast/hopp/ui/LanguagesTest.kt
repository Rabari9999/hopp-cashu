package space.sovereigncast.hopp.ui

import space.sovereigncast.hopp.ui.components.Languages
import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.ui.components.formatMsat
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.components.formatTenths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The languages Hopp speaks are listed once (Languages.kt, maintainer 2026-11-02): Android's
 * per-app language choice and iOS's name the same ones, and each writes its numbers and dates
 * its own way.
 */
class LanguagesTest {
    private val codes = Languages.all.map { it.code }

    @Test
    fun androidOffersTheSameLanguages() {
        val xml = File("../androidApp/src/main/res/xml/locales_config.xml").readText()
        assertEquals(codes, Regex("""android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList())
    }

    @Test
    fun iosNamesTheSameLanguages() {
        val plist = File("../iosApp/iosApp/Info.plist").readText()
        val list = plist.substringAfter("<key>CFBundleLocalizations</key>").substringBefore("</array>")
        assertEquals(codes, Regex("""<string>([^<]+)</string>""").findAll(list).map { it.groupValues[1] }.toList())
    }

    @Test
    fun eachLanguageWritesNumbersAndDatesItsWay() {
        val day = kotlinx.datetime.LocalDate(2026, 10, 7)
        assertEquals(listOf("07.10.2026", "07.10.2026", "07/10/2026", "07/10/2026"), codes.map { formatDate(day, it) })
        assertEquals(listOf("21,000", "21.000", "21.000", "21\u202F000"), codes.map { formatSats(21_000, it) })
        assertEquals(listOf("1.5", "1,5", "1,5", "1,5"), codes.map { formatTenths(15, it) })
        assertEquals(listOf("2.5", "2,5", "2,5", "2,5"), codes.map { formatMsat(2_500, it) })
        // A language Hopp does not speak writes as English does.
        assertEquals("21,000", formatSats(21_000, "it"))
    }
}
