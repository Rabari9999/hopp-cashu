package space.sovereigncast.hopp.nostr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.sync.RelayRound

/**
 * What Hopp did with the relays besides sync — the profile, the Nostr contacts, the shared list, a
 * comment — the last of each, with what every relay answered (maintainer 2026-11-10). Shown only
 * where it went wrong: quiet while things work, a line and its relays where they did not. Kept
 * until Hopp ends, never stored: no record of which relays were asked when.
 */
class NostrActivity(private val nowMs: () -> Long) {
    enum class Area { Profile, Contacts, Share, Comment }

    /**
     * One [area]'s last go, [send] or read, at [atMs]: each relay's worst answer. [core]: the relays
     * that decide whether it went well — the listener's and Hopp's; the contacts' own relays, many of
     * them long gone, are only counted beside them. Null: all of them.
     */
    data class Outcome(val area: Area, val atMs: Long, val send: Boolean, val relays: List<RelayRound>, val core: Set<String>? = null) {
        val counted: List<RelayRound> get() = core?.let { c -> relays.filter { it.url in c } } ?: relays
        val others: List<RelayRound> get() = core?.let { c -> relays.filter { it.url !in c } }.orEmpty()
        val answered: Int get() = counted.count { it.ok }
        val asked: Int get() = counted.size
        /** Fewer than half answered: what came may be missing something, or what went out reached too few. */
        val problem: Boolean get() = asked > 0 && answered * 2 < asked
    }

    private val _last = MutableStateFlow<Map<Area, Outcome>>(emptyMap())
    val last: StateFlow<Map<Area, Outcome>> = _last.asStateFlow()

    /** [block] with every relay answer it gets noted, kept as [area]'s last go — also when it fails. */
    suspend fun <T> track(area: Area, send: Boolean = false, core: Collection<String>? = null, block: suspend () -> T): T {
        val log = RelayLog()
        try {
            return withContext(log) { block() }
        } finally {
            val rounds = RelayRound.of(log.notes())
            if (rounds.isNotEmpty()) _last.update { it + (area to Outcome(area, nowMs(), send, rounds, core?.toSet())) }
        }
    }

    companion object {
        /** What went wrong, newest first. */
        fun problems(last: Map<Area, Outcome>): List<Outcome> = last.values.filter { it.problem }.sortedByDescending { it.atMs }
    }
}
