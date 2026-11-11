package space.sovereigncast.hopp.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.schemaDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The browser's database driver ([SerialDriver]) on a JDBC SQLite that answers a moment later, as the worker does. */
class SerialDriverTest {
    /** Every statement a moment later — a cancellation can land in any of them; [onEnd] when a transaction ends. */
    private class WorkerLike(private val delegate: SqlDriver, val onEnd: CompletableDeferred<Unit> = CompletableDeferred()) : SqlDriver by delegate {
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = QueryResult.AsyncValue { delay(5); delegate.executeQuery(identifier, sql, mapper, parameters, binders).await() }

        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
            QueryResult.AsyncValue {
                if (sql == "END TRANSACTION") { onEnd.complete(Unit); delay(200) } else delay(5)
                delegate.execute(identifier, sql, parameters, binders).await()
            }
    }

    /** A statement left waiting fails the test instead of holding the build. */
    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20.seconds, block) }

    private suspend fun HoppDatabase.addShow(feed: String) =
        podcastQueries.insert(feed, null, feed, null, null, "{}", null, null, 0, 0, "", null)

    private suspend fun HoppDatabase.feeds() = podcastQueries.selectAll().awaitAsList().map { it.feed_url }.toSet()

    /** Maintainer 2026-10-28: three shows subscribed at once, their episodes under the wrong ones. */
    @Test
    fun showsSubscribedAtOnceKeepTheirOwnEpisodes() = bounded {
        val db = HoppDatabase(SerialDriver(WorkerLike(schemaDriver())))
        val ids = coroutineScope {
            listOf("a", "b", "c").map { "https://pod.test/$it.xml" }.map { feed ->
                async {
                    db.transactionWithResult {
                        db.addShow(feed)
                        val id = db.podcastQueries.lastInsertId().awaitAsOne()
                        for (n in 1..5) db.episodeQueries.insertIfNew(id, "$feed#$n", "$feed #$n", n.toLong(), null, null, null, null, null, "{}", 0, 0, 0, null)
                        feed to id
                    }
                }
            }.awaitAll()
        }
        for ((feed, id) in ids) {
            assertEquals(feed, db.podcastQueries.selectById(id).awaitAsOne().feed_url)
            assertEquals(List(5) { true }, db.episodeQueries.selectByPodcast(id).awaitAsList().map { it.guid.startsWith(feed) })
        }
    }

    /** Review 2026-10-31: a transaction whose coroutine was cancelled held the lock for good — every later statement waited. */
    @Test
    fun aTransactionCutOffLeavesNothingBehindAndTheDatabaseFree() = bounded {
        val db = HoppDatabase(SerialDriver(WorkerLike(schemaDriver())))
        val inside = CompletableDeferred<Unit>()
        val cut = launch {
            db.transaction {
                db.addShow("https://pod.test/a.xml")
                inside.complete(Unit)
                awaitCancellation()
            }
        }
        inside.await()
        cut.cancelAndJoin()
        withTimeout(5.seconds) { db.transaction { db.addShow("https://pod.test/b.xml") } }
        assertEquals(setOf("https://pod.test/b.xml"), db.feeds())
    }

    /** Cancelled while the commit is on its way: it still arrives, and the lock comes back. */
    @Test
    fun aTransactionCancelledWhileItEndsIsCommittedWhole() = bounded {
        val worker = WorkerLike(schemaDriver())
        val db = HoppDatabase(SerialDriver(worker))
        val ending = launch { db.transaction { db.addShow("https://pod.test/a.xml") } }
        worker.onEnd.await()
        ending.cancelAndJoin()
        withTimeout(5.seconds) { db.transaction { db.addShow("https://pod.test/b.xml") } }
        assertEquals(setOf("https://pod.test/a.xml", "https://pod.test/b.xml"), db.feeds())
    }

    /** A transaction inside one of the same coroutine nests: one commit, and a failure inside takes both back. */
    @Test
    fun aNestedTransactionIsPartOfItsOuterOne() = bounded {
        val db = HoppDatabase(SerialDriver(WorkerLike(schemaDriver())))
        db.transaction {
            db.addShow("https://pod.test/a.xml")
            db.transaction { db.addShow("https://pod.test/b.xml") }
        }
        runCatching {
            db.transaction {
                db.addShow("https://pod.test/c.xml")
                db.transaction { error("inside") }
            }
        }
        withTimeout(5.seconds) { db.addShow("https://pod.test/d.xml") }
        assertEquals(setOf("https://pod.test/a.xml", "https://pod.test/b.xml", "https://pod.test/d.xml"), db.feeds())
    }
}
