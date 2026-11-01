package space.sovereigncast.hopp.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import space.sovereigncast.hopp.platform.AppInfo

/** The one HTTP client: Hopp's User-Agent on every request (ADR 0015) — the
 *  default OkHttp agent would be counted as a bot by Sovereigncast. Not in the
 *  browser, which sends its own and would ask first before each request with
 *  another (a CORS preflight). [platform]: what a platform adds (the web's feed proxy). */
fun createHttpClient(app: AppInfo, platform: HttpClientConfig<*>.() -> Unit = {}): HttpClient = HttpClient {
    if (app.platform != "Web") install(UserAgent) { agent = app.userAgent }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
    }
    install(WebSockets)
    followRedirects = true
    platform()
}

/** An answer larger than Hopp reads for its kind (see [Limits]). */
class ResponseTooLarge(val limit: Long) : Exception("answer larger than ${limit / 1024} KiB")

/** An answer read to its end — but never past a limit. */
class LimitedResponse(val status: io.ktor.http.HttpStatusCode, val headers: io.ktor.http.Headers, val bytes: ByteArray) {
    fun text(): String = bytes.decodeToString()
}

/**
 * How much Hopp reads of what others serve (security review 2026-10-10,
 * M-02): a feed, a chapter file or a transcript is whatever its host sends,
 * and without a limit a huge or endless answer was read into memory whole —
 * and chapters stored in the database. Generous for real content (a feed of
 * 300+ episodes is ~2 MB), firm against the rest.
 */
object Limits {
    const val FEED = 32L shl 20
    const val OPML = 16L shl 20
    const val TRANSCRIPT = 16L shl 20
    const val CHAPTERS = 2L shl 20
    const val DIRECTORY = 4L shl 20
    const val API = 1L shl 20
    const val LNURL = 512L shl 10
}

/**
 * Sends the request and reads at most [limit] bytes of the body: refused
 * up front when Content-Length says more, stopped when the stream goes past
 * it (chunked, or compressed — the limit counts what arrives after
 * decompression). A plain `get` would read the whole body before anyone
 * could look at it.
 */
suspend fun HttpClient.limited(limit: Long, block: HttpRequestBuilder.() -> Unit): LimitedResponse =
    HttpStatement(HttpRequestBuilder().apply(block), this).execute { r ->
        r.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { if (it > limit) throw ResponseTooLarge(limit) }
        val bytes = r.bodyAsChannel().readRemaining(limit + 1).readByteArray()
        if (bytes.size > limit) throw ResponseTooLarge(limit)
        LimitedResponse(r.status, r.headers, bytes)
    }

suspend fun HttpClient.getLimited(url: String, limit: Long, block: HttpRequestBuilder.() -> Unit = {}): LimitedResponse =
    limited(limit) { url(url); method = HttpMethod.Get; block() }
