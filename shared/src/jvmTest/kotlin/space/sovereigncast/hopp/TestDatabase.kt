package space.sovereigncast.hopp

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import space.sovereigncast.hopp.db.HoppDatabase

/**
 * A fresh in-memory database for a test, on a driver that answers only
 * asynchronously, as the browser's does (ADR 0022): a synchronous read left in
 * the code (`executeAsList()` …) fails here, not only in a browser.
 */
fun testDatabase(): HoppDatabase = HoppDatabase(AsyncOnlyDriver(schemaDriver()))

/** An in-memory JDBC driver with the current schema — for tests that change it by hand. */
fun schemaDriver(): JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.synchronous().create(it) }

/** [delegate], every answer handed out as an [QueryResult.AsyncValue]: its `value` throws. */
class AsyncOnlyDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = QueryResult.AsyncValue { delegate.executeQuery(identifier, sql, mapper, parameters, binders).await() }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
        QueryResult.AsyncValue { delegate.execute(identifier, sql, parameters, binders).await() }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue { delegate.newTransaction().await() }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = delegate.addListener(*queryKeys, listener = listener)
    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = delegate.removeListener(*queryKeys, listener = listener)
    override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(*queryKeys)
}
