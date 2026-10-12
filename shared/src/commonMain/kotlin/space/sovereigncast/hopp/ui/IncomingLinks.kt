package space.sovereigncast.hopp.ui

import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow

/** Links the platform hands to the app — a `nostr+walletconnect:` URI from a
 *  wallet's "connect" button, which opens the wallet page filled in, and a
 *  show to open (ADR 0002 §4). */
/** A show — and maybe one of its episodes, from a point — that a link opens. */
data class ShowLink(val feed: String, val episode: String? = null, val startSeconds: Long? = null)

object IncomingLinks {
    val walletUri = MutableStateFlow<String?>(null)

    /** A connection string on its way to the wallet page — in memory only, taken
     *  once: never a navigation argument or saved state, which Android keeps in
     *  the activity's saved bundle (security review 2026-10-10, M-01). */
    private var walletDraft: String? = null
    fun handOverWallet(uri: String) { walletDraft = uri }
    fun takeWalletDraft(): String? = walletDraft.also { walletDraft = null }
    /** A show from "Open in Hopp" or a shared link: its preview opens, or its page — and
     *  the episode's, when the link names one — when subscribed. */
    val show = MutableStateFlow<ShowLink?>(null)

    /** The host of Sovereigncast's "Open in Hopp" page — `www`: the bare
     *  domain redirects, and Android does not follow a redirect to verify. */
    const val OPEN_HOST = "www.sovereigncast.space"

    /**
     * A link for others (device feedback 2026-10-12): the show's feed, and the
     * episode's GUID and a start in seconds if given — nothing else, no
     * tracking. Opens Hopp where it is installed; elsewhere the page offers
     * the stores and the feed address for any other app.
     */
    fun shareLink(feedUrl: String, episodeGuid: String? = null, startSeconds: Long? = null): String =
        io.ktor.http.URLBuilder("https://$OPEN_HOST/open").apply {
            parameters.append("feed", feedUrl)
            episodeGuid?.let { parameters.append("episode", it) }
            startSeconds?.takeIf { it > 0 }?.let { parameters.append("t", it.toString()) }
        }.buildString()

    /** The feed a show link points to — [linkFrom] without episode and start. */
    fun feedFrom(link: String): String? = linkFrom(link)?.feed

    /**
     * What a show link points to: `https://www.sovereigncast.space/open?feed=…`
     * (the App Link Sovereigncast's show page and Hopp's own sharing use) or
     * `hopp://subscribe?feed=…` (the scheme that needs no verification), each
     * with an optional `episode` (its GUID) and `t` (seconds). Null for anything
     * else — a missing or empty `feed`, or one that is not http(s), opens Hopp
     * as it is; a bad `t` or an overlong `episode` is left out.
     */
    fun linkFrom(link: String): ShowLink? {
        val url = runCatching { Url(link) }.getOrNull() ?: return null
        val ours = when (url.protocol.name) {
            "https" -> url.host.equals(OPEN_HOST, ignoreCase = true) && (url.encodedPath == "/open" || url.encodedPath.startsWith("/open/"))
            // hopp://subscribe?… — the "host" is the action.
            "hopp" -> url.host.equals("subscribe", ignoreCase = true)
            else -> false
        }
        if (!ours) return null
        val feed = url.parameters["feed"]?.trim().orEmpty()
        val scheme = feed.substringBefore("://", "").lowercase()
        if (!((scheme == "http" || scheme == "https") && feed.length > scheme.length + 3)) return null
        val episode = url.parameters["episode"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 }
        val start = url.parameters["t"]?.trim()?.toLongOrNull()?.takeIf { it in 1..MAX_START_SECONDS }
        return ShowLink(feed, episode, start)
    }

    private const val MAX_START_SECONDS = 48 * 3600L
}
