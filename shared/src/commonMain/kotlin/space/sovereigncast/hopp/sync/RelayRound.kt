package space.sovereigncast.hopp.sync

import space.sovereigncast.hopp.nostr.RelayAnswer
import space.sovereigncast.hopp.nostr.RelayNote
import space.sovereigncast.hopp.nostr.RelayOp

/**
 * One relay in a sync round (maintainer 2026-10-29: the sync page said "2 of 3" and never why):
 * per document — and [SyncDocs.KEYRING] for the sync key — the worst its reads and its sends came to.
 */
data class RelayRound(val url: String, val reads: Map<String, RelayAnswer>, val sends: Map<String, RelayAnswer>) {
    private val all get() = reads.values + sends.values

    /** The worst answer of the round; [RelayAnswer.Ok] when it answered everything. */
    val worst: RelayAnswer get() = all.maxByOrNull { it.weight } ?: RelayAnswer.Ok(0)
    val ok: Boolean get() = worst is RelayAnswer.Ok
    /** Its slowest answer that came. */
    val ms: Long? get() = all.filterIsInstance<RelayAnswer.Ok>().maxOfOrNull { it.ms }

    companion object {
        /** The relays in the order they first answered — per document and way the worst answer. */
        fun of(notes: List<RelayNote>): List<RelayRound> =
            notes.groupBy { it.url }.map { (url, mine) ->
                fun worst(op: RelayOp) = mine.filter { it.op == op }.groupBy { it.area }
                    .mapValues { (_, n) -> n.maxBy { it.answer.weight }.answer }
                RelayRound(url, worst(RelayOp.Read), worst(RelayOp.Send))
            }
    }
}
