package space.sovereigncast.hopp.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.model.Episode
import space.sovereigncast.hopp.model.Person
import space.sovereigncast.hopp.model.Podcast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The person search across the library (maintainer 2026-10-26). */
class PeopleTest {
    private val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
    private val people = PeopleRepository(db)

    private fun show(title: String, vararg persons: Person): Long {
        db.podcastQueries.insert("https://pod.test/$title.xml", null, title, null, null,
            json.encodeToString(Podcast.serializer(), Podcast(title = title, persons = persons.toList())), null, null, 0, 0, "")
        return db.podcastQueries.lastInsertId().executeAsOne()
    }

    private fun episode(show: Long, guid: String, published: Long, vararg persons: Person) {
        db.episodeQueries.insertIfNew(show, guid, guid, published, 600, null, "https://cdn.test/$guid.mp3", 0, null,
            json.encodeToString(Episode.serializer(), Episode(guid, guid, publishedAt = published, persons = persons.toList())), 0, 0, 0)
    }

    private fun host(name: String, href: String? = null) = Person(name, "Host", "Cast", null, href)
    private fun guest(name: String) = Person(name, "Guest", "Cast", null, null)

    @Test
    fun anEpisodeNamesThemOrItsShowDoes() = runBlocking {
        val signal = show("Signal")
        episode(signal, "s1", 100, host("Calso", "https://twitter.com/calso21"), host("Thorsten"))
        episode(signal, "s2", 200, host("Thorsten ⚡️", "https://primal.net/p/x"))
        val other = show("Other", host("Calso"))
        episode(other, "o1", 300)                      // names no one: the show's Calso stands for it
        episode(other, "o2", 400, guest("Jan-Paul"))   // names its own: the show's do not count
        assertEquals(listOf("o1", "s1"), people.episodes("Calso").map { it.item.guid })
        assertEquals(listOf("Host", "Host"), people.episodes("Calso").map { it.role })
        // One person, though one feed writes a flash behind the name and gives other links.
        assertEquals(listOf("s2", "s1"), people.episodes("Thorsten").map { it.item.guid })
        assertEquals(listOf("o2"), people.episodes("jan-paul").map { it.item.guid })
    }

    @Test
    fun theSearchFindsPeopleByPartOfTheName() = runBlocking {
        val signal = show("Signal")
        episode(signal, "s1", 100, host("Calso", "https://twitter.com/calso21"), guest("Calsonia"))
        episode(signal, "s2", 200, host("Calso"))
        val found = people.find("cals")
        assertEquals(listOf("Calso" to 2, "Calsonia" to 1), found.map { it.name to it.episodes })
        assertEquals("Signal", found.first().show)
        assertEquals("https://twitter.com/calso21", found.first().href)
        assertTrue(people.find("ca").isEmpty(), "two letters name half the library")
        assertTrue(people.find("100%").isEmpty(), "a wildcard is a character, not everything")
    }

    @Test
    fun aNameWithoutLatinLettersIsFoundToo() = runBlocking {
        val s = show("Kanal")
        episode(s, "k1", 100, host("Ölçü"))
        assertEquals(listOf("k1"), people.episodes("ÖLÇÜ").map { it.item.guid })
    }
}
