package space.sovereigncast.hopp.ui

import space.sovereigncast.hopp.ui.library.HomeSections
import space.sovereigncast.hopp.ui.library.HomeSections.BOOSTED
import space.sovereigncast.hopp.ui.library.HomeSections.CONTINUE
import space.sovereigncast.hopp.ui.library.HomeSections.FAVORITES
import space.sovereigncast.hopp.ui.library.HomeSections.NEW
import space.sovereigncast.hopp.ui.library.HomeSections.RECENT
import space.sovereigncast.hopp.ui.library.HomeSections.TAKE_PART
import space.sovereigncast.hopp.ui.library.HomeSections.YOUR_SHOWS
import kotlin.test.Test
import kotlin.test.assertEquals

/** The order of Home's sections (device feedback 2026-10-13). */
class HomeSectionsTest {
    @Test
    fun hoppsOwnOrderPutsTakingPartAndTheShowsFirst() {
        assertEquals(listOf(TAKE_PART, YOUR_SHOWS, CONTINUE, NEW, FAVORITES, BOOSTED, RECENT), HomeSections.order(emptyList()))
    }

    @Test
    fun theListenersOrderHolds() {
        val mine = listOf(RECENT, NEW, CONTINUE, YOUR_SHOWS, TAKE_PART, FAVORITES, BOOSTED)
        assertEquals(mine, HomeSections.order(mine))
    }

    /** A section added later goes where Hopp's order puts it; one dropped — "From your
     *  shows" — or unknown leaves a saved order without a gap. */
    @Test
    fun aSavedOrderLosesWhatHoppDroppedAndGainsWhatItAdded() {
        val saved = listOf(RECENT, "fromshows", NEW, CONTINUE, TAKE_PART, FAVORITES, BOOSTED)
        assertEquals(listOf(RECENT, NEW, CONTINUE, TAKE_PART, YOUR_SHOWS, FAVORITES, BOOSTED), HomeSections.order(saved))
    }
}
