package space.sovereigncast.hopp.nostr

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** What one relay did with one request — kept for the sync page (maintainer 2026-10-29: "2 of 3" said nothing of why). */
sealed interface RelayAnswer {
    /** Read to its end (`EOSE`), or the event taken (`OK true`); [ms] from the request on. */
    data class Ok(val ms: Long) : RelayAnswer
    /** `OK false` or `CLOSED`, with the relay's own words — NIP-01 prefixes them ("rate-limited: …"). */
    data class Refused(val reason: String) : RelayAnswer
    /** Connected, then no answer in time. */
    data object Silent : RelayAnswer
    /** No connection at all. */
    data class Unreachable(val error: String?) : RelayAnswer
    /** A read cut off before its end: the connection dropped, or the relay sent more than Hopp takes. */
    data object Partial : RelayAnswer

    /** How bad, for the worst of several answers: an answer that says why beats one that does not. */
    val weight: Int get() = when (this) {
        is Ok -> 0
        Partial -> 1
        Silent -> 2
        is Unreachable -> 3
        is Refused -> 4
    }
}

/** Why a relay refused, from the prefix NIP-01 gives its message. */
enum class Refusal(val prefix: String?) {
    RateLimited("rate-limited"), Blocked("blocked"), AuthRequired("auth-required"), Restricted("restricted"),
    Pow("pow"), Invalid("invalid"), Error("error"), Other(null);

    companion object {
        fun of(reason: String): Refusal {
            val head = reason.substringBefore(':').trim().lowercase()
            return entries.firstOrNull { it.prefix == head } ?: Other
        }
    }
}

/** A read or a send. */
enum class RelayOp { Read, Send }

/** One relay's answer to one request, under [area] — the sync document asked for. */
data class RelayNote(val url: String, val area: String, val op: RelayOp, val answer: RelayAnswer)

/**
 * Collects what each relay answered while it is in the coroutine context ([RelayPublisher] notes
 * every read and send there). [area] names what the requests are for, set before each part of a round.
 */
class RelayLog : AbstractCoroutineContextElement(RelayLog) {
    companion object Key : CoroutineContext.Key<RelayLog>

    var area: String = ""
    // Relays answer at once, from more than one thread.
    private val lock = Mutex()
    private val notes = mutableListOf<RelayNote>()

    suspend fun note(url: String, op: RelayOp, answer: RelayAnswer) {
        lock.withLock { notes += RelayNote(url, area, op, answer) }
    }

    suspend fun notes(): List<RelayNote> = lock.withLock { notes.toList() }
}

internal suspend fun noteAnswer(url: String, op: RelayOp, answer: RelayAnswer) {
    coroutineContext[RelayLog]?.note(url, op, answer)
}

/** [block] with the requests it makes noted under [area]; the area before is back after it. */
suspend inline fun <T> inRelayArea(area: String, block: () -> T): T {
    val log = currentRelayLog() ?: return block()
    val before = log.area
    log.area = area
    try {
        return block()
    } finally {
        log.area = before
    }
}

suspend fun currentRelayLog(): RelayLog? = coroutineContext[RelayLog]
