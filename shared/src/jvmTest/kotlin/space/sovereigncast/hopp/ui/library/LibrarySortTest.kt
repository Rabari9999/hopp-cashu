package space.sovereigncast.hopp.ui.library

import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.Show
import space.sovereigncast.hopp.data.ShowCounts
import kotlin.test.Test
import kotlin.test.assertEquals

/** Device feedback 2026-10-19: the library by newest episode, A–Z or most unheard. */
class LibrarySortTest {
    private fun show(id: Long, title: String) = Show(id, "https://$id.test/rss", title, null, null, null)
    private val shows = listOf(show(1, "beta"), show(2, "Alpha"), show(3, "Gamma"), show(4, "Delta"))
    private val counts = mapOf(
        1L to ShowCounts(5, 0, latestAt = 300),
        2L to ShowCounts(5, 4, latestAt = 100),
        3L to ShowCounts(5, 4, latestAt = 200),
        // 4: no episodes yet — last.
    )

    @Test
    fun newestEpisodeFirstIsTheDefault() {
        assertEquals(Settings.SORT_NEWEST, Settings().librarySort)
        assertEquals(listOf("beta", "Gamma", "Alpha", "Delta"), sorted(shows, counts, Settings.SORT_NEWEST).map { it.title })
    }

    @Test
    fun aToZIgnoresCaseAndMostUnheardBreaksTiesByTheNewest() {
        assertEquals(listOf("Alpha", "beta", "Delta", "Gamma"), sorted(shows, counts, Settings.SORT_TITLE).map { it.title })
        assertEquals(listOf("Gamma", "Alpha", "beta", "Delta"), sorted(shows, counts, Settings.SORT_UNHEARD).map { it.title })
    }
}
