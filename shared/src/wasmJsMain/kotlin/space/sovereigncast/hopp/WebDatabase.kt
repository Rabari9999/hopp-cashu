package space.sovereigncast.hopp

import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.async.coroutines.awaitMigrate
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker
import space.sovereigncast.hopp.db.HoppDatabase

private fun sqliteWorker(): Worker = js("""new Worker(new URL("./sqlite.worker.js", import.meta.url), { type: "module" })""")
// Named, so the worker knows before it opens anything (sqlite.worker.js).
private fun tabOnlyWorker(): Worker = js("""new Worker(new URL("./sqlite.worker.js", import.meta.url), { type: "module", name: "hopp-tab-only" })""")

/**
 * The browser's database (ADR 0022): SQLite Wasm in a worker, the file in OPFS
 * (resources/sqlite.worker.js). Created or migrated before the app starts —
 * the apps' drivers do that themselves; here the schema's version is
 * SQLite's `user_version`, as Android keeps it. [tabOnly]: in memory only,
 * nothing stored ("This is not my device", ADR 0022, 2).
 */
suspend fun openWebDatabase(tabOnly: Boolean = false): SqlDriver {
    val driver = SerialDriver(WebWorkerDriver(if (tabOnly) tabOnlyWorker() else sqliteWorker()))
    val schema = HoppDatabase.Schema
    val version = driver.executeQuery(null, "PRAGMA user_version", { c -> QueryResult.AsyncValue { if (c.next().await()) c.getLong(0) ?: 0L else 0L } }, 0).await()
    when {
        version == 0L -> schema.awaitCreate(driver)
        version < schema.version -> schema.awaitMigrate(driver, version, schema.version)
        else -> return driver
    }
    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0).await()
    return driver
}

/** Where the database lives: "opfs-sahpool", or "memory" where the browser offers no OPFS (gone on reload). */
suspend fun webStorage(driver: SqlDriver): String =
    driver.executeQuery(null, "PRAGMA hopp_storage", { c -> QueryResult.AsyncValue { if (c.next().await()) c.getString(0) ?: "" else "" } }, 0).await()

/** The database from here on in memory only, its file deleted ("This is not my device"). */
suspend fun keepDatabaseInMemory(driver: SqlDriver) {
    driver.executeQuery(null, "PRAGMA hopp_tab_only", { c -> QueryResult.AsyncValue { c.next().await() } }, 0).await()
}
