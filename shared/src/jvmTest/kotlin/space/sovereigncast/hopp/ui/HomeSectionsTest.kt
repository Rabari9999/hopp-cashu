package space.sovereigncast.hopp.ui

import space.sovereigncast.hopp.ui.library.HomeSections
import space.sovereigncast.hopp.ui.library.HomeSections.BOOSTED
import space.sovereigncast.hopp.ui.library.HomeSections.CONTINUE
import space.sovereigncast.hopp.ui.library.HomeSections.FAVORITES
import space.sovereigncast.hopp.ui.library.HomeSections.NEW
import space.sovereigncast.hopp.ui.library.HomeSections.QUEUE
import space.sovereigncast.hopp.ui.library.HomeSections.RECENT
import space.sovereigncast.hopp.ui.library.HomeSections.TAKE_PART
import space.sovereigncast.hopp.ui.library.HomeSections.YOUR_SHOWS
import kotlin.test.Test
import kotlin.test.assertEquals

/** The order of Home's sections (device feedback 2026-10-13). */
class HomeSectionsTest {
    /** Since 0.3.3 listening first: what is under way, what comes next (Dennis, maintainer 2026-11-02). */
    @Test
    fun hoppsOwnOrderPutsListeningFirst() {
        assertEquals(listOf(CONTINUE, QUEUE, NEW, TAKE_PART, YOUR_SHOWS, FAVORITES, BOOSTED, RECENT), HomeSections.order(emptyList()))
    }

    @Test
    fun theListenersOrderHolds() {
        val mine = listOf(RECENT, NEW, CONTINUE, QUEUE, YOUR_SHOWS, TAKE_PART, FAVORITES, BOOSTED)
        assertEquals(mine, HomeSections.order(mine))
    }

    /** Hopp's order before 0.3.3, kept for who had it: "Up next" comes in right after "Continue listening". */
    @Test
    fun theOldOrderGainsUpNextAfterContinue() {
        assertEquals(listOf(TAKE_PART, YOUR_SHOWS, CONTINUE, QUEUE, NEW, FAVORITES, BOOSTED, RECENT), HomeSections.order(HomeSections.before033))
    }

    /** A section added later goes where Hopp's order puts it; one dropped — "From your
     *  shows" — or unknown leaves a saved order without a gap. */
    @Test
    fun aSavedOrderLosesWhatHoppDroppedAndGainsWhatItAdded() {
        val saved = listOf(RECENT, "fromshows", NEW, CONTINUE, TAKE_PART, FAVORITES, BOOSTED)
        assertEquals(listOf(RECENT, NEW, CONTINUE, QUEUE, TAKE_PART, YOUR_SHOWS, FAVORITES, BOOSTED), HomeSections.order(saved))
    }
}
