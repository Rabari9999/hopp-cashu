package space.sovereigncast.hopp.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom

sealed interface FetchResult {
    data object NotModified : FetchResult
    data class Fetched(
        val bytes: ByteArray,
        val etag: String?,
        val lastModified: String?,
        /** Set when every redirect on the way was permanent (301/308): the
         *  feed has moved for good and the subscription should follow. */
        val movedTo: String?,
    ) : FetchResult
}

class FeedFetchException(message: String) : Exception(message)

/**
 * Fetches a feed with conditional requests (ADR 0013): an unchanged feed
 * costs a 304 and is not parsed. Redirects are followed by hand, so a
 * permanent move can be told apart from a temporary one.
 */
class FeedFetcher(http: HttpClient) {
    private val client = http.config { followRedirects = false }

    suspend fun fetch(url: String, etag: String? = null, lastModified: String? = null): FetchResult {
        var current = url
        var permanent = true
        repeat(MAX_REDIRECTS + 1) {
            val response = client.getLimited(current, Limits.FEED) {
                if (current == url) {
                    etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                    lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
                }
                header(HttpHeaders.Accept, "application/rss+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5")
            }
            when (response.status.value) {
                304 -> return FetchResult.NotModified
                in 200..299 -> return FetchResult.Fetched(
                    bytes = response.bytes,
                    etag = response.headers[HttpHeaders.ETag],
                    lastModified = response.headers[HttpHeaders.LastModified],
                    movedTo = current.takeIf { it != url && permanent },
                )
                301, 302, 303, 307, 308 -> {
                    val location = response.headers[HttpHeaders.Location] ?: throw FeedFetchException("redirect without Location")
                    if (response.status != HttpStatusCode.MovedPermanently && response.status != HttpStatusCode.PermanentRedirect) permanent = false
                    current = URLBuilder().takeFrom(current).takeFrom(location).buildString()
                }
                else -> throw FeedFetchException("HTTP ${response.status.value}")
            }
        }
        throw FeedFetchException("too many redirects")
    }

    companion object {
        const val MAX_REDIRECTS = 5

        /** What a listener pastes → a fetchable URL: trims, maps feed:// and
         *  podcast:// to https, adds https:// when the scheme is missing. */
        fun normalize(input: String): String? {
            var s = input.trim()
            if (s.isEmpty() || s.any { it.isWhitespace() }) return null
            for (prefix in listOf("feed://", "podcast://", "pcast://", "itpc://")) {
                if (s.startsWith(prefix, ignoreCase = true)) s = "https://" + s.substring(prefix.length)
            }
            if (s.startsWith("feed:", ignoreCase = true)) s = s.substring(5)
            if (!s.contains("://")) s = "https://$s"
            val scheme = s.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = s.substringAfter("://").substringBefore('/').substringBefore('?')
            if (host.isEmpty() || '.' !in host && host != "localhost") return null
            return s
        }
    }
}
