package space.sovereigncast.hopp.ui

import space.sovereigncast.hopp.ui.library.HomeSections
import space.sovereigncast.hopp.ui.library.HomeSections.BOOSTED
import space.sovereigncast.hopp.ui.library.HomeSections.CONTINUE
import space.sovereigncast.hopp.ui.library.HomeSections.FAVORITES
import space.sovereigncast.hopp.ui.library.HomeSections.FROM_SHOWS
import space.sovereigncast.hopp.ui.library.HomeSections.NEW
import space.sovereigncast.hopp.ui.library.HomeSections.RECENT
import space.sovereigncast.hopp.ui.library.HomeSections.TAKE_PART
import space.sovereigncast.hopp.ui.library.HomeSections.YOUR_SHOWS
import kotlin.test.Test
import kotlin.test.assertEquals

/** The order of Home's sections (device feedback 2026-10-13). */
class HomeSectionsTest {
    @Test
    fun withoutAnOrderOfTheirOwnHoppsMovesTheUnheardUpOnAQuietDay() {
        assertEquals(HomeSections.withNews, HomeSections.order(emptyList(), quiet = false))
        assertEquals(HomeSections.quietDay, HomeSections.order(emptyList(), quiet = true))
        check(HomeSections.quietDay.indexOf(FROM_SHOWS) < HomeSections.quietDay.indexOf(FAVORITES))
        check(HomeSections.withNews.indexOf(FROM_SHOWS) > HomeSections.withNews.indexOf(BOOSTED))
    }

    @Test
    fun theListenersOrderHoldsWhateverTheDay() {
        val mine = listOf(RECENT, FROM_SHOWS, NEW, CONTINUE, YOUR_SHOWS, TAKE_PART, FAVORITES, BOOSTED)
        assertEquals(mine, HomeSections.order(mine, quiet = false))
        assertEquals(mine, HomeSections.order(mine, quiet = true))
    }

    /** A section added in a later version goes where Hopp's own order puts it; unknown keys drop out. */
    @Test
    fun aSectionTheSavedOrderDoesNotKnowFindsItsPlace() {
        val saved = listOf(RECENT, NEW, CONTINUE, YOUR_SHOWS, TAKE_PART, FAVORITES, BOOSTED, "gone")
        assertEquals(listOf(RECENT, NEW, CONTINUE, YOUR_SHOWS, TAKE_PART, FAVORITES, BOOSTED, FROM_SHOWS), HomeSections.order(saved, quiet = false))
    }
}
