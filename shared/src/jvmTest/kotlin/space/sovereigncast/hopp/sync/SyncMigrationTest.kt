package space.sovereigncast.hopp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import space.sovereigncast.hopp.db.HoppDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/** Delta audit 2026-10-22, MN-01: the per-entry "not sent yet" of version 13 keeps what the old mark said. */
class SyncMigrationTest {
    @Test
    fun whatWasNotSentBeforeTheUpdateStaysToBeSent() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HoppDatabase.Schema.create(driver)
        // As version 12 had it: no per-entry mark, a newest time taken per document.
        driver.execute(null, "ALTER TABLE sync_entry DROP COLUMN pending", 0)
        listOf(
            "INSERT INTO sync_doc (doc, fetched_at, published_up_to) VALUES ('progress', 1, 2000)",
            "INSERT INTO sync_entry (doc, key, json, updated_at) VALUES ('progress', 'sent', '{}', 1500)",
            "INSERT INTO sync_entry (doc, key, json, updated_at) VALUES ('progress', 'new', '{}', 2500)",
            "INSERT INTO sync_entry (doc, key, json, updated_at) VALUES ('queue', 'never', '{}', 0)",
        ).forEach { driver.execute(null, it, 0) }
        HoppDatabase.Schema.migrate(driver, 12, HoppDatabase.Schema.version)
        val store = SyncStore(HoppDatabase(driver)) { 3000 }
        assertEquals(setOf("new"), store.pendingKeys("progress"))
        assertEquals(setOf("never"), store.pendingKeys("queue"))
    }
}
