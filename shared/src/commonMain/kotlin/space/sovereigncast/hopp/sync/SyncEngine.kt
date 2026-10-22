package space.sovereigncast.hopp.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.SignerNeedsListener
import space.sovereigncast.hopp.nostr.SignerRejected
import kotlin.time.Duration.Companion.days

/** A document sync keeps in step: subscriptions, progress, "Up next", settings. */
interface SyncedDocument {
    /** Its name in the `d` tag: `hopp/<name>`. */
    val name: String

    /** Entries from another device, newer than this one's: make them so here. The keys
     *  done; one that cannot be yet (a feed out of reach) is tried again next time. */
    suspend fun apply(entries: Map<String, SyncEntry>): Set<String>

    /** What this device has, as entries with the best time it knows — merged once,
     *  when sync is turned on. An entry of time 0 has none: it joins only where the
     *  relays have nothing (settings, the queue's order). */
    suspend fun snapshot(): Map<String, SyncEntry>

    /** An entry no device needs any more (progress older than 90 days): it is dropped
     *  everywhere alike, not marked removed. */
    fun expired(key: String, entry: SyncEntry, nowMs: Long): Boolean = false
}

sealed interface SyncOutcome {
    /** [applied] entries came from elsewhere; [published]: this device's went out; [pending]:
     *  entries from elsewhere not applicable yet (a feed out of reach) — tried again next time. */
    data class Done(val applied: Int, val published: Boolean, val pending: Int = 0) : SyncOutcome
    /** The relays gave the document only in part: [applied] of it taken, nothing sent (audit 2026-10-22, H-02). */
    data class Incomplete(val applied: Int) : SyncOutcome
    data object NoIdentity : SyncOutcome
    /** The signer app would have to ask the listener — sync waits for the app to be open. */
    data object WaitsForSigner : SyncOutcome
    /** A newer Hopp wrote this document: left as it is until this one is updated. */
    data object NeedsUpdate : SyncOutcome
    data class Failed(val error: Throwable) : SyncOutcome
}

/**
 * Keeps documents in step with the listener's relays (ADR 0021, 5). Every
 * sync reads first and merges per entry before anything is sent: a document
 * on the relays is replaced whole, so sending without reading would drop what
 * another device added since. The documents are signed and sealed with the
 * sync key ([SyncKeys]), not the listener's own.
 */
