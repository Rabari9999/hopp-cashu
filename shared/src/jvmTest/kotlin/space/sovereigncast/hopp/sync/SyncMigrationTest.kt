package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import space.sovereigncast.hopp.db.HoppDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/** Delta audits 2026-10-22, MN-01: the per-entry "not sent yet" keeps what the old mark said — on the way
 *  from version 12, and on a device that took version 13 while its migration still lost it. */
class SyncMigrationTest {
    /** The database as version 13 had it: no removal times, no list of what the app may not hold. */
    private fun version13(): JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { driver ->
        HoppDatabase.Schema.synchronous().create(driver)
        version14(driver)
        driver.execute(null, "ALTER TABLE sync_entry DROP COLUMN kept_from", 0)
        driver.execute(null, "DROP TABLE sync_unapplied", 0)
    }

    /** Version 21: no show left out of what is shared with Nostr contacts (ADR 0024). */
    private fun version21(driver: JdbcSqliteDriver) {
        driver.execute(null, "ALTER TABLE podcast DROP COLUMN contacts_hidden", 0)
        driver.execute(null, "DROP TABLE contact_cache", 0)
    }

    /** Version 19 from the current schema: a "heard up to" from the first episode always. */
    private fun version19(driver: JdbcSqliteDriver) {
        version21(driver)
        driver.execute(null, "ALTER TABLE podcast DROP COLUMN played_through_from", 0)
    }

    /** Version 18: no gap kept that every relay showed either. */
    private fun version18(driver: JdbcSqliteDriver) {
        version19(driver)
        driver.execute(null, "ALTER TABLE sync_doc DROP COLUMN stuck_rev", 0)
        driver.execute(null, "ALTER TABLE sync_doc DROP COLUMN stuck_since", 0)
    }

    /** Version 17: no revision kept that failed to go out whole either. */
    private fun version17(driver: JdbcSqliteDriver) {
        version18(driver)
        driver.execute(null, "ALTER TABLE sync_doc DROP COLUMN broken_rev", 0)
    }

    /** Version 16: no "heard up to" per show yet either. */
    private fun version16(driver: JdbcSqliteDriver) {
        version17(driver)
        driver.execute(null, "ALTER TABLE podcast DROP COLUMN played_through", 0)
        driver.execute(null, "ALTER TABLE podcast DROP COLUMN played_through_at", 0)
    }

    /** Version 15: no listening statistics yet either. */
    private fun version15(driver: JdbcSqliteDriver) {
        version16(driver)
        listOf("listen_day", "listen_show", "listen_episode", "listen_count").forEach { driver.execute(null, "DROP TABLE $it", 0) }
    }

    /** Version 14: a show skipped nothing yet either. */
    private fun version14(driver: JdbcSqliteDriver) {
        version15(driver)
        driver.execute(null, "ALTER TABLE podcast DROP COLUMN skip_intro_seconds", 0)
        driver.execute(null, "ALTER TABLE podcast DROP COLUMN skip_outro_seconds", 0)
    }

