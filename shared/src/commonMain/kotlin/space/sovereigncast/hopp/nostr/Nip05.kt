package space.sovereigncast.hopp.nostr

import io.ktor.client.HttpClient
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.sovereigncast.hopp.net.Limits
import space.sovereigncast.hopp.net.getLimited
import kotlin.time.Duration.Companion.seconds

/**
 * A website's own Nostr key (NIP-05, the name `_`): `_@einundzwanzig.space`
 * is the podcast Einundzwanzig. For a show whose feed names no key, the key
 * of its website is the best guess (device feedback 2026-10-17). Asked once
 * per host while the app runs, none after [TIMEOUT]; null where the site
 * has none.
 */
class Nip05(private val http: HttpClient) {
    private val known = mutableMapOf<String, String?>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun siteKey(host: String): String? {
        val h = host.lowercase().removePrefix("www.")
        if (h in known) return known[h]
        val key = withTimeoutOrNull(TIMEOUT) {
            runCatching {
                val r = http.getLimited("https://$h/.well-known/nostr.json?name=_", Limits.LNURL)
                if (!r.status.isSuccess()) return@runCatching null
                json.parseToJsonElement(r.bytes.decodeToString()).jsonObject["names"]?.jsonObject?.get("_")
                    ?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf { HEX.matches(it) }
            }.getOrNull()
        }
        known[h] = key
        return key
    }

    companion object {
        private val TIMEOUT = 2.seconds
        private val HEX = Regex("^[0-9a-f]{64}$")

        /** The host of a web address, if it is one. */
        fun hostOf(url: String?): String? = url?.trim()
            ?.let { Regex("^https?://([^/:?#]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.lowercase()?.takeIf { '.' in it }
    }
}
