package space.sovereigncast.hopp

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The browser's database has one connection, in its worker, for all of Hopp's coroutines —
 * and SQLDelight's web-worker driver one transaction for all of them: a second coroutine's
 * transaction became part of the first, its statements ran inside it, and a show's
 * `last_insert_rowid()` was another show's (maintainer 2026-10-28: three shows subscribed,
 * their episodes under the wrong ones). On Android and iOS a transaction holds its connection.
 *
 * Here a transaction holds a lock from its start to its commit or rollback; statements of
 * other coroutines wait for it, those of the transaction's own coroutine (and its children)
 * go on. A statement outside any transaction takes the lock for itself alone.
 */
class SerialDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    private val lock = Mutex()
    private var owner: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun ownsTransaction(): Boolean {
        val owner = owner ?: return false
        var job: Job? = currentCoroutineContext()[Job]
        while (job != null) {
            if (job === owner) return true
            job = job.parent
        }
        return false
    }

    private suspend fun <T> serial(block: suspend () -> T): T =
        if (ownsTransaction()) block() else lock.withLock { block() }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = QueryResult.AsyncValue { serial { delegate.executeQuery(identifier, sql, mapper, parameters, binders).await() } }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
        QueryResult.AsyncValue { serial { delegate.execute(identifier, sql, parameters, binders).await() } }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue {
        // A transaction inside this coroutine's own: SQLDelight nests it.
        if (ownsTransaction()) return@AsyncValue delegate.newTransaction().await()
        lock.lock()
        owner = currentCoroutineContext()[Job]
        try {
            delegate.newTransaction().await().also { t ->
                var released = false
                val release = { if (!released) { released = true; owner = null; lock.unlock() } }
                t.afterCommit(release)
                t.afterRollback(release)
            }
        } catch (e: Throwable) {
            owner = null
            lock.unlock()
            throw e
        }
    }
}
