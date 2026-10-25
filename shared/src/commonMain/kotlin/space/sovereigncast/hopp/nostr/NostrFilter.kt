package space.sovereigncast.hopp.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A NIP-01 filter checked here, not only by the relay (security audit
 * 2026-10-22, M-07): a relay may send events the request did not ask for.
 * `ids`, `authors`, `kinds`, `#x` tags, `since` and `until`; `limit` and
 * anything else are the relay's business.
 */
object NostrFilter {
    fun matchesAny(event: Event, filters: List<JsonObject>): Boolean = filters.any { matches(event, it) }

    fun matches(event: Event, filter: JsonObject): Boolean {
        for ((key, value) in filter) {
            val ok = when {
                key == "ids" -> event.id in texts(value)
                key == "authors" -> event.pubkey in texts(value)
                key == "kinds" -> event.kind in (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                key == "since" -> (value as? JsonPrimitive)?.longOrNull?.let { event.created_at >= it } ?: true
                key == "until" -> (value as? JsonPrimitive)?.longOrNull?.let { event.created_at <= it } ?: true
                key.length == 2 && key[0] == '#' -> {
                    val wanted = texts(value).toSet()
                    event.tags.any { it.size >= 2 && it[0] == key.substring(1) && it[1] in wanted }
                }
                else -> true
            }
            if (!ok) return false
        }
        return true
    }

    private fun texts(value: JsonElement): List<String> = (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