    /** Review 2026-10-31: a "heard up to" kept before its start was kept still covers from the first episode. */
    @Test
    fun aMarkFromBeforeTheUpdateStartsAtTheFirstEpisode() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.synchronous().create(it); version19(it) }
        driver.execute(null, "INSERT INTO podcast (feed_url, title, meta_json, subscribed_at, played_through, played_through_at) VALUES ('https://pod.test/feed.xml', 'Show', '{}', 1, 500, 600)", 0)
        HoppDatabase.Schema.synchronous().migrate(driver, 19, HoppDatabase.Schema.version)
        val show = HoppDatabase(driver).podcastQueries.selectAll().executeAsOne()
        assertEquals(Triple(500L, null, 600L), Triple(show.played_through, show.played_through_from, show.played_through_at))
    }

    /** Maintainer 2026-10-30: "heard up to" per show; after the update no show has one. */
    @Test
    fun noShowIsHeardUpToAfterTheUpdate() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.synchronous().create(it); version16(it) }
        driver.execute(null, "INSERT INTO podcast (feed_url, title, meta_json, subscribed_at) VALUES ('https://pod.test/feed.xml', 'Show', '{}', 1)", 0)
        HoppDatabase.Schema.synchronous().migrate(driver, 16, HoppDatabase.Schema.version)
        val show = HoppDatabase(driver).podcastQueries.selectAll().executeAsOne()
        assertEquals(null to null, show.played_through to show.played_through_at)
    }

    /** Maintainer 2026-10-26: a show's skipped start and end; after the update every show skips nothing. */
    @Test
    fun showsSkipNothingAfterTheUpdate() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.synchronous().create(it); version14(it) }
        driver.execute(null, "INSERT INTO podcast (feed_url, title, meta_json, subscribed_at) VALUES ('https://pod.test/feed.xml', 'Show', '{}', 1)", 0)
        HoppDatabase.Schema.synchronous().migrate(driver, 14, HoppDatabase.Schema.version)
        val show = HoppDatabase(driver).podcastQueries.selectAll().executeAsOne()
        assertEquals(0L to 0L, show.skip_intro_seconds to show.skip_outro_seconds)
    }

    /** Maintainer 2026-10-26: the statistics' tables come with the update, empty. */
    @Test
    fun theStatisticsStartEmptyAfterTheUpdate() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.synchronous().create(it); version15(it) }
        HoppDatabase.Schema.synchronous().migrate(driver, 15, HoppDatabase.Schema.version)
        val q = HoppDatabase(driver).listeningQueries
        q.ensureDay("abcd", 20_000); q.addDay(60_000, 90_000, "abcd", 20_000)
        assertEquals(60_000L, q.daysOf("abcd").executeAsOne().heard_ms)
    }

    @Test
    fun whatWasNotSentBeforeTheUpdateStaysToBeSent() = runTest {
        val driver = version13()
        // As version 12 had it: no per-entry mark, a newest time taken per document.
        driver.execute(null, "ALTER TABLE sync_entry DROP COLUMN pending", 0)
        listOf(
            "INSERT INTO sync_doc (doc, fetched_at, published_up_to) VALUES ('progress', 1, 2000)",
            "INSERT INTO sync_entry (doc, key, json, updated_at) VALUES ('progress', 'sent', '{}', 1500)",
            "INSERT INTO sync_entry (doc, key, json, updated_at) VALUES ('progress', 'new', '{}', 2500)",
            "INSERT INTO sync_entry (doc, key, json, updated_at) VALUES ('queue', 'never', '{}', 0)",
        ).forEach { driver.execute(null, it, 0) }
        HoppDatabase.Schema.synchronous().migrate(driver, 12, HoppDatabase.Schema.version)
        val store = SyncStore(HoppDatabase(driver)) { 3000 }
        assertEquals(setOf("new"), store.pendingKeys("progress"))
        assertEquals(setOf("never"), store.pendingKeys("queue"))
    }

    /** Delta audit 2, M02: version 13 already installed with the first migration, the mark lost — put right. */
    @Test
    fun aDeviceAlreadyOnVersion13GetsBackWhatItLost() = runTest {
        val driver = version13()
        listOf(
            "INSERT INTO sync_doc (doc, fetched_at, published_up_to) VALUES ('progress', 1, 100)",
            "INSERT INTO sync_entry (doc, key, json, updated_at, pending) VALUES ('progress', 'sent', '{}', 50, 0)",
            "INSERT INTO sync_entry (doc, key, json, updated_at, pending) VALUES ('progress', 'lost', '{}', 200, 0)",
            "INSERT INTO sync_entry (doc, key, json, updated_at, pending) VALUES ('progress', 'kept', '{}', 300, 1)",
        ).forEach { driver.execute(null, it, 0) }
        HoppDatabase.Schema.synchronous().migrate(driver, 13, HoppDatabase.Schema.version)
        val store = SyncStore(HoppDatabase(driver)) { 3000 }
        assertEquals(setOf("lost", "kept"), store.pendingKeys("progress"))
        assertEquals(emptyMap(), store.unapplied("progress"))
    }
}
