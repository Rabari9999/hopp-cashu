package space.sovereigncast.hopp.nostr

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Reading an author's newest event: found, found nowhere, or not known (security audit 2026-10-20, M-04/M-08). */
class RelayPublisherTest {
    private val relay = FakeRelay()
    private val keys = KeyPair.generate()
    private val list = Event.sign(keys, 10000, "", listOf(listOf("p", "ab".repeat(32))), createdAt = 1)

    /** One relay reachable and empty, one that cannot be reached: the answer is incomplete. */
    private val oneDown = RelayConnector { url -> if (url == "wss://down.test") throw java.io.IOException("down") else relay.connect(url) }

    @Test
    fun noneFoundIsCompleteOnlyWhenEveryRelayAnswered() = runBlocking {
        val publisher = RelayPublisher(oneDown, timeout = 2.seconds)
        val all = publisher.latestChecked(10000, keys.publicKeyHex, listOf("wss://a.test", "wss://b.test"))
        assertNull(all.event)
        assertTrue(all.complete, "both answered: none, for sure")
        val some = publisher.latestChecked(10000, keys.publicKeyHex, listOf("wss://a.test", "wss://down.test"))
        assertNull(some.event)
        assertFalse(some.complete, "one did not answer: not known")
        assertNull(publisher.latest(10000, keys.publicKeyHex, listOf("wss://a.test", "wss://down.test")), "latest() says nothing of it")
    }

    @Test
    fun aListFoundOnOneRelayIsFoundWhateverTheOthers() = runBlocking {
        relay.publish(list)
        val found = RelayPublisher(oneDown, timeout = 2.seconds).latestChecked(10000, keys.publicKeyHex, listOf("wss://a.test", "wss://down.test"))
        assertEquals(list, found.event)
        assertFalse(found.complete)
    }

    @Test
    fun readsThatDropAreNotAnswers() = runBlocking {
        relay.publish(list)
        relay.readsFail = true
        val read = RelayPublisher(relay, timeout = 2.seconds).latestChecked(10000, keys.publicKeyHex, listOf("wss://a.test"), 200.milliseconds)
        assertNull(read.event)
        assertFalse(read.complete)
    }
}
