package space.sovereigncast.hopp.ui

import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow

/** Links the platform hands to the app — a `nostr+walletconnect:` URI from a
 *  wallet's "connect" button, which opens the wallet page filled in, and a
 *  show to open (ADR 0002 §4). */
object IncomingLinks {
    val walletUri = MutableStateFlow<String?>(null)

    /** A connection string on its way to the wallet page — in memory only, taken
     *  once: never a navigation argument or saved state, which Android keeps in
     *  the activity's saved bundle (security review 2026-10-10, M-01). */
    private var walletDraft: String? = null
    fun handOverWallet(uri: String) { walletDraft = uri }
    fun takeWalletDraft(): String? = walletDraft.also { walletDraft = null }
    /** A feed from "Open in Hopp": its preview opens, or its page when subscribed. */
    val feedUrl = MutableStateFlow<String?>(null)

    /** The host of Sovereigncast's "Open in Hopp" page — `www`: the bare
     *  domain redirects, and Android does not follow a redirect to verify. */
    const val OPEN_HOST = "www.sovereigncast.space"

    /**
     * The feed a show link points to: `https://www.sovereigncast.space/open?feed=…`
     * (the App Link Sovereigncast's show page shares) or `hopp://subscribe?feed=…`
     * (the scheme that needs no verification). Null for anything else — a
     * missing or empty `feed`, or one that is not http(s), opens Hopp as it is.
     */
    fun feedFrom(link: String): String? {
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
        return feed.takeIf { (scheme == "http" || scheme == "https") && feed.length > scheme.length + 3 }
    }
}
