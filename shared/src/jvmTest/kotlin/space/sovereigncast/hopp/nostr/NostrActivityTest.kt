package space.sovereigncast.hopp.nostr

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Hopp did with the relays besides sync, shown only where it went wrong (maintainer 2026-11-10). */
class NostrActivityTest {
    private val relay = FakeRelay()
    private var down = setOf<String>()
    private val publisher = RelayPublisher(RelayConnector { url -> if (url in down) error("refused") else relay.connect(url) })
    private val anything = Json.parseToJsonElement("""{"kinds":[1]}""") as JsonObject
    private val activity = NostrActivity { 1_000L }

    private val mine = listOf("wss://a.test", "wss://b.test", "wss://c.test")
    private val theirs = listOf("wss://x.test", "wss://y.test")

    @Test
    fun aReadMostOfTheListenersRelaysDidNotAnswerIsAProblem() = runBlocking {
        down = setOf("wss://b.test", "wss://c.test", "wss://x.test")
        activity.track(NostrActivity.Area.Contacts, core = mine) { publisher.query(listOf(anything), mine + theirs) }
        val o = activity.last.value.getValue(NostrActivity.Area.Contacts)
        assertEquals(1 to 3, o.answered to o.asked, "the contacts' own relays count beside")
        assertEquals(listOf(true, false), listOf("wss://y.test", "wss://x.test").map { u -> o.others.single { it.url == u }.ok })
        assertTrue(o.problem)
        assertEquals(listOf(o), NostrActivity.problems(activity.last.value))
    }

    @Test
    fun aLaterGoThatWentWellTakesTheLineAway() = runBlocking {
        down = mine.toSet()
        activity.track(NostrActivity.Area.Profile) { publisher.query(listOf(anything), mine) }
        assertTrue(NostrActivity.problems(activity.last.value).isNotEmpty())
        // One relay of three down is no problem: most answered.
        down = setOf("wss://c.test")
        activity.track(NostrActivity.Area.Profile) { publisher.query(listOf(anything), mine) }
        assertFalse(activity.last.value.getValue(NostrActivity.Area.Profile).problem)
        assertTrue(NostrActivity.problems(activity.last.value).isEmpty())
    }

    @Test
    fun aSendThatFailsIsKeptAndStillThrows() = runBlocking {
        down = mine.toSet()
        val event = Event.sign(KeyPair.generate(), 1, "hello", createdAt = 1_790_000_000)
        assertFailsWith<IllegalStateException> {
            activity.track(NostrActivity.Area.Comment, send = true) {
                check(publisher.publish(event, mine).isNotEmpty()) { "not published" }
            }
        }
        val o = activity.last.value.getValue(NostrActivity.Area.Comment)
        assertTrue(o.send && o.problem && o.answered == 0)
    }

    @Test
    fun nothingAskedLeavesNothing() = runBlocking {
        activity.track(NostrActivity.Area.Share, send = true) { }
        assertTrue(activity.last.value.isEmpty())
    }
}
