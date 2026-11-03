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
    /** Since 0.3.3 taking part on top, always (maintainer 2026-11-03), then the playlist — what plays and what
     *  comes next (Dennis, 2026-11-02 and -03). */
    @Test
    fun hoppsOwnOrderPutsTakingPartFirstThenThePlaylist() {
        assertEquals(listOf(TAKE_PART, QUEUE, NEW, YOUR_SHOWS, FAVORITES, BOOSTED, RECENT), HomeSections.order(emptyList()))
    }

    @Test
    fun theListenersOrderHolds() {
        val mine = listOf(RECENT, NEW, QUEUE, YOUR_SHOWS, TAKE_PART, FAVORITES, BOOSTED)
        assertEquals(mine, HomeSections.order(mine))
    }

    /** "Continue listening" became the playlist: the playlist takes its place, once. */
    @Test
    fun thePlaylistTakesContinuesPlace() {
        assertEquals(listOf(RECENT, NEW, QUEUE, YOUR_SHOWS, TAKE_PART, FAVORITES, BOOSTED),
            HomeSections.order(listOf(RECENT, NEW, CONTINUE, YOUR_SHOWS, TAKE_PART, QUEUE, FAVORITES, BOOSTED)))
        // Hopp's order before 0.3.3, kept for who had it.
        assertEquals(listOf(TAKE_PART, YOUR_SHOWS, QUEUE, NEW, FAVORITES, BOOSTED, RECENT), HomeSections.order(HomeSections.before033))
    }

    /** A section added later goes where Hopp's order puts it; one dropped — "From your
     *  shows" — or unknown leaves a saved order without a gap. */
    @Test
    fun aSavedOrderLosesWhatHoppDroppedAndGainsWhatItAdded() {
        val saved = listOf(RECENT, "fromshows", NEW, TAKE_PART, FAVORITES, BOOSTED)
        assertEquals(listOf(RECENT, NEW, YOUR_SHOWS, TAKE_PART, QUEUE, FAVORITES, BOOSTED), HomeSections.order(saved))
    }
}
