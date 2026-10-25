package space.sovereigncast.hopp.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A relay message's type and subscription, read as JSON reads them (delta audit 6 2026-10-24, RP-02). */
class RelayFrameTest {
    @Test
    fun theHeadIsReadAsJsonReadsIt() {
        assertEquals("EVENT" to "hopp-ab", RelayFrame.head("""["EVENT","hopp-ab",{}]"""))
        assertEquals("EOSE" to "hopp-ab", RelayFrame.head(" ".repeat(100) + """[ "EOSE" ,${"\n\t"} "hopp-ab" ]"""))
        assertEquals("EVENT" to "hopp-ab", RelayFrame.head("""["EVENT","hopp-ab",{}]"""))
        assertEquals("OK" to "a\"b\\c/", RelayFrame.head("""["OK","a\"b\\c\/",true,""]"""))
        // The subscription only in what follows is not its subscription.
        assertEquals("EVENT" to "foreign", RelayFrame.head("""["EVENT","foreign",{"content":"\"hopp-ab\""}]"""))
        assertEquals("NOTICE" to "hopp-ab", RelayFrame.head("""["NOTICE","hopp-ab"]"""))
    }

    @Test
    fun anythingElseHasNoHead() {
        for (text in listOf("", "{}", "[]", """["EOSE"]""", """["EOSE",1]""", """["EOSE" "x"]""", """["EOSE","x""", """["EOSE","\x"]""",
            """["EOSE","\u00"]""", "[\"EOSE\",\"a\u0001\"]", """["EVENT","${"x".repeat(200)}"]""")) {
            assertNull(RelayFrame.head(text), text)
        }
    }
}
