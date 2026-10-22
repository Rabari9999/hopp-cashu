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
 * NIP-01 for NIP-47 and comments (kinds, authors, ids, any #tag, since, until, limit,
 * several filters in one REQ).
 */
class FakeRelay : RelayConnector {
    private val json = Json { ignoreUnknownKeys = true }
    private val stored = mutableListOf<Event>()
    private val sessions = mutableListOf<Session>()
    var rejectEvents = false
    /** A lying relay (security audit 2026-10-18, H-01): passes every event on and still answers "refused". */
    var passOnAndReject = false
    /** Reads fail — the connection drops on every REQ — while publishing still works (security audit 2026-10-20, M-04/M-08). */
    var readsFail = false
    /** Events a read leaves out — a relay that lost them, or holds back (security audit 2026-10-22). */
    var hide: (Event) -> Boolean = { false }
    /** Reads whose filter this says yes to fail — the connection drops (delta audit 2 2026-10-22). */
    var failReads: (JsonObject) -> Boolean = { false }
    /** Events it answers "refused" to and does not keep (delta audit 2026-10-22). */
    var refuse: (Event) -> Boolean = { false }
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
                if (readsFail) { session.channel.close(); return }
                val id = arr[1].jsonPrimitive.content
                val filters = arr.drop(2).map { it.jsonObject }
                if (filters.any(failReads)) { session.channel.close(); return }
                session.subs[id] = filters.first()
                synchronized(this) {
                    filters.flatMap { filter -> stored.filter { matches(filter, it) && !hide(it) }.takeLast(filter["limit"]?.jsonPrimitive?.int ?: Int.MAX_VALUE) }.distinctBy { it.id }
                }.forEach { session.channel.trySend(eventMessage(id, it)) }
                session.channel.trySend("""["EOSE","$id"]""")
            }
            "EVENT" -> {
                val event = json.decodeFromJsonElement(Event.serializer(), arr[1])
                val ok = event.isValid() && !rejectEvents && !passOnAndReject && !refuse(event)
                session.channel.trySend("""["OK","${event.id}",$ok,"${if (ok) "" else "blocked: test"}"]""")
                if (ok || (passOnAndReject && event.isValid())) publish(event)
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
        list("ids")?.let { if (e.id !in it) return false }
        f["since"]?.jsonPrimitive?.content?.toLong()?.let { if (e.created_at < it) return false }
        f["until"]?.jsonPrimitive?.content?.toLong()?.let { if (e.created_at > it) return false }
        for (key in f.keys.filter { it.startsWith("#") }) list(key)?.let { vals ->
            val tag = key.removePrefix("#")
            if (e.tags.none { it.size >= 2 && it[0] == tag && it[1] in vals }) return false
        }
        return true
    }
}
