package space.sovereigncast.hopp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.comments.CommentStore
import space.sovereigncast.hopp.comments.DbCommentStore
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.feed.Chapter
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.AsyncOnlyDriver
import space.sovereigncast.hopp.schemaDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The library search (maintainer 2026-11-10): the subscribed shows, their episodes, chapters and the comments read. */
class LibrarySearchTest {
    private val driver = schemaDriver()
    private val db = HoppDatabase(AsyncOnlyDriver(driver))
    private val clock = Clock { 1_800_000_000L }
    private var notes = "Die Bitcoin-Bibliothek ist zurück. Grüße an Müller.<br>00:00 Intro<br>05:10 Lesesaal und Bibliothek<br>20:00 Ausblick"
    private val http = HttpClient(MockEngine { req ->
        when (req.url.encodedPath) {
            "/signal.xml" -> respond(feed("Nodesignal", "E300 – Die dreihundertste", "e300", notes))
            else -> respond(feed("Other Show", "Something else", "o1", "Nothing about books here."))
        }
    })
    private val settings = SettingsRepository(db)
    private val search = LibrarySearch(db, settings = settings)
    private val podcasts = PodcastRepository(db, FeedFetcher(http), clock, search = search)

    private fun feed(title: String, episode: String, guid: String, description: String) = """<?xml version="1.0"?>
        <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>$title</title><description>A show about $title</description>
        <item><title>$episode</title><guid>$guid</guid><pubDate>Mon, 02 Nov 2026 06:00:00 +0000</pubDate>
        <description><![CDATA[$description]]></description>${if (title == "Nodesignal") """<podcast:person role="host">Thorsten Bibliothekar</podcast:person>""" else ""}
        <enclosure url="https://cdn.test/$guid.mp3" type="audio/mpeg"/></item></channel></rss>"""

    private fun test(block: suspend () -> Unit) = runBlocking {
        podcasts.subscribe("https://pod.test/signal.xml")
        podcasts.subscribe("https://pod.test/other.xml")
        search.catchUp()
        block()
    }

    @Test
    fun aWordIsFoundInTheNotesWhicheverWayItsUmlautsAreWritten() = test {
        val hits = search.find("bibliothek")
        assertEquals(listOf("E300 – Die dreihundertste"), hits.episodes.map { it.item.title })
        assertEquals("Nodesignal", hits.episodes.single().item.podcastTitle)
        val snippet = hits.episodes.single().snippet!!
        assertEquals("Bibliothek", snippet.text.substring(snippet.marks.first()), "inside the compound, marked")
        for (q in listOf("Müller", "mueller", "GRUESSE müller", "bitcoin-bibliothek")) assertEquals(1L, search.find(q).episodeCount, q)
        assertEquals(0L, search.find("bibliothek nirgends").episodeCount, "every word has to be there")
        assertEquals(0L, search.find("100%").episodeCount, "a % is a character, not a wildcard")
        // A person the episode names counts too.
        assertEquals(1L, search.find("bibliothekar").episodeCount)
    }

    @Test
    fun showsAreFoundByTheirOwnText() = test {
        assertEquals(listOf("Other Show"), search.find("other show").shows.map { it.title })
        assertEquals(1L, search.find("a show about nodesignal").shows.single().episodes)
    }

    @Test
    fun chaptersComeFromTheNotesUntilAFileIsLoaded() = test {
        val chapter = search.find("ausblick").chapters.single()
        assertEquals(1_200_000L, chapter.startMs)
        assertEquals("Ausblick", chapter.title)
        assertEquals(1L, search.find("lesesaal").chapterCount)

        // The chapter file, once played: what the player shows, the notes' list goes.
        indexChapterFile(db, chapter.item.id, listOf(Chapter(0.0, "Anfang"), Chapter(90.0, "Die Bibliothek kehrt zurück"), Chapter(95.0, "Bild", inToc = false)))
        assertEquals(0L, search.find("ausblick").chapterCount)
        assertEquals(listOf(90_000L), search.find("kehrt zurueck").chapters.map { it.startMs })
        assertEquals(0L, search.find("bild").chapterCount, "not a navigation stop")

        // The feed changes its notes: its own chapters stay out while the file's are there.
        notes = "Neue Notizen.<br>00:00 Intro<br>05:00 Ganz anders<br>09:00 Schluss"
        podcasts.refresh(chapter.item.podcastId)
        search.catchUp()
        assertEquals(0L, search.find("ganz anders").chapterCount)
        assertEquals(1L, search.find("neue notizen").episodeCount)
    }