class SyncEngine(
    private val store: SyncStore,
    private val relays: SyncRelays,
    private val identity: IdentityRepository,
    private val keys: SyncKeys,
    private val nowMs: () -> Long,
) {
    private val lock = Mutex()

    /** One round for [doc]. What this device had when sync was turned on without a time (settings, the
     *  queue's order) waits in the store as seeds ([SyncStore.seed]), taken only where the relays have nothing. */
    suspend fun sync(doc: SyncedDocument, interactive: Boolean = false): SyncOutcome = lock.withLock {
        if (identity.identity.value == null) return SyncOutcome.NoIdentity
        try {
            // A first keyring only with the listener there — never from a round in the background (audit 2026-10-22, H-01).
            val signer = keys.signer(interactive, create = interactive) ?: return SyncOutcome.WaitsForSigner
            val urls = identity.writeRelays()
            val now = nowMs()
            val cutoff = now - REMOVALS_KEPT.inWholeMilliseconds
            store.pruneRemoved(doc.name, cutoff)
            var mine = store.entries(doc.name)
            mine.filter { (k, e) -> doc.expired(k, e, now) }.keys.takeIf { it.isNotEmpty() }?.let { store.remove(doc.name, it); mine = mine - it }
            val fetched = relays.fetch(signer, doc.name, urls)
            val (read, notBefore) = when (fetched) {
                is Fetched.Complete -> fetched.entries to fetched.newestAt
                is Fetched.Incomplete -> fetched.entries to fetched.newestAt
                Fetched.Absent -> emptyMap<String, SyncEntry>() to 0L
                is Fetched.Unknown -> return SyncOutcome.Failed(SyncNotReached(fetched.answered, fetched.asked))
            }
            val theirs = read.filter { (k, e) -> !(e.del && e.t < cutoff) && !doc.expired(k, e, now) }
            // An entry dated more than a day ahead is a clock gone wrong: not taken here, but carried
            // along in what goes out — never dropped from the relays by this device (delta audit 2026-10-22, MN-02).
            val ahead = theirs.filterValues { it.t > now + MAX_AHEAD.inWholeMilliseconds }.keys
            // A seed gives way to any value the relays hold — on a second device its defaults never
            // win over the first one's settings (HN-01).
            val beaten = store.seeds(doc.name).filterTo(mutableSetOf()) { it in theirs }
            if (beaten.isNotEmpty()) { store.dropSeeds(doc.name, beaten); mine = mine - beaten }
            mine = mine - rebase(doc, mine, fetched, theirs, cutoff)
            val merged = SyncMerge.merge(mine.filter { (k, e) -> !doc.expired(k, e, now) }, theirs)
            val (applied, waiting) = applyNewer(doc, merged.fromThem - ahead)
            if (fetched is Fetched.Incomplete) return SyncOutcome.Incomplete(applied)
            store.markFetched(doc.name, nowMs())
            val publish = merged.theyLack
            if (publish) relays.publish(signer, doc.name, merged.all, urls, notBefore)
            // The relays hold these versions now; anything changed here since stays pending.
            store.markSent(doc.name, merged.all)
            SyncOutcome.Done(applied, publish, pending = waiting)
        } catch (e: SignerNeedsListener) {
            SyncOutcome.WaitsForSigner
        } catch (e: SignerRejected) {
            SyncOutcome.WaitsForSigner
        } catch (e: SyncNewerVersion) {
            SyncOutcome.NeedsUpdate
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.Failed(e)
        }
    }

    /**
     * A device away longer than removal markers are kept (audit 2026-10-22, M-03):
     * an entry it holds, not changed here since it last sent, and no longer
     * in a complete document there was removed elsewhere, its marker gone by now —
     * removed here too instead of brought back. The keys removed.
     */
    private suspend fun rebase(doc: SyncedDocument, mine: Map<String, SyncEntry>, fetched: Fetched, theirs: Map<String, SyncEntry>, cutoff: Long): Set<String> {
        val last = store.fetchedAt(doc.name) ?: return emptySet()
        if (last >= cutoff || fetched !is Fetched.Complete) return emptySet()
        val changedHere = store.pendingKeys(doc.name)
        val gone = mine.filter { (k, e) -> k !in theirs && !e.del && k !in changedHere }
        if (gone.isEmpty()) return emptySet()
        doc.apply(gone.mapValues { (_, e) -> SyncEntry(e.t, del = true, d = e.d) })
        // Removed only as it was: a change the listener made while it was applied stays, and holds
        // in the app again (delta audit 2026-10-22, HN-04).
        val removed = store.removeIfSent(doc.name, gone)
        reassert(doc, gone.keys - removed)
        return removed
    }

    /**
     * [incoming] applied where it is still newer than this device's, and taken into the store
     * where it still is after — the listener may change the same thing meanwhile (delta audit
     * 2026-10-22, HN-07): then the app holds theirs and the store ours, and ours goes back
     * into the app. How many were taken, and how many wait (a feed out of reach).
     */
    private suspend fun applyNewer(doc: SyncedDocument, incoming: Map<String, SyncEntry>): Pair<Int, Int> {
        val toApply = store.stillNewer(doc.name, incoming)
        if (toApply.isEmpty()) return 0 to 0
        val applied = doc.apply(toApply)
        val taken = store.putIfNewer(doc.name, toApply.filterKeys { it in applied })
        reassert(doc, applied - taken)
        return taken.size to (toApply.size - applied.size)
    }

    /** This device's own entries for [keys] into the app once more — over what another device's
     *  applied a moment after the listener changed them. Again while they change meanwhile. */
    private suspend fun reassert(doc: SyncedDocument, keys: Set<String>) {
        var left = keys
        repeat(REASSERT_TRIES) {
            if (left.isEmpty()) return
            val ours = left.mapNotNull { k -> store.entry(doc.name, k)?.let { k to it } }.toMap()
            if (ours.isEmpty()) return
            doc.apply(ours)
            left = ours.filter { (k, e) -> store.entry(doc.name, k) != e }.keys
        }
    }

    /**
     * What the relays hold of document [name], read without taking anything —
     * for the offer to restore. Null: nothing there. [Sealed]: the listener has
     * a sync key, but the signer would have to ask before it opens.
     */
    suspend fun peek(name: String): Peek? {
        if (identity.identity.value == null) return null
        return try {
            val signer = keys.signer(interactive = false, create = false) ?: return null
            when (val f = relays.fetch(signer, name, identity.writeRelays())) {
                is Fetched.Complete -> Peek.Open(f.entries)
                is Fetched.Incomplete -> Peek.Open(f.entries)
                Fetched.Absent, is Fetched.Unknown -> null
            }
        } catch (e: SignerNeedsListener) {
            Peek.Sealed
        } catch (e: SignerRejected) {
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    sealed interface Peek {
        data class Open(val entries: Map<String, SyncEntry>) : Peek
        data object Sealed : Peek
    }

    /** A new sync key (ADR 0022, 3); the old one is retired by [retireMoved] once the documents are out. */
    suspend fun renewKey() = lock.withLock { keys.renew() }

    /** What a key this device moved off sealed goes from the relays — after a round went through under the new one. */
    suspend fun retireMoved(): Boolean = lock.withLock { keys.retireMoved() }

    /** Whether a key this device moved off still waits to be retired. */
    fun movedPending(): Boolean = keys.movedPending()

    /** This device forgets the sync key; the keyring stays on the relays. */
    suspend fun forgetKey() = lock.withLock { keys.forget() }

    /** Whether [doc] changed here since the relays last took it. */
    fun unpublished(doc: SyncedDocument): Boolean = store.unpublished(doc.name)

    companion object {
        /** Removal markers stay this long; a device away longer starts from the relays' list ([rebase]). */
        val REMOVALS_KEPT = 90.days
        /** How far ahead of this device's clock an entry may be dated. */
        val MAX_AHEAD = 1.days
        private const val REASSERT_TRIES = 3
    }
}
