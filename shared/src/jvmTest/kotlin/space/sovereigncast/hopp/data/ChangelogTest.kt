package space.sovereigncast.hopp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "What's new" reads the changelogs this build holds (maintainer 2026-11-03). */
class ChangelogTest {
    @Test
    fun bothChangelogsAreReadAndTellTheSameReleases() {
        val en = Changelog.releases(german = false)
        val de = Changelog.releases(german = true)
        assertTrue(en.size > 5, "releases: ${en.map { it.version }}")
        assertEquals(en.map { it.version }, de.map { it.version })
        // The key for "seen" is counted in English: German must count the same.
        assertEquals(en.map { it.newCount to it.fixCount }, de.map { it.newCount to it.fixCount })
        en.filterNot { it.unreleased }.forEach { r ->
            assertNotNull(r.date, r.version)
            assertTrue(r.sections.isNotEmpty(), r.version)
            r.sections.flatMap { it.items }.forEach { assertNotNull(it.area, "${r.version}: ${it.text}") }
        }
    }

    @Test
    fun theEntriesComeAsAreaAndPlainText() {
        val r = Changelog.parse(
            """
            # Changelog

            Intro text, not a release.

            ## [Unreleased]

            ## [1.2.0] - 2026-11-01

            ### 🆕 New Features
            - **Home:** Something `new`.
            - **Player:** Faster.

            ### 🐛 Fixes
            - **Shows:** `&amp;` shows as `&`.
            """.trimIndent(),
        )
        assertEquals(listOf(Changelog.UNRELEASED, "1.2.0"), r.map { it.version })
        assertTrue(r[0].sections.isEmpty())
        val release = r[1]
        assertEquals("2026-11-01", release.date)
        assertEquals(2, release.newCount)
        assertEquals(1, release.fixCount)
        assertEquals(Changelog.Item("Home", "Something new."), release.sections[0].items[0])
        assertEquals(Changelog.Item("Shows", "&amp; shows as &."), release.sections[1].items[0])
        assertTrue(release.sections[1].fixes)
    }

    @Test
    fun aReleaseIsItsOwnAndABuildBetweenReleasesIsWhatIsInWork() {
        val r = Changelog.parse("## [Unreleased]\n### 🆕 New Features\n- **A:** b.\n## [1.0.0] - 2026-01-01\n### 🐛 Fixes\n- **C:** d.\n")
        assertEquals("1.0.0", Changelog.yours(r, "1.0.0")?.version)
        assertEquals(Changelog.UNRELEASED, Changelog.yours(r, "1.0.0-beta.abc1234")?.version)
        assertEquals(Changelog.UNRELEASED, Changelog.yours(r, "1.0.0-39-gabc1234")?.version)
        assertNull(Changelog.yours(r, "9.9.9"))
        // Nothing in work yet: between releases, the release it builds on.
        assertEquals("1.0.0", Changelog.yours(Changelog.parse("## [Unreleased]\n\n## [1.0.0] - 2026-01-01\n### 🐛 Fixes\n- **C:** d.\n"), "1.0.0-beta.x")?.version)
        // Hopp Web carries the apps' last version and the code ahead of it: what is in work.
        assertEquals(Changelog.UNRELEASED, Changelog.yours(r, "1.0.0", between = true)?.version)
        assertEquals("Unreleased:1", Changelog.yours(r, "1.0.0-beta.x")?.seenKey)
    }

    @Test
    fun theTourOfANewInstallMarksWhatIsNewAsSeen() {
        val fresh = Settings().doneTour("0.3.3:16")
        assertEquals("0.3.3:16", fresh.whatsNewSeen)
        // An update keeps what was seen: the line shows once.
        val updated = Settings(onboardingDone = true).doneTour("0.3.3:16")
        assertEquals("", updated.whatsNewSeen)
    }
}
