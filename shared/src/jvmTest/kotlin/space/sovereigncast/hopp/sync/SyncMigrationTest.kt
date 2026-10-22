package space.sovereigncast.hopp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import space.sovereigncast.hopp.db.HoppDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/** Delta audits 2026-10-22, MN-01: the per-entry "not sent yet" keeps what the old mark said — on the way
 *  from version 12, and on a device that took version 13 while its migration still lost it. */
class SyncMigrationTest {
    /** The database as version 13 had it: no removal times, no list of what the app may not hold. */
    private fun version13(): JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { driver ->
        HoppDatabase.Schema.create(driver)
        driver.execute(null, "ALTER TABLE sync_entry DROP COLUMN kept_from", 0)
        driver.execute(null, "DROP TABLE sync_unapplied", 0)
    }

    @Test
    fun whatWasNotSentBeforeTheUpdateStaysToBeSent() {
        val driver = version13()
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

    /** Delta audit 2, M02: version 13 already installed with the first migration, the mark lost — put right. */
    @Test
    fun aDeviceAlreadyOnVersion13GetsBackWhatItLost() {
        val driver = version13()
        listOf(
            "INSERT INTO sync_doc (doc, fetched_at, published_up_to) VALUES ('progress', 1, 100)",
            "INSERT INTO sync_entry (doc, key, json, updated_at, pending) VALUES ('progress', 'sent', '{}', 50, 0)",
            "INSERT INTO sync_entry (doc, key, json, updated_at, pending) VALUES ('progress', 'lost', '{}', 200, 0)",
            "INSERT INTO sync_entry (doc, key, json, updated_at, pending) VALUES ('progress', 'kept', '{}', 300, 1)",
        ).forEach { driver.execute(null, it, 0) }
        HoppDatabase.Schema.migrate(driver, 13, HoppDatabase.Schema.version)
        val store = SyncStore(HoppDatabase(driver)) { 3000 }
        assertEquals(setOf("lost", "kept"), store.pendingKeys("progress"))
        assertEquals(emptyMap(), store.unapplied("progress"))
    }
}
