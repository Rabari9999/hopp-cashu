package space.sovereigncast.hopp.nostr

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Sends an event to relays and says which of them took it (NIP-01 `OK`). */
class RelayPublisher(private val connector: RelayConnector, private val timeout: Duration = 10.seconds) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The relays that answered `OK true`; one that fails or stays silent is left out. */
    suspend fun publish(event: Event, relays: List<String>): Set<String> = coroutineScope {
        relays.distinct().map { url -> async { url.takeIf { publishTo(event, url) } } }.awaitAll().filterNotNull().toSet()
    }

    private suspend fun publishTo(event: Event, url: String): Boolean = withTimeoutOrNull(timeout) {
        val session = runCatching { connector.connect(url) }.getOrNull() ?: return@withTimeoutOrNull false
        try {
            coroutineScope {
                // Listen before sending, so a fast OK is not missed.
                val ok = async(start = CoroutineStart.UNDISPATCHED) {
                    session.incoming.mapNotNull { text ->
                        runCatching {
                            val arr = json.parseToJsonElement(text).jsonArray
                            if (arr[0].jsonPrimitive.content == "OK" && arr[1].jsonPrimitive.content == event.id) arr[2].jsonPrimitive.content == "true" else null
                        }.getOrNull()
                    }.first()
                }
                session.send(buildJsonArray { add(JsonPrimitive("EVENT")); add(json.encodeToJsonElement(Event.serializer(), event)) }.toString())
                ok.await()
            }
        } catch (e: Exception) {
            false
        } finally {
            runCatching { session.close() }
        }
    } ?: false
}
