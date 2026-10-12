package space.sovereigncast.hopp.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Security review 2026-10-10, M-02: what others serve is read up to a limit, never past it. */
class LimitsTest {
    private val size = 1_000L
    private val http = HttpClient(MockEngine { req ->
        val n = req.url.parameters["n"]!!.toInt()
        when (req.url.encodedPath) {
            // Announced: Content-Length.
            "/sized" -> respond(ByteArray(n) { 'a'.code.toByte() }, HttpStatusCode.OK, headersOf("Content-Length", n.toString()))
            // Not announced: a stream that only shows its size by going on.
            else -> respond(ByteReadChannel(ByteArray(n) { 'b'.code.toByte() }), HttpStatusCode.OK)
        }
    })

    @Test fun exactlyTheLimitIsRead() = runBlocking {
        assertEquals(size.toInt(), http.getLimited("https://x.test/sized?n=$size", size).bytes.size)
        assertEquals(size.toInt(), http.getLimited("https://x.test/stream?n=$size", size).bytes.size)
    }

    @Test fun oneByteMoreIsRefusedAnnouncedOrNot() = runBlocking {
        assertFailsWith<ResponseTooLarge> { http.getLimited("https://x.test/sized?n=${size + 1}", size) }
        assertFailsWith<ResponseTooLarge> { http.getLimited("https://x.test/stream?n=${size + 1}", size) }
        Unit
    }

    @Test fun aFeedTooLargeIsNotRead() = runBlocking {
        val big = HttpClient(MockEngine { respond(ByteReadChannel(ByteArray((Limits.FEED + 1).toInt())), HttpStatusCode.OK) })
        assertFailsWith<ResponseTooLarge> { FeedFetcher(big).fetch("https://x.test/feed.xml") }
        Unit
    }
}
