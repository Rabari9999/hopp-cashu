package space.sovereigncast.hopp

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import kotlinx.browser.document

/**
 * Most podcast hosts send no CORS headers, so the browser may not read their
 * feeds, chapters, transcripts or images itself (ADR 0022, 6): every GET goes
 * through the feed proxy at [base] — but the services that allow the browser
 * themselves: the Podcast Index (it needs its key headers, which the proxy
 * never forwards) and Sovereigncast's Listener API (signed per request, NIP-98).
 */
internal class FeedProxyConfig {
    var base: String = ""
}

internal val FeedProxy = createClientPlugin("FeedProxy", ::FeedProxyConfig) {
    val base = pluginConfig.base.trimEnd('/')
    onRequest { request, _ ->
        val url = request.url
        if (base.isEmpty() || request.method != HttpMethod.Get) return@onRequest
        if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return@onRequest
        if (url.host in DIRECT_HOSTS || url.encodedPathSegments.joinToString("/").startsWith("/api/listener/")) return@onRequest
        val target = url.buildString()
        if (target.startsWith(base)) return@onRequest
        url.takeFrom("$base/fetch")
        url.parameters.clear()
        url.parameters.append("url", target)
    }
}

private val DIRECT_HOSTS = setOf("api.podcastindex.org")

private fun absolute(href: String): String = js("new URL(href, document.baseURI).href")

/** The proxy's address, from the page: `<meta name="hopp-feed-proxy" content="…">` — set where it is
 *  served; a relative one is taken from the page's address. */
internal fun feedProxyBase(): String =
    document.querySelector("meta[name=hopp-feed-proxy]")?.getAttribute("content")?.trim()?.takeIf { it.isNotEmpty() }?.let(::absolute).orEmpty()
