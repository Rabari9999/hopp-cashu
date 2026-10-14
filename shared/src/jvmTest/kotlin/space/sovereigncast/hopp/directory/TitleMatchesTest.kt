package space.sovereigncast.hopp.directory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Suggestions while typing (device feedback 2026-10-14): the directories find whole words only. */
class TitleMatchesTest {
    @Test fun aWordBegunMatches() = assertTrue(titleMatches("Nodesignal - Deine Bitcoin-Frequenz", "Nodesig"))
    @Test fun caseSpacesAndPunctuationDoNotCount() = assertTrue(titleMatches("Citadel Dispatch", "citadel dis"))
    @Test fun umlautsMatchTheirSpelling() {
        assertTrue(titleMatches("Münzweg - der Bitcoin-Podcast", "muenzw"))
        assertTrue(titleMatches("Muenzweg", "Münz"))
    }
    @Test fun oneLetterIsNotEnough() = assertFalse(titleMatches("Nodesignal", "N"))
    @Test fun otherLettersDoNot() = assertFalse(titleMatches("Nodesignal", "Nodesix"))
}
