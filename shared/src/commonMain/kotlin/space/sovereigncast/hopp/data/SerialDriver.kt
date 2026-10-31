package space.sovereigncast.hopp.data

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 *
 * The transaction is this driver's own, its BEGIN and END plain statements: the delegate's
 * ended in a step a cancelled coroutine never finished — no END or ROLLBACK sent, the lock
 * never given back, every later statement waiting for good (review 2026-10-31). Begun and
 * ended here, neither is cut off halfway, and the lock goes back however the end goes.
 * In common code so the JVM tests can check it; only the web uses it.
 */
class SerialDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    private val lock = Mutex()
    private var owner: Job? = null
    private var current: Transacter.Transaction? = null

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

    override fun currentTransaction(): Transacter.Transaction? = current

    override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue {
        // A transaction inside this coroutine's own: SQLDelight nests it, nothing goes to SQLite.
        if (ownsTransaction()) return@AsyncValue Serial(current).also { current = it }
        lock.lock()
        try {
            withContext(NonCancellable) { delegate.execute(null, "BEGIN TRANSACTION", 0).await() }
        } catch (e: Throwable) {
            lock.unlock()
            throw e
        }
        owner = currentCoroutineContext()[Job]
        Serial(null).also { current = it }
    }

    private inner class Serial(override val enclosingTransaction: Transacter.Transaction?) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.AsyncValue {
            if (enclosingTransaction != null) {
                current = enclosingTransaction
                return@AsyncValue
            }
            try {
                withContext(NonCancellable) {
                    if (!successful) delegate.execute(null, "ROLLBACK TRANSACTION", 0).await()
                    else try {
                        delegate.execute(null, "END TRANSACTION", 0).await()
                    } catch (e: Exception) {
                        // A commit that failed leaves the transaction open: closed without its changes.
                        runCatching { delegate.execute(null, "ROLLBACK TRANSACTION", 0).await() }
                        throw e
                    }
                }
            } finally {
                current = null
                owner = null
                lock.unlock()
            }
        }
    }
}
