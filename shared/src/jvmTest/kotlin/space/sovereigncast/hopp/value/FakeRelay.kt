package space.sovereigncast.hopp.value

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.RelayConnector
import space.sovereigncast.hopp.nostr.RelaySession

/**
 * An in-memory Nostr relay for tests: stores events, answers REQ with stored
 * matches + EOSE and forwards new events to matching subscriptions. Enough of
 * NIP-01 for NIP-47 (kinds, authors, #e, #p, limit).
 */
class FakeRelay : RelayConnector {
    private val json = Json { ignoreUnknownKeys = true }
    private val stored = mutableListOf<Event>()
    private val sessions = mutableListOf<Session>()
    var rejectEvents = false
    val listeners = mutableListOf<(Event) -> Unit>()

    inner class Session : RelaySession {
        val channel = Channel<String>(Channel.UNLIMITED)
        val subs = mutableMapOf<String, JsonObject>()
        override val incoming: Flow<String> = channel.consumeAsFlow()
        override suspend fun send(message: String) = handle(this, message)
        override suspend fun close() { synchronized(this@FakeRelay) { sessions.remove(this) }; channel.close() }
    }

    override suspend fun connect(url: String): RelaySession = Session().also { synchronized(this) { sessions += it } }

    fun publish(event: Event) {
        val targets = synchronized(this) { stored += event; sessions.toList() }
        for (s in targets) for ((id, filter) in s.subs.toMap()) if (matches(filter, event)) {
            s.channel.trySend(eventMessage(id, event))
        }
        listeners.toList().forEach { it(event) }
    }

    private fun handle(session: Session, text: String) {
        val arr = json.parseToJsonElement(text).jsonArray
        when (arr[0].jsonPrimitive.content) {
            "REQ" -> {
                val id = arr[1].jsonPrimitive.content
                val filter = arr[2].jsonObject
                session.subs[id] = filter
                val limit = filter["limit"]?.jsonPrimitive?.int ?: Int.MAX_VALUE
                synchronized(this) { stored.filter { matches(filter, it) }.takeLast(limit) }
                    .forEach { session.channel.trySend(eventMessage(id, it)) }
                session.channel.trySend("""["EOSE","$id"]""")
            }
            "EVENT" -> {
                val event = json.decodeFromJsonElement(Event.serializer(), arr[1])
                val ok = event.isValid() && !rejectEvents
                session.channel.trySend("""["OK","${event.id}",$ok,"${if (ok) "" else "blocked: test"}"]""")
                if (ok) publish(event)
            }
            "CLOSE" -> session.subs.remove(arr[1].jsonPrimitive.content)
        }
    }

    private fun eventMessage(sub: String, e: Event) = buildJsonArray {
        add(JsonPrimitive("EVENT")); add(JsonPrimitive(sub)); add(json.encodeToJsonElement(Event.serializer(), e))
    }.toString()

    private fun matches(f: JsonObject, e: Event): Boolean {
        fun list(k: String) = (f[k] as? JsonArray)?.map { it.jsonPrimitive.content }
        list("kinds")?.let { if (e.kind.toString() !in it) return false }
        list("authors")?.let { if (e.pubkey !in it) return false }
        for (tag in listOf("e", "p")) list("#$tag")?.let { vals ->
            if (e.tags.none { it.size >= 2 && it[0] == tag && it[1] in vals }) return false
        }
        return true
    }
}
