package space.sovereigncast.hopp

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import space.sovereigncast.hopp.db.HoppDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/** The browser's database (SQLite Wasm in a worker, OPFS), as the app opens it. */
class WebDatabaseTest {
    /** Maintainer 2026-10-28: three shows subscribed at once, their episodes under the wrong
     *  ones — every coroutine's statements ran in one shared transaction. */
    @Test
    fun showsSubscribedAtOnceKeepTheirOwnEpisodes() = runTest {
        val db = HoppDatabase(openWebDatabase())
        val shows = listOf("a", "b", "c").map { "https://test.invalid/$it-${kotlin.random.Random.nextLong()}.xml" }
        val ids = coroutineScope {
            shows.map { feed ->
                async {
                    db.transactionWithResult {
                        db.podcastQueries.insert(feed, null, feed, null, null, "{}", null, null, 0, 0, "", null)
                        val id = db.podcastQueries.lastInsertId().awaitAsOne()
                        for (n in 1..5) db.episodeQueries.insertIfNew(id, "$feed#$n", "$feed #$n", n.toLong(), null, null, null, null, null, "{}", 0, 0, 0, null)
                        feed to id
                    }
                }
            }.awaitAll()
        }
        for ((feed, id) in ids) {
            assertEquals(feed, db.podcastQueries.selectById(id).awaitAsOne().feed_url)
            val episodes = db.episodeQueries.selectByPodcast(id).awaitAsList()
            assertEquals(5, episodes.size, "episodes of $feed")
            assertEquals(true, episodes.all { it.guid.startsWith(feed) }, "only $feed's episodes under it")
        }
    }
}