    @Test
    fun changedNotesChangeTheChapters() = test {
        notes = "Neue Notizen.<br>00:00 Intro<br>05:00 Ganz anders<br>09:00 Schluss"
        val id = search.find("ausblick").chapters.single().item.podcastId
        podcasts.refresh(id)
        search.catchUp()
        assertEquals(0L, search.find("ausblick").chapterCount)
        assertEquals(listOf(300_000L), search.find("ganz anders").chapters.map { it.startMs })
    }

    @Test
    fun theCommentsReadAreFoundWithoutTheMutedAndTheShowsOwnNote() = test {
        val (alice, troll, show) = List(3) { KeyPair.generate() }
        val tag = "podcast:item:guid:e300"
        val said = Event.sign(alice, 1111, "Welcome back, Bitcoin Bibliothek!", createdAt = 1_790_000_000)
        val spam = Event.sign(troll, 1111, "Bibliothek spam", createdAt = 1_790_000_100)
        val root = Event.sign(show, 1, "New episode: the Bibliothek", createdAt = 1_789_000_000)
        val like = Event.sign(alice, 7, "+", createdAt = 1_790_000_200)
        DbCommentStore(db).save(tag, CommentStore.Seen(listOf(said, spam, root, like), emptyList(), setOf(troll.publicKeyHex), 1, setOf(root.id)))
        db.commentQueries.upsertAuthor(alice.publicKeyHex, "Marmitc", null, 1)

        val hits = search.find("bibliothek")
        assertEquals(listOf(said.id), hits.comments.map { it.id })
        assertEquals("Marmitc", hits.comments.single().author)
        assertEquals("E300 – Die dreihundertste", hits.comments.single().item.title)
        assertEquals(1L, hits.commentCount)
        assertEquals(3L, hits.total, "an episode, a chapter, a comment, and no show")

        // Muted here (or on another device, by sync) — not only on the public list: not found either.
        settings.update { it.copy(mutedLocally = it.mutedLocally + alice.publicKeyHex) }
        assertEquals(0L to emptyList<String>(), search.find("bibliothek").let { it.commentCount to it.comments.map { c -> c.id } })
    }

    @Test
    fun anUnsubscribedShowIsFoundNoMore() = test {
        val id = search.find("bibliothek").episodes.single().item.podcastId
        DbCommentStore(db).save("podcast:item:guid:e300", CommentStore.Seen(listOf(Event.sign(KeyPair.generate(), 1111, "Bibliothek!", createdAt = 1)), emptyList(), emptySet(), 1))
        podcasts.unsubscribe(id)
        assertEquals(0L, search.find("bibliothek").total)
    }

    @Test
    fun whatWasKeptBeforeTheSearchIsMadeSearchableOnce() = test {
        val episode = search.find("bibliothek").episodes.single().item
        // As a database from before version 25 holds it.
        driver.execute(null, "UPDATE episode SET search_text = NULL, chapter_sig = NULL", 0)
        driver.execute(null, "UPDATE podcast SET search_text = NULL", 0)
        driver.execute(null, "DELETE FROM search_chapter", 0)
        driver.execute(null, "DELETE FROM search_comment", 0)
        driver.execute(null, "DELETE FROM setting", 0)
        val old = Event.sign(KeyPair.generate(), 1, "Die Bibliothek lebt", createdAt = 1)
        db.commentQueries.insertEvent("podcast:item:guid:e300", old.id, kotlinx.serialization.json.Json.encodeToString(Event.serializer(), old))
        db.commentQueries.upsertFetch("podcast:item:guid:e300", 1, "[]", "[]", "[]")
        db.chapterCacheQueries.upsert(episode.id, """{"chapters":[{"startTime":42,"title":"Aus der Datei"}]}""", 1)
        assertEquals(0L, search.find("bibliothek").total)

        search.catchUp()
        val hits = search.find("bibliothek")
        assertEquals(1L to 1L, hits.episodeCount to hits.commentCount)
        assertEquals(listOf(42_000L), search.find("aus der datei").chapters.map { it.startMs })
        assertEquals(0L, search.find("ausblick").chapterCount, "the file's chapters, not the notes'")
        assertEquals(listOf("Other Show"), search.find("other").shows.map { it.title })
    }

    @Test
    fun aSnippetMarksTheWordWhereverItsUmlautsFall() {
        val text = "Viele Grüße an Herrn Müller aus der Bitcoin-Bibliothek, die nach langer Pause zurück ist."
        val s = snippet(text, LibrarySearch.words("mueller bibliothek"), length = 60)!!
        assertEquals(listOf("Müller", "Bibliothek"), s.marks.map { s.text.substring(it) })
        assertTrue(s.text.startsWith("… ") && s.text.endsWith(" …"), s.text)
        assertNull(snippet(text, listOf("nirgends")))
    }
}
