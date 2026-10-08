package space.sovereigncast.hopp.player

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.QueueRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PlaybackItem
import space.sovereigncast.hopp.platform.PlaybackState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An engine that does what it is told and lets the test play the clock. */
class RecordingEngine : PlaybackEngine {
    val s = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = s
    val loaded = mutableListOf<PlaybackItem>()
    val seeks = mutableListOf<Long>()
    var lastSpeed = 1f
    override fun load(item: PlaybackItem, playWhenReady: Boolean) {
        loaded += item
        s.value = PlaybackState(item.id, playWhenReady, positionMs = item.startPositionMs, durationMs = 600_000)
    }
    override fun play() { s.value = s.value.copy(isPlaying = true) }
    override fun pause() { s.value = s.value.copy(isPlaying = false) }
    override fun seekTo(positionMs: Long) { seeks += positionMs; s.value = s.value.copy(positionMs = positionMs) }
    override fun setSpeed(speed: Float) { lastSpeed = speed; s.value = s.value.copy(speed = speed) }
    override fun release() = Unit
    fun at(ms: Long, playing: Boolean = true) { s.value = s.value.copy(positionMs = ms, isPlaying = playing) }
    fun end() { s.value = s.value.copy(positionMs = s.value.durationMs, isPlaying = false) }
}

/** Package 2 in the player: queue, sleep timer, the show's speed, chapters. */
class PlayerControllerTest {
    private val chapters = """{"version":"1.2.0","chapters":[{"startTime":0,"title":"Intro"},{"startTime":60,"title":"Main"},{"startTime":300,"title":"End"}]}"""
    private fun feed() = buildString {
        append("""<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>Test Show</title>""")
        for (i in 3 downTo 1) {
            val ch = if (i == 3) """<podcast:chapters url="https://pod.test/ch.json" type="application/json+chapters"/>""" else ""
            append("""<item><title>Ep $i</title><guid>ep-$i</guid><pubDate>Mon, 0$i Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/$i.mp3" type="audio/mpeg"/>$ch</item>""")
        }
        append("</channel></rss>")
    }
    private val http = HttpClient(MockEngine { req ->
        when (req.url.encodedPath) {
            "/feed.xml" -> respond(feed())
            "/ch.json" -> respond(chapters)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })
    private val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { 1000 })
    private val episodes = EpisodeRepository(db, http, { 1000 })
    private val queue = QueueRepository(db)
    private val engine = RecordingEngine()
    private var clockMs = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val player = PlayerController(engine, episodes, podcasts, queue, SettingsRepository(db), scope) { clockMs }

    @AfterTest fun stop() = scope.cancel()

    private fun test(block: suspend () -> Unit) { runBlocking { withTimeout(10_000) { block() } } }
    private suspend fun until(what: String, check: suspend () -> Boolean) {
        try { withTimeout(5_000) { while (!check()) delay(10) } } catch (e: Exception) { throw AssertionError("never: $what") }
    }
    /** One engine report, given time to be seen — a StateFlow keeps only the latest. */
    private suspend fun step(block: RecordingEngine.() -> Unit) { engine.block(); delay(30) }
    private suspend fun at(ms: Long, playing: Boolean = true) = step { at(ms, playing) }

    private suspend fun episodes(): List<EpisodeItem> {
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        return episodes.ofPodcast(id).first().sortedBy { it.title }
    }
    private suspend fun start(item: EpisodeItem) {
        player.play(item)
        until("${item.title} loaded") { engine.loaded.lastOrNull()?.id == item.id.toString() && player.current.value?.item?.id == item.id }
        step { play() }
    }

    @Test
    fun theQueueTakesOverWhenAnEpisodeEnds() = test {
        val (e1, e2, _) = episodes()
        start(e1)
        queue.playLast(e2.id)
        at(10_000)
        step { end() }
        until("Ep 2 follows") { engine.loaded.last().id == e2.id.toString() }
        until("Ep 2 shown") { player.current.value?.item?.id == e2.id }
        assertTrue(queue.items.first().isEmpty(), "taken off the queue")
        assertTrue(episodes.item(e1.id)!!.played)
    }

