package space.sovereigncast.hopp.ui

import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.sovereigncast.ShowAction
import space.sovereigncast.hopp.ui.library.TakePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hiding single entries under Home's "Take part" (device feedback 2026-10-22). */
class TakePartTest {
    private fun action(kind: ShowAction.Kind, episode: String? = "e1", guid: String = "g1") =
        ShowAction(1, "Nodesignal", null, kind, "E1", 0, 100, null, episodeGuid = episode, podcastGuid = guid)

    @Test
    fun aRoundHidesItselfOnlyANewOneComesBack() {
        val hidden = listOf(TakePart.itself(action(ShowAction.Kind.ReleaseBoosting))!!)
        assertTrue(TakePart.hides(hidden, action(ShowAction.Kind.ReleaseBoosting)))
        assertFalse(TakePart.hides(hidden, action(ShowAction.Kind.ReleaseBoosting, episode = "e2")))
        assertFalse(TakePart.hides(hidden, action(ShowAction.Kind.Boost2Unlock)))
        assertFalse(TakePart.hides(hidden, action(ShowAction.Kind.Suggestions, episode = null)))
    }

    @Test
    fun aShowHidesAllOfItsOwnAndNoOtherShows() {
        val hidden = listOf(TakePart.show("g1"))
        ShowAction.Kind.entries.forEach { assertTrue(TakePart.hides(hidden, action(it))) }
        assertFalse(TakePart.hides(hidden, action(ShowAction.Kind.ReleaseBoosting, guid = "g2")))
        // Without a guid nothing can be hidden — and nothing is.
        assertFalse(TakePart.hides(listOf(TakePart.show("")), action(ShowAction.Kind.ReleaseBoosting, guid = "")))
    }

    @Test
    fun keysReadBackAndTheOldestGoBeyondTheLimit() {
        assertEquals(TakePart.Entry(TakePart.Scope.Unlock, "g1", "e1"), TakePart.parse(TakePart.itself(action(ShowAction.Kind.Boost2Unlock))!!))
        assertEquals(TakePart.Entry(TakePart.Scope.Suggestions, "g1"), TakePart.parse("suggestions:g1"))
        assertNull(TakePart.parse("rb:g1"))
        assertNull(TakePart.parse("nonsense"))
        var list = emptyList<String>()
        repeat(Settings.TAKE_PART_HIDDEN_MAX + 5) { list = TakePart.add(list, "show:g$it") }
        assertEquals(Settings.TAKE_PART_HIDDEN_MAX, list.size)
        assertEquals("show:g5", list.first())
        // Hidden again: once, as the newest.
        assertEquals(listOf("show:g6", "show:g5"), TakePart.add(listOf("show:g5", "show:g6"), "show:g5"))
    }
}
