package space.sovereigncast.hopp.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.AsyncOnlyDriver
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import space.sovereigncast.hopp.schemaDriver

/** Package 2: the version 1 → 2 migration, the queue, downloads (ADR 0012). */
class DownloadsAndQueueTest {
    private val audio = ByteArray(600_000) { (it % 251).toByte() }
    private var items = 2
    private var failAudio: (String) -> Boolean = { false }
    private var audioStatus = HttpStatusCode.OK
    /** "big": 2 MB announced; "endless": 2 MB without a length; "html": an error page. */
    private var audioMode = "normal"
    private val ranges = mutableListOf<String?>()
    private var now = 1_000_000L

    private fun feed(n: Int) = buildString {
        append("""<?xml version="1.0"?><rss version="2.0"><channel><title>Test Show</title>""")
        for (i in n downTo 1) append("""<item><title>Ep $i</title><guid>ep-$i</guid><pubDate>Mon, 0$i Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/$i.mp3?src=feed" type="audio/mpeg"/></item>""")
        append("</channel></rss>")
    }

    private val http = HttpClient(MockEngine { req ->
        when {
            req.url.encodedPath == "/feed.xml" -> respond(feed(items))
            req.url.host == "cdn.test" -> {
                if (failAudio(req.url.encodedPath)) throw IOException("network gone")
                if (audioStatus != HttpStatusCode.OK) return@MockEngine respondError(audioStatus)
                when (audioMode) {
                    "big" -> return@MockEngine respond(ByteArray(2 shl 20), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, (2 shl 20).toString()))
                    "endless" -> return@MockEngine respond(io.ktor.utils.io.ByteReadChannel(ByteArray(2 shl 20)), HttpStatusCode.OK)
                    "html" -> return@MockEngine respond("<html>Bitte anmelden</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
                }
                val range = req.headers[HttpHeaders.Range]
                ranges += range
                val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                val body = audio.copyOfRange(from, audio.size)
                respond(body, if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentLength, body.size.toString()))
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    })
    private val driver = schemaDriver()
    private val db = HoppDatabase(AsyncOnlyDriver(driver))
    private val dir = Files.createTempDirectory("hopp-dl").toFile()
    private val files = DownloadFiles(dir.absolutePath)
    private val scheduled = mutableListOf<Boolean>()
    private val scheduler = DownloadScheduler { scheduled += it }
    private val clock = Clock { now }
    private val settings = SettingsRepository(db)
    private val podcasts = PodcastRepository(db, FeedFetcher(http), clock, files, scheduler) { settings.current().autoDownloads }
    private val episodes = EpisodeRepository(db, http, clock)
    private val downloads = DownloadRepository(db, http, clock, files, scheduler, settings)
    private val queue = QueueRepository(db)

    private fun test(block: suspend () -> Unit) { runBlocking { block() } }
    private suspend fun subscribe() = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
    private suspend fun byTitle(podcastId: Long, title: String) = episodes.ofPodcast(podcastId).first().single { it.title == title }

    /**
     * Review 2026-10-31: the migrations as a whole, against the schema a new install gets — every table's
     * columns in their order (the generated `SELECT *` reads them by position), types, NOT NULL, defaults
     * and keys, and every index and trigger. A `.sqm` that drifted from its `.sq` failed nowhere before.
     */
    @Test
    fun aDatabaseMigratedFromVersionOneHasANewOnesSchema() {
        val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        V1_SCHEMA.split(";").map(String::trim).filter(String::isNotEmpty).forEach { migrated.execute(null, it, 0) }
        HoppDatabase.Schema.synchronous().migrate(migrated, 1, HoppDatabase.Schema.version)
        val fresh = schemaDriver()
        fun JdbcSqliteDriver.rows(sql: String, columns: Int): List<List<String?>> = executeQuery(null, sql, { c ->
            val out = mutableListOf<List<String?>>()
            while (c.next().value) out += (0 until columns).map { c.getString(it) }
            app.cash.sqldelight.db.QueryResult.Value(out)
        }, 0).value
        fun JdbcSqliteDriver.schema(): Map<String, Any> {
            val tables = rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name", 1).map { it[0]!! }
            val normal = { sql: String? -> sql?.lowercase()?.replace(Regex("\\s+"), " ")?.replace("( ", "(")?.replace(" )", ")")?.trim() }
            return tables.associateWith { t -> rows("PRAGMA table_info('$t')", 6) } +
                rows("SELECT type, name, sql FROM sqlite_master WHERE type IN ('index', 'trigger') AND sql IS NOT NULL ORDER BY name", 3)
                    .associate { (type, name, sql) -> "$type $name" to normal(sql)!! }
        }
        val want = fresh.schema()
        val got = migrated.schema()
        assertEquals(want.keys, got.keys, "tables, indexes and triggers")
        val differ = want.keys.filter { want[it] != got[it] }.map { "$it:\n  new      ${want[it]}\n  migrated ${got[it]}" }
        assertTrue(differ.isEmpty(), "after the migrations:\n" + differ.joinToString("\n"))
    }

    /** Review 2026-10-31: installed before version 5, the payment log had results_json before fees_msat and
     *  unknown_msat — read by position, each from another's column, and a reason set on a payment emptied
     *  its lines. Version 21 puts them in order, every value kept, no id handed out twice. */
    @Test
    fun thePaymentLogOfAnEarlyInstallIsReadRightAfterTheUpdate() = test {
        val driver = schemaDriver()
        // Version 20 had no show left out of sharing with Nostr contacts yet (version 22).
        driver.execute(null, "ALTER TABLE podcast DROP COLUMN contacts_hidden", 0)
        driver.execute(null, "DROP TABLE contact_cache", 0)
        driver.execute(null, "DROP TABLE payment", 0)
        driver.execute(null, """CREATE TABLE payment (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, kind TEXT NOT NULL,
            podcast_id INTEGER, episode_id INTEGER, podcast_title TEXT, episode_title TEXT, amount_msat INTEGER NOT NULL, message TEXT,
            results_json TEXT NOT NULL, fees_msat INTEGER NOT NULL DEFAULT 0, unknown_msat INTEGER NOT NULL DEFAULT 0)""", 0)
        driver.execute(null, "INSERT INTO payment (id, created_at, kind, amount_msat, message, results_json, fees_msat, unknown_msat) VALUES (5, 1, 'boost', 21000, 'Hi', '[{\"name\":\"Host\"}]', 300, 7000)", 0)
        // A later payment, gone since: its id is not handed out again.
        driver.execute(null, "INSERT INTO payment (id, created_at, kind, amount_msat, results_json) VALUES (9, 2, 'stream', 1, '[]')", 0)
        driver.execute(null, "DELETE FROM payment WHERE id = 9", 0)
        HoppDatabase.Schema.synchronous().migrate(driver, 20, HoppDatabase.Schema.version)
        val db = HoppDatabase(driver)
        val row = db.paymentQueries.selectById(5).executeAsOne()
        assertEquals(listOf<Any?>(21000L, "Hi", 300L, 7000L, "[{\"name\":\"Host\"}]"),
            listOf(row.amount_msat, row.message, row.fees_msat, row.unknown_msat, row.results_json))
        db.paymentQueries.insert(3, "boost", null, null, null, null, 1, null, 0, 0, "[]")
        assertEquals(10L, db.paymentQueries.lastInsertId().executeAsOne())
    }

    @Test
    fun aVersionOneDatabaseIsMigrated() = test {
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        V1_SCHEMA.split(";").map(String::trim).filter(String::isNotEmpty).forEach { old.execute(null, it, 0) }
        old.execute(null, "INSERT INTO podcast (feed_url, title, meta_json, subscribed_at) VALUES ('https://pod.test/feed.xml', 'Old', '{}', 1)", 0)
        old.execute(null, "INSERT INTO episode (podcast_id, guid, title, meta_json, first_seen_at, position_ms) VALUES (1, 'g', 'Kept', '{\"guid\":\"g\"}', 1, 4200)", 0)
        HoppDatabase.Schema.synchronous().migrate(old, 1, HoppDatabase.Schema.version)
        val migrated = HoppDatabase(old)
        // Version 11: categories, and the feed asked for in full once more to fill them.
        assertEquals("" to null, migrated.podcastQueries.selectAll().executeAsOne().let { it.categories to it.etag })
        val item = EpisodeRepository(migrated, http, clock).item(1)!!
        assertEquals("Kept" to 4200L, item.title to item.positionMs)
        assertEquals(DownloadState.NONE, item.downloadState)
        // Version 3: the payment log and streaming tables are there too.
        assertEquals(0L, migrated.paymentQueries.spentSince(0).executeAsOne())
        assertEquals(0L, migrated.streamingQueries.owedTotal().executeAsOne())
        // Version 4: favourites.
        EpisodeRepository(migrated, http, clock).setFavorite(1, true)
        assertEquals(listOf(1L), EpisodeRepository(migrated, http, clock).favorites.first().map { it.id })
        SettingsRepository(migrated).update { it.copy(skipBackSeconds = 15) }
        assertEquals(15, SettingsRepository(migrated).current().skipBackSeconds)
        // Version 5: routing fees count against the budget.
        migrated.paymentQueries.insert(1, "boost", 1, 1, "Old", "Kept", 21_000, null, 2_000, 0, "[]")
        assertEquals(23_000L, migrated.paymentQueries.spentSince(0).executeAsOne())
        // Version 6: Nostr comments kept.
        migrated.commentQueries.upsertBoost("c", 21)
        assertEquals(21L, migrated.commentQueries.boosts(listOf("c")).executeAsOne().sats)
        // Version 7: the show's notes found on the relays.
        migrated.commentQueries.upsertFetch("t", 1, "[]", "[]", "[\"r\"]")
        assertEquals("[\"r\"]", migrated.commentQueries.fetch("t").executeAsOne().roots_json)
        // Version 8: a download's failed tries survive a restart.
        assertEquals(0L, migrated.episodeQueries.selectById(1).executeAsOne().download_failures)
        migrated.episodeQueries.setDownloadFailures(2, 1)
        assertEquals(2L, migrated.episodeQueries.selectById(1).executeAsOne().download_failures)
        // Version 9: unknown outcomes count against the budget.
        migrated.paymentQueries.insert(2, "stream", 1, 1, "Old", "Kept", 0, null, 0, 5_000, "[]")
        assertEquals(28_000L, migrated.paymentQueries.spentSince(0).executeAsOne())
        // Version 10: the enclosure's size, read once from the stored feed data.
        assertNull(migrated.episodeQueries.selectById(1).executeAsOne().enclosure_length)
        EpisodeRepository(migrated, http, clock).fillEnclosureLengths()
        assertEquals(0L, migrated.episodeQueries.selectById(1).executeAsOne().enclosure_length, "the old row states no size")
    }

    @Test
    fun theQueueKeepsItsOrder() = test {
        items = 4
        val id = subscribe()
        val (e1, e2, e3, e4) = (1..4).map { byTitle(id, "Ep $it").id }
        queue.playLast(e1); queue.playLast(e2); queue.playNext(e3); queue.playLast(e4)
        assertEquals(listOf(e3, e1, e2, e4), queue.items.first().map { it.id })
        queue.playNext(e4) // already in it: moves to the front
        assertEquals(listOf(e4, e3, e1, e2), queue.items.first().map { it.id })
        queue.reorder(listOf(e1, e2, e3, e4))
        assertEquals(e1, queue.pop())
        assertEquals(listOf(e2, e3, e4), queue.items.first().map { it.id })
        podcasts.unsubscribe(id)
        assertNull(queue.peek())
    }

    @Test
    fun newEpisodesDownloadOnWifiAndRequestedOnesAnywhere() = test {
        val id = subscribe()
        val newest = byTitle(id, "Ep 2")
        assertEquals(DownloadState.QUEUED, newest.downloadState, "the newest one is queued at subscribe")
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 1").downloadState, "the back catalogue is not")
        assertEquals(listOf(false), scheduled)

        assertTrue(downloads.runPending(includeAuto = false))
        assertEquals(DownloadState.QUEUED, byTitle(id, "Ep 2").downloadState, "no automatic download on mobile data")

        downloads.request(byTitle(id, "Ep 1").id)
        assertEquals(listOf(false, true), scheduled)
        assertTrue(downloads.runPending(includeAuto = false))
        val requested = byTitle(id, "Ep 1")
        assertEquals(DownloadState.DONE, requested.downloadState)
        assertContentEquals(audio, File(requested.downloadPath!!).readBytes())
        assertTrue(requested.downloadPath!!.endsWith(".mp3"))
        assertEquals("file://${requested.downloadPath}", requested.playableUrl)

        assertTrue(downloads.runPending(includeAuto = true))
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 2").downloadState)
        assertEquals(2L * audio.size, downloads.usedBytes.first())

        items = 3
        podcasts.refresh(id)
        assertEquals(DownloadState.QUEUED, byTitle(id, "Ep 3").downloadState, "a new episode is queued on refresh")
        podcasts.setAutoDownload(id, false)
        items = 4
        podcasts.refresh(id)
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 4").downloadState, "unless the show is switched off")
    }

    /** Device feedback 2026-10-22: automatic downloads switched off — nothing new is fetched unasked,
     *  what waited for Wi-Fi is dropped, and a run that left Wi-Fi takes no automatic one more. */
    @Test
    fun automaticDownloadsCanBeSwitchedOffAndStopOffWifi() = test {
        items = 3
        val id = subscribe()
        // On: the newest waits for Wi-Fi; a run that leaves Wi-Fi after the asked-for one takes none more.
        assertEquals(DownloadState.QUEUED, byTitle(id, "Ep 3").downloadState)
        downloads.request(byTitle(id, "Ep 1").id)
        // On Wi-Fi when the run starts, on mobile data from the second episode on.
        var asked = 0
        assertTrue(downloads.runPending { asked++ == 0 })
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 1").downloadState)
        assertEquals(DownloadState.QUEUED, byTitle(id, "Ep 3").downloadState, "fetched over mobile data")
        // Off: what waited goes, and a refresh queues nothing.
        settings.update { it.copy(autoDownloads = false) }
        downloads.dropAutomatic()
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 3").downloadState)
        // One already running stops too (device feedback 2026-10-24).
        db.episodeQueries.setDownloadState(DownloadState.DOWNLOADING.toLong(), byTitle(id, "Ep 2").id)
        downloads.dropAutomatic()
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 2").downloadState)
        items = 4
        podcasts.refresh(id)
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 4").downloadState)
    }

    /**
     * Maintainer 2026-11-01: a restore subscribes, which queues each show's newest episode, and sync marks
     * it heard a moment later — it was downloaded all the same. An automatic download of a heard episode is
     * not fetched, and one heard while it runs is dropped; one asked for is fetched anyway.
     */
    @Test
    fun anAutomaticDownloadOfAHeardEpisodeIsNotFetched() = test {
        items = 3
        val id = subscribe()
        assertEquals(DownloadState.QUEUED, byTitle(id, "Ep 3").downloadState)
        episodes.applyProgress(byTitle(id, "Ep 3").id, 0, played = true, atSeconds = now)
        assertEquals(0L, downloads.waitingCount.first())
        assertTrue(downloads.runPending(includeAuto = true))
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 3").downloadState)
        assertEquals(emptyList(), ranges, "nothing fetched")

        // Heard while it runs: gone, no file left.
        items = 4
        podcasts.refresh(id)
        val e4 = byTitle(id, "Ep 4").id
        failAudio = { path -> if (path == "/4.mp3") runBlocking { episodes.applyProgress(e4, 0, played = true, atSeconds = now) }; false }
        assertTrue(downloads.runPending(includeAuto = true))
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 4").downloadState)
        assertEquals(null, byTitle(id, "Ep 4").downloadPath)
        assertEquals(emptyList(), dir.listFiles()!!.map { it.name }, "no file left")
        assertTrue(queue.items.first().isEmpty(), "not in Up next")

        // Asked for: fetched though heard.
        downloads.request(byTitle(id, "Ep 3").id)
        assertTrue(downloads.runPending(includeAuto = true))
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 3").downloadState)
    }

    /** A show subscribed by sync starts its automatic download only once the round is through, with the
     *  other devices' progress in — not while the round still runs. */
    @Test
    fun aShowSubscribedBySyncDownloadsAfterTheRound() = test {
        podcasts.subscribe("https://pod.test/feed.xml", sync = false)
        assertEquals(emptyList(), scheduled, "held during the round")
        podcasts.releaseHeldDownloads()
        assertEquals(listOf(false), scheduled)
        podcasts.releaseHeldDownloads()
        assertEquals(listOf(false), scheduled, "once")
    }

    /** Device feedback 2026-10-19: as in AntennaPod, a download is ready in "Up next". */
    @Test
    fun aFinishedDownloadGoesToTheEndOfTheQueue() = test {
        items = 4
        val id = subscribe()
        val (e1, e2, e3, e4) = (1..4).map { byTitle(id, "Ep $it").id }
        queue.playLast(e3)
        listOf(e3, e1, e2, e4).forEach { downloads.request(it) }
        episodes.setPlayed(e2, true)
        downloads.playing = { e4 }
        assertTrue(downloads.runPending(includeAuto = true))
        assertEquals(listOf(e3, e1), queue.items.first().map { it.id }, "keeps its place; not one heard, nor the one playing")
        queue.remove(e3); queue.remove(e1)
        settings.update { it.copy(downloadsToQueue = false) }
        downloads.remove(e1); downloads.request(e1)
        assertTrue(downloads.runPending(includeAuto = true))
        assertEquals(emptyList(), queue.items.first(), "switched off")
    }

    /** Device feedback 2026-10-25: queued by its download, then marked heard and its download
     *  removed, an episode still started after the one before it — streamed. Heard, it leaves. */
    @Test
    fun aHeardEpisodeLeavesTheQueue() = test {
        items = 4
        val id = subscribe()
        val (e1, e2, e3, e4) = (1..4).map { byTitle(id, "Ep $it").id }
        listOf(e1, e2, e3, e4).forEach { downloads.request(it) }
        assertTrue(downloads.runPending(includeAuto = true))
        val order = queue.items.first().map { it.id }
        assertEquals(setOf(e1, e2, e3, e4), order.toSet())
        val heard = order.first()
        episodes.setPlayed(heard, true)
        downloads.remove(heard)
        assertEquals(order.drop(1), queue.items.first().map { it.id })
        assertEquals(order[1], queue.peek(), "not the one heard")
        order.forEach { queue.remove(it) }
        episodes.setPlayed(heard, false)
        listOf(e4, e3, e2, e1).forEach { queue.playLast(it) }
        // This one and the older ones; then the whole show.
        episodes.setPlayedUpTo(byTitle(id, "Ep 3"))
        assertEquals(listOf(e4), queue.items.first().map { it.id }, "Ep 3 and the older ones")
        episodes.setShowPlayed(id)
        assertEquals(emptyList(), queue.items.first())
        // Unheard again, it does not come back by itself; put in again, it stays until heard.
        episodes.setPlayed(e2, false)
        queue.playLast(e2)
        assertEquals(listOf(e2), queue.items.first().map { it.id })
    }

    /** Device feedback 2026-10-15: one waiting for Wi-Fi goes now on request,
     *  over mobile data too. */
    @Test
    fun aWaitingDownloadGoesNowOverMobileData() = test {
        val id = subscribe()
        assertEquals(1L, downloads.waitingCount.first())
        assertTrue(downloads.runPending(includeAuto = false))
        assertEquals(DownloadState.QUEUED, byTitle(id, "Ep 2").downloadState)
        downloads.startNow(byTitle(id, "Ep 2").id)
        assertEquals(listOf(false, true), scheduled, "on the chain for any network")
        assertEquals(0L, downloads.waitingCount.first())
        assertTrue(downloads.runPending(includeAuto = false))
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 2").downloadState)
        // All at once: from the Downloads page.
        items = 3
        podcasts.refresh(id)
        assertEquals(1L, downloads.waitingCount.first())
        downloads.startNow()
        assertTrue(downloads.runPending(includeAuto = false))
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 3").downloadState)
    }

    @Test
    fun anInterruptedDownloadResumes() = test {
        val id = subscribe()
        val ep = byTitle(id, "Ep 2")
        File(dir, "${ep.id}.part").writeBytes(audio.copyOfRange(0, 100_000))
        assertTrue(downloads.runPending(includeAuto = true))
        assertEquals("bytes=100000-", ranges.last())
        assertContentEquals(audio, File(byTitle(id, "Ep 2").downloadPath!!).readBytes())
    }

    @Test
    fun aLostNetworkKeepsItQueuedAndADeadLinkFails() = test {
        val id = subscribe()
        failAudio = { true }
        assertFalse(downloads.runPending(includeAuto = true), "worth a retry")
        assertEquals(DownloadState.QUEUED, byTitle(id, "Ep 2").downloadState)
        failAudio = { false }
        audioStatus = HttpStatusCode.NotFound
        assertTrue(downloads.runPending(includeAuto = true))
        assertEquals(DownloadState.FAILED, byTitle(id, "Ep 2").downloadState)
        audioStatus = HttpStatusCode.OK
        downloads.request(byTitle(id, "Ep 2").id) // try again by hand
        assertTrue(downloads.runPending(includeAuto = false))
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 2").downloadState)
    }

    /** Code review 2026-10-17, H5: a host that keeps failing — down, its domain
     *  gone — does not hold up the downloads behind it for ever. */
    @Test
    fun aDownloadThatKeepsFailingStopsHoldingUpTheQueue() = test {
        val id = subscribe()
        failAudio = { it == "/1.mp3" }
        downloads.request(byTitle(id, "Ep 1").id) // asked for: first in line
        repeat(DownloadRepository.MAX_TRIES - 1) { assertFalse(downloads.runPending(includeAuto = true)) }
        assertEquals(DownloadState.QUEUED, byTitle(id, "Ep 2").downloadState, "still waiting behind it")
        downloads.runPending(includeAuto = true)
        assertEquals(DownloadState.FAILED, byTitle(id, "Ep 1").downloadState)
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 2").downloadState)
    }

    /** The failure count is in the row (H5): a new process does not grant the
     *  same download another four blocking rounds. Asking for it again resets it. */
    @Test
    fun aRestartKeepsTheFailureCountAndARequestResetsIt() = test {
        val id = subscribe()
        failAudio = { it == "/1.mp3" }
        downloads.request(byTitle(id, "Ep 1").id)
        repeat(2) { assertFalse(downloads.runPending(includeAuto = true)) }
        val again = DownloadRepository(db, http, clock, files, scheduler, settings)
        repeat(DownloadRepository.MAX_TRIES - 3) { assertFalse(again.runPending(includeAuto = true)) }
        again.runPending(includeAuto = true)
        assertEquals(DownloadState.FAILED, byTitle(id, "Ep 1").downloadState)
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 2").downloadState)
        again.request(byTitle(id, "Ep 1").id)
        assertFalse(again.runPending(includeAuto = true), "the request started the count over")
        assertEquals(DownloadState.QUEUED, byTitle(id, "Ep 1").downloadState)
        failAudio = { false }
        assertTrue(again.runPending(includeAuto = true))
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 1").downloadState)
    }

    /** Security review 2026-10-10, M-03: a download stops at the storage limit —
     *  announced or not — and a web page is no episode; nothing is left behind. */
    @Test
    fun aDownloadTooLargeOrNotAudioIsRefused() = test {
        val id = subscribe()
        settings.update { it.copy(storageLimitMb = 1) }
        for (mode in listOf("big", "endless", "html")) {
            audioMode = mode
            downloads.request(byTitle(id, "Ep 2").id)
            assertTrue(downloads.runPending(includeAuto = true), mode)
            assertEquals(DownloadState.FAILED, byTitle(id, "Ep 2").downloadState, mode)
            assertFalse(files.exists(files.partial(byTitle(id, "Ep 2").id).toString()), "$mode left its .part")
        }
        audioMode = "normal"
        downloads.request(byTitle(id, "Ep 2").id)
        assertTrue(downloads.runPending(includeAuto = true))
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 2").downloadState)
    }

    @Test
    fun playedFilesGoAfterADayAndTheLimitTakesTheOldestUnplayed() = test {
        items = 3
        val id = subscribe()
        (1..3).forEach { downloads.request(byTitle(id, "Ep $it").id) }
        downloads.runPending(includeAuto = true)
        assertEquals(3, downloads.downloads.first().count { it.downloadState == DownloadState.DONE })

        episodes.setPlayed(byTitle(id, "Ep 3").id, true)
        now += 23 * 3600; downloads.cleanup()
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 3").downloadState, "kept within the day")
        now += 2 * 3600; downloads.cleanup()
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 3").downloadState)

        // Two files of 600 kB against a limit of 1 MB: the older unplayed one goes.
        settings.update { it.copy(storageLimitMb = 0) }
        downloads.cleanup()
        assertEquals(2, downloads.downloads.first().count { it.downloadState == DownloadState.DONE }, "0 means no limit")
        settings.update { it.copy(storageLimitMb = 1) }
        downloads.cleanup()
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 1").downloadState)
        assertEquals(DownloadState.DONE, byTitle(id, "Ep 2").downloadState)

        // A file deleted behind Hopp's back is no longer listed.
        File(byTitle(id, "Ep 2").downloadPath!!).delete()
        downloads.cleanup()
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 2").downloadState)
    }

    @Test
    fun removingAndUnsubscribingDeleteTheFiles() = test {
        items = 2
        val id = subscribe()
        downloads.request(byTitle(id, "Ep 1").id)
        downloads.runPending(includeAuto = true)
        val path = byTitle(id, "Ep 1").downloadPath!!
        downloads.remove(byTitle(id, "Ep 1").id)
        assertFalse(File(path).exists())
        assertEquals(DownloadState.NONE, byTitle(id, "Ep 1").downloadState)
        val other = byTitle(id, "Ep 2").downloadPath!!
        podcasts.unsubscribe(id)
        assertFalse(File(other).exists())
    }
}

/** The schema as package 1 shipped it (version 1). */
private val V1_SCHEMA = """
CREATE TABLE podcast (
    id INTEGER PRIMARY KEY AUTOINCREMENT, feed_url TEXT NOT NULL UNIQUE, podcast_guid TEXT, title TEXT NOT NULL,
    author TEXT, image_url TEXT, meta_json TEXT NOT NULL, etag TEXT, last_modified TEXT, last_checked_at INTEGER,
    last_error TEXT, sc_base_url TEXT, sc_verified_at INTEGER, sc_capabilities_json TEXT, subscribed_at INTEGER NOT NULL,
    playback_speed REAL, auto_download INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX podcast_guid_idx ON podcast(podcast_guid);
CREATE TABLE episode (
    id INTEGER PRIMARY KEY AUTOINCREMENT, podcast_id INTEGER NOT NULL, guid TEXT NOT NULL, title TEXT,
    published_at INTEGER, duration_seconds INTEGER, image_url TEXT, enclosure_url TEXT, chapters_url TEXT,
    meta_json TEXT NOT NULL, position_ms INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0,
    last_played_at INTEGER, in_inbox INTEGER NOT NULL DEFAULT 0, first_seen_at INTEGER NOT NULL,
    download_state INTEGER NOT NULL DEFAULT 0, download_path TEXT, UNIQUE (podcast_id, guid)
);
CREATE INDEX episode_podcast_idx ON episode(podcast_id, published_at);
CREATE INDEX episode_inbox_idx ON episode(in_inbox, published_at);
CREATE TABLE queue (episode_id INTEGER PRIMARY KEY NOT NULL, sort_key INTEGER NOT NULL);
CREATE TABLE chapter_cache (episode_id INTEGER PRIMARY KEY NOT NULL, json TEXT NOT NULL, fetched_at INTEGER NOT NULL)
"""