    @Test
    fun endOfEpisodeStopsInsteadOfGoingOn() = test {
        val (e1, e2, _) = episodes()
        start(e1)
        queue.playLast(e2.id)
        player.setSleep(SleepTimer.EndOfEpisode)
        step { end() }
        until("timer off") { player.sleep.value == SleepTimer.Off }
        delay(200)
        assertEquals(listOf(e1.id.toString()), engine.loaded.map { it.id })
        assertEquals(listOf(e2.id), queue.items.first().map { it.id }, "the queue waits")
    }

    @Test
    fun minutesCountOnlyWhilePlaying() = test {
        val (e1, _, _) = episodes()
        start(e1)
        player.setSleep(SleepTimer.Minutes(60_000))
        for (t in 1..40) { clockMs += 1_000; at(t * 1_000L) }
        until("40 s gone") { (player.sleep.value as SleepTimer.Minutes).remainingMs <= 21_000 }
        step { pause() }
        clockMs += 10 * 60_000 // ten minutes paused
        step { play() }
        for (t in 41..55) { clockMs += 1_000; at(t * 1_000L) }
        assertTrue(engine.s.value.isPlaying, "paused time did not count")
        for (t in 56..65) { if (!engine.s.value.isPlaying) break; clockMs += 1_000; at(t * 1_000L) }
        until("paused by the timer") { !engine.s.value.isPlaying && player.sleep.value == SleepTimer.Off }
    }

    @Test
    fun endOfChapterStopsAtTheNextOneButNotAfterAJump() = test {
        val e3 = episodes().last()
        start(e3)
        until("chapters loaded") { player.current.value!!.chapters.stops.size == 3 }
        player.setSleep(SleepTimer.EndOfChapter)
        at(20_000); at(250_000) // a jump into "Main" — not reached by listening
        until("tick seen") { true }
        delay(100)
        assertTrue(engine.s.value.isPlaying)
        for (t in 296..302) { if (!engine.s.value.isPlaying) break; at(t * 1_000L) }
        until("paused at the end of Main") { !engine.s.value.isPlaying }
        assertEquals(300_000L, engine.seeks.last(), "back to the chapter's start")
        assertEquals(SleepTimer.Off, player.sleep.value)
    }

    @Test
    fun nextJumpsByChapterThenToTheQueue() = test {
        val all = episodes()
        val e3 = all.last()
        start(e3)
        until("chapters loaded") { player.current.value!!.chapters.stops.size == 3 }
        queue.playLast(all.first().id)
        at(10_000)
        player.next()
        assertEquals(60_000L, engine.seeks.last())
        at(310_000)
        player.next()
        until("next episode") { engine.loaded.last().id == all.first().id.toString() }
        player.previous()
        assertEquals(0L, engine.seeks.last(), "no chapters: back to the start")
    }

    @Test
    fun eachShowKeepsItsSpeed() = test {
        val (e1, e2, _) = episodes()
        start(e1)
        player.setSpeed(1.5f)
        until("saved") { podcasts.show(e1.podcastId).first()?.playbackSpeed == 1.5f }
        engine.lastSpeed = 1f
        start(e2)
        until("applied") { engine.lastSpeed == 1.5f }
    }

    @Test
    fun anEpisodeStartedElsewhereIsFollowed() = test {
        val (e1, e2, _) = episodes()
        start(e1)
        queue.playLast(e2.id)
        // Android Auto sets the item on the session directly.
        engine.s.value = PlaybackState(e2.id.toString(), isPlaying = true, positionMs = 0, durationMs = 600_000)
        until("followed") { player.current.value?.item?.id == e2.id }
        assertTrue(queue.items.first().isEmpty())
        assertFalse(engine.loaded.any { it.id == e2.id.toString() }, "not loaded a second time")
    }
}
