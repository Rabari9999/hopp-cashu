package space.sovereigncast.hopp.ui

import space.sovereigncast.hopp.model.Trailer
import space.sovereigncast.hopp.ui.library.latestTrailer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which of a feed's trailers the show page plays: the newest; undated, the last named. */
class TrailerTest {
    private fun t(url: String, at: Long?) = Trailer(null, url, at, null, null, null)

    @Test fun none() = assertNull(latestTrailer(emptyList()))
    @Test fun theNewest() = assertEquals("b", latestTrailer(listOf(t("a", 1), t("b", 3), t("c", 2)))?.url)
    @Test fun undatedTheLastNamed() = assertEquals("c", latestTrailer(listOf(t("a", null), t("b", null), t("c", null)))?.url)
    @Test fun aDatedOneBeforeAnUndated() = assertEquals("a", latestTrailer(listOf(t("a", 1), t("b", null)))?.url)
}
