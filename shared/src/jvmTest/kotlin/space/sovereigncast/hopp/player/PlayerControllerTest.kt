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
import kotlinx.coroutines.launch
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
    /** The length reported on load; 0 for a stream whose length comes later. */
    var loadDurationMs = 600_000L
    override fun load(item: PlaybackItem, playWhenReady: Boolean) {
        loaded += item
        s.value = PlaybackState(item.id, playWhenReady, positionMs = item.startPositionMs, durationMs = loadDurationMs)
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
    private val settings = SettingsRepository(db)
    private val player = PlayerController(engine, episodes, podcasts, queue, settings, scope) { clockMs }

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

    /** Device feedback 2026-10-22: an episode begun and left for another goes back to the top of
     *  "Up next" and plays on after it; one barely begun does not. */
    @Test
    fun anEpisodeLeftForAnotherWaitsAtTheTopOfUpNext() = test {
        val (e1, e2, e3) = episodes()
        queue.playLast(e3.id)
        start(e1)
        at(120_000)
        start(e2)
        until("Ep 1 back on top") { queue.items.first().map { it.id } == listOf(e1.id, e3.id) }
        assertEquals(120_000L, episodes.item(e1.id)!!.positionMs)
        at(3_000)
        step { end() }
        until("Ep 1 follows, where it was") { engine.loaded.last().let { it.id == e1.id.toString() && it.startPositionMs == 120_000L } }
        // Barely begun: not put back.
        at(2_000)
        start(e3)
        until("Ep 3 playing") { player.current.value?.item?.id == e3.id }
        assertEquals(emptyList(), queue.items.first().map { it.id })
    }

    /** Device feedback 2026-10-22 (a phone restarted): the episode last listened to is back in the
     *  player, paused where it was, and plays from there. */
    @Test
    fun theLastEpisodeIsBackAfterARestart() = test {
        val (e1, _, _) = episodes()
        start(e1)
        at(90_000)
        step { pause() }
        until("position saved") { episodes.item(e1.id)!!.positionMs == 90_000L }
        val fresh = RecordingEngine()
        val again = PlayerController(fresh, episodes, podcasts, queue, settings, scope) { clockMs }
        until("shown again") { again.current.value?.item?.id == e1.id }
        until("where it was") { again.state.value.positionMs == 90_000L }
        assertTrue(fresh.loaded.isEmpty(), "nothing loaded before the listener asks")
        again.togglePlay()
        until("plays from there") { fresh.loaded.lastOrNull()?.let { it.id == e1.id.toString() && it.startPositionMs == 90_000L } == true }
    }

    /** Delta audit 3 2026-10-22, ND-03: after a restart, a newer position from another device arrives while
     *  the episode is only shown — played, it starts there, and the database keeps it. */
    @Test
    fun aPositionFromElsewhereAfterARestartIsWherePlayStarts() = test {
        val (e1, _, _) = episodes()
        start(e1)
        at(90_000)
        step { pause() }
        until("position saved") { episodes.item(e1.id)!!.positionMs == 90_000L }
        val fresh = RecordingEngine()
        val again = PlayerController(fresh, episodes, podcasts, queue, settings, scope) { clockMs }
        until("shown again") { again.current.value?.item?.id == e1.id }
        // As ProgressDocument does: the database first, then the player.
        episodes.applyProgress(e1.id, 180_000, false, clockMs / 1000)
        again.adoptProgress(e1.id, 180_000, false)
        assertEquals(180_000L, again.current.value?.item?.positionMs)
        again.togglePlay()
        until("plays from the new place") { fresh.loaded.lastOrNull()?.let { it.id == e1.id.toString() && it.startPositionMs == 180_000L } == true }
        assertEquals(180_000L, episodes.item(e1.id)!!.positionMs)
    }

    /** ND-03, the other way: the position arrives in the database only (the player not told) — still played from there. */
    @Test
    fun aRestoredEpisodePlaysFromWhatTheDatabaseHasNow() = test {
        val (e1, _, _) = episodes()
        start(e1)
        at(90_000)
        step { pause() }
        until("position saved") { episodes.item(e1.id)!!.positionMs == 90_000L }
        val fresh = RecordingEngine()
        val again = PlayerController(fresh, episodes, podcasts, queue, settings, scope) { clockMs }
        until("shown again") { again.current.value?.item?.id == e1.id }
        episodes.applyProgress(e1.id, 180_000, false, clockMs / 1000)
        again.togglePlay()
        until("plays from the new place") { fresh.loaded.lastOrNull()?.let { it.id == e1.id.toString() && it.startPositionMs == 180_000L } == true }
    }

    /** A clip (podcast:soundbite) plays to its end, then the listener is back where they were. */
    @Test
    fun aClipPlaysToItsEndAndGoesBack() = test {
        val (e1, _, _) = episodes()
        start(e1)
        at(42_000)
        player.playClip(e1, 120_000, 30_000)
        until("at the clip") { engine.state.value.positionMs == 120_000L && engine.state.value.isPlaying }
        at(135_000)
        assertTrue(engine.state.value.isPlaying, "the clip runs")
        at(150_000)
        until("paused and back") { !engine.state.value.isPlaying && engine.state.value.positionMs == 42_000L }
    }

    /** Phase 2: a Sovereigncast clip plays in Hopp's player; at its end the episode
     *  from before is back, paused where it was — nothing saved or played for the clip. */
    @Test
    fun aSovereigncastClipPlaysAndTheEpisodeComesBack() = test {
        val (e1, _, _) = episodes()
        start(e1)
        at(42_000)
        player.playRemoteClip(RemoteClip("https://pm.test/clip.mp3", "Der Halving-Clip", "Nodesignal", null))
        until("the clip loaded") { engine.loaded.last().id == PlayerController.REMOTE_CLIP_ID && engine.state.value.isPlaying }
        assertEquals("https://pm.test/clip.mp3", engine.loaded.last().url)
        assertEquals("Der Halving-Clip", player.remoteClip.value?.title)
        until("Ep 1's position saved") { episodes.item(e1.id)!!.positionMs == 42_000L }
        at(20_000)
        step { end() }
        until("Ep 1 back") { engine.loaded.last().id == e1.id.toString() && player.remoteClip.value == null }
        assertEquals(42_000L, engine.loaded.last().startPositionMs)
        assertFalse(engine.state.value.isPlaying, "back, but paused")
        assertFalse(episodes.item(e1.id)!!.played, "the clip's end is not the episode's")
    }

    /** Closed by hand, or another episode started: no jump back later. */
    @Test
    fun aSovereigncastClipEndsWhenClosedOrReplaced() = test {
        val (e1, e2, _) = episodes()
        start(e1)
        at(42_000)
        player.playRemoteClip(RemoteClip("https://pm.test/clip.mp3", "Clip", "Nodesignal", null))
        until("the clip loaded") { engine.loaded.last().id == PlayerController.REMOTE_CLIP_ID }
        player.closeRemoteClip()
        until("Ep 1 back") { engine.loaded.last().id == e1.id.toString() }
        player.playRemoteClip(RemoteClip("https://pm.test/clip.mp3", "Clip", "Nodesignal", null))
        until("the clip again") { engine.loaded.last().id == PlayerController.REMOTE_CLIP_ID }
        start(e2)
        until("the clip is over") { player.remoteClip.value == null }
        step { end() }
        delay(100)
        assertTrue(engine.loaded.none { it.id == e1.id.toString() && engine.loaded.indexOf(it) > engine.loaded.indexOfLast { l -> l.id == e2.id.toString() } }, "no jump back to Ep 1")
    }

    /** Moving away during a clip ends the clip: nothing pauses later. */
    @Test
    fun aJumpAwayEndsTheClip() = test {
        val (e1, _, _) = episodes()
        start(e1)
        player.playClip(e1, 120_000, 30_000)
        until("at the clip") { engine.state.value.positionMs == 120_000L }
        at(125_000)
        at(600_000) // the listener seeks elsewhere
        at(700_000)
        delay(100)
        assertTrue(engine.state.value.isPlaying)
        assertEquals(700_000L, engine.state.value.positionMs)
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

    /** Maintainer 2026-10-26: a show skips the first and the last seconds of its episodes — from
     *  the beginning and listened up to the end only, never where the listener went themselves. */
    @Test
    fun aShowSkipsItsStartAndItsEnd() = test {
        val (e1, e2, e3) = episodes()
        podcasts.setSkip(e1.podcastId, 30, 20)
        val notices = java.util.concurrent.CopyOnWriteArrayList<Skipped>()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { player.skipped.collect { notices += it } }
        delay(30)
        start(e1)
        assertEquals(30_000L, engine.loaded.last().startPositionMs, "the start skipped")
        until("said, with a way back") { notices.firstOrNull() == Skipped(e1.id, 30, atStart = true) }
        player.undoSkip(notices.first())
        assertEquals(0L, engine.seeks.last())
        // Jumped past the end's 20 s: not skipped — it ends as it ends.
        queue.playLast(e2.id)
        at(582_000)
        at(584_000)
        assertTrue(!episodes.item(e1.id)!!.played && engine.loaded.size == 1, "a jump is not listening")
        step { end() }
        until("Ep 2 follows, its start skipped") { engine.loaded.last().let { it.id == e2.id.toString() && it.startPositionMs == 30_000L } }
        step { play() }
        // Listened into the last 20 s: over, paused, played.
        at(579_000)
        at(581_000)
        until("Ep 2 heard") { episodes.item(e2.id)!!.played }
        assertTrue(!engine.s.value.isPlaying)
        until("said") { notices.any { it == Skipped(e2.id, 20, atStart = false) } }
        // From a chapter or a time mark: where asked.
        player.playAt(e3, 5_000)
        until("Ep 3 at 5 s") { engine.loaded.last().let { it.id == e3.id.toString() && it.startPositionMs == 5_000L } }
        // Skips longer than the episode allows: left whole.
        podcasts.setSkip(e1.podcastId, 300, 250)
        player.play(e1.copy(played = true, durationSeconds = 600))
        until("Ep 1 from 0") { engine.loaded.last().let { it.id == e1.id.toString() && it.startPositionMs == 0L } }
    }

    /** Device check 2026-10-27: what was heard is in the database once listening pauses — not only
     *  every half minute — so an app ended right after keeps it. */
    @Test
    fun whatWasHeardIsWrittenWhenListeningPauses() = test {
        val listening = space.sovereigncast.hopp.data.ListeningRepository(db, space.sovereigncast.hopp.platform.MemorySecretStore(), { 1_790_000_000 }) { kotlinx.datetime.TimeZone.UTC }
        val counting = PlayerController(engine, episodes, podcasts, queue, settings, scope, listening = listening) { clockMs }
        val (e1) = episodes()
        counting.play(e1)
        until("loaded") { engine.loaded.isNotEmpty() }
        var pos = 0L
        repeat(20) { clockMs += 500; pos += 500; at(pos) }
        at(pos, playing = false)
        until("written") { db.listeningQueries.showTotals(0, 999_912).executeAsList().sumOf { it.heard ?: 0 } > 0 }
    }

    /** Review 2026-10-27: an episode whose feed gives no length takes the skip, and goes back to
     *  its start once it turns out too short for it — a trailer does not begin at its end. */
    @Test
    fun anEpisodeOfUnknownLengthTooShortForTheSkipStartsAtItsBeginning() = test {
        val (e1, e2) = episodes()
        podcasts.setSkip(e1.podcastId, 600, 0)
        engine.loadDurationMs = 0
        player.play(e1.copy(played = true, durationSeconds = null))
        until("skipped, as nothing says otherwise") { engine.loaded.lastOrNull()?.startPositionMs == 600_000L }
        engine.s.value = engine.s.value.copy(durationMs = 180_000)
        until("back to its start") { engine.seeks.lastOrNull() == 0L }
        // Long enough once known: where the skip put it.
        engine.seeks.clear()
        player.play(e2.copy(played = true, durationSeconds = null))
        until("Ep 2 skipped") { engine.loaded.last().let { it.id == e2.id.toString() && it.startPositionMs == 600_000L } }
        engine.s.value = engine.s.value.copy(durationMs = 3_600_000)
        delay(100)
        assertTrue(engine.seeks.isEmpty())
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

    /** Device feedback 2026-10-19: one speed for every show, unless a show has its own. */
    @Test
    fun theDefaultSpeedCountsForShowsWithoutTheirOwn() = test {
        val (e1, e2, _) = episodes()
        settings.update { it.copy(defaultSpeed = 1.3f) }
        start(e1)
        until("default") { engine.lastSpeed == 1.3f }
        settings.update { it.copy(defaultSpeed = 1.2f) }
        until("a new default at once") { engine.lastSpeed == 1.2f }
        player.setSpeed(1.8f)
        until("its own") { podcasts.show(e1.podcastId).first()?.playbackSpeed == 1.8f }
        settings.update { it.copy(defaultSpeed = 1.1f) }
        engine.lastSpeed = 1f
        start(e2)
        until("its own stays") { engine.lastSpeed == 1.8f }
        player.setSpeed(1.1f)
        until("the default again: none of its own") { podcasts.show(e1.podcastId).first()?.playbackSpeed == null }
    }

    /** Device feedback 2026-10-19: a stream still loading counts as playing — a second tap stops it. */
    @Test
    fun aTapWhileLoadingStops() = test {
        val (e1, _, _) = episodes()
        start(e1)
        engine.s.value = engine.s.value.copy(isPlaying = false, isBuffering = true)
        player.togglePlay()
        assertFalse(engine.s.value.isPlaying)
        engine.s.value = engine.s.value.copy(isBuffering = false)
        player.togglePlay()
        assertTrue(engine.s.value.isPlaying)
    }

    /** Device feedback 2026-10-19: a tap steps through every speed the long press offers. */
    @Test
    fun aTapStepsThroughEverySpeed() = test {
        val (e1, _, _) = episodes()
        start(e1)
        val seen = (1..PlayerController.SPEED_CHOICES.size).map { player.cycleSpeed(); engine.lastSpeed }
        assertEquals(listOf(1.1f, 1.2f, 1.3f, 1.5f, 1.8f, 2f, 2.5f, 3f, 0.8f, 1f), seen)
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
