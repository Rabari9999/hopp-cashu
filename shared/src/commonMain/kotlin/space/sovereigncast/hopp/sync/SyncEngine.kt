package space.sovereigncast.hopp.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Nip46Exception
import space.sovereigncast.hopp.nostr.SignerNeedsListener
import space.sovereigncast.hopp.nostr.SignerNoAnswer
import space.sovereigncast.hopp.nostr.SignerRejected
import space.sovereigncast.hopp.nostr.SignerUnavailable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** A document sync keeps in step: subscriptions, progress, "Up next", settings. */
interface SyncedDocument {
    /** Its name in the `d` tag: `hopp/<name>`. */
    val name: String

    /** Entries from another device, newer than this one's: make them so here. The keys
     *  done; one that cannot be yet (a feed out of reach) is tried again next time. */
    suspend fun apply(entries: Map<String, SyncEntry>): Set<String>

    /** [apply], where a step that cannot be undone (a show given up takes its episodes, progress and
     *  place in "Up next" with it) asks [stillWins] first, in the same transaction as the step: the
     *  listener may have changed the same thing a moment ago (delta audit 2 2026-10-22, HN-07). */
    suspend fun apply(entries: Map<String, SyncEntry>, stillWins: suspend (key: String, entry: SyncEntry) -> Boolean): Set<String> = apply(entries)

    /** What this device has, as entries with the best time it knows — merged once,
     *  when sync is turned on. An entry of time 0 has none: it joins only where the
     *  relays have nothing (settings, the queue's order). */
    suspend fun snapshot(): Map<String, SyncEntry>

    /** An entry no device needs any more (progress older than 90 days): it is dropped
     *  everywhere alike, not marked removed. */
    fun expired(key: String, entry: SyncEntry, nowMs: Long): Boolean = false

    /** Before each round: what the document may tidy here first (progress: "heard up to" found
     *  where single entries say it, maintainer 2026-10-30). */
    suspend fun prepare() = Unit

    /** Of [entries] (this device's and the relays', the newer per key), those another entry already
     *  says — left out of the store, the round and what goes out, without a removal marker. */
    suspend fun redundant(entries: Map<String, SyncEntry>): Set<String> = emptySet()

    /** Of [entries] [apply] could not do, those that will not come here: an episode the show's feed no
     *  longer carries (maintainer 2026-11-01: "4 episodes waiting" for good). Tried again each round all
     *  the same, but not counted as waiting. */
    suspend fun beyondReach(entries: Map<String, SyncEntry>): Set<String> = emptySet()

    /** For the sync report: what [entries] are, in numbers — no titles (maintainer 2026-10-30: a document
     *  too large, and nothing said what made it so). */
    suspend fun describe(entries: Map<String, SyncEntry>): String? = null
}

sealed interface SyncOutcome {
    /** [applied] entries came from elsewhere; [published]: this device's went out; [pending]:
     *  entries from elsewhere not applicable yet (a feed out of reach) — tried again next time. */
    data class Done(val applied: Int, val published: Boolean, val pending: Int = 0) : SyncOutcome
    /** The relays gave the document only in part: [applied] of it taken, nothing sent (audit 2026-10-22, H-02). */
    /** [doc]: which document came in part (the sync page's report names it); [found]: what the read
     *  found, and [rewriteInMs] how long until a gap that stays is written over, for the report.
     *  [canRewrite]: a majority read it to the end — only then may the listener have it written anew. */
    data class Incomplete(
        val applied: Int,
        val doc: String? = null,
        val found: String? = null,
        val rewriteInMs: Long? = null,
        val canRewrite: Boolean = false,
    ) : SyncOutcome
    data object NoIdentity : SyncOutcome
    /** The signer app would have to ask the listener — sync waits for the app to be open. */
    data object WaitsForSigner : SyncOutcome
    /** A newer Hopp wrote this document: left as it is until this one is updated. */
    data object NeedsUpdate : SyncOutcome
    data class Failed(val error: Throwable) : SyncOutcome

    /** It is the signer that sync waits for or that failed — every document would meet it the same. */
    fun isTheSigner(): Boolean = this is WaitsForSigner || (this is Failed && (error is SignerNoAnswer || error is Nip46Exception || error is SignerUnavailable || error is space.sovereigncast.hopp.nostr.SignerUnreachable))
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
    /** When each document last went out for its compaction alone ([COMPACT_EVERY]); this run only. */
    private val compactedAt = mutableMapOf<String, Long>()

    /** One round for [doc]. What this device had when sync was turned on without a time (settings, the
     *  queue's order) waits in the store as seeds ([SyncStore.seed]), taken only where the relays have nothing. */
    suspend fun sync(doc: SyncedDocument, interactive: Boolean = false, rewrite: Boolean = false): SyncOutcome = lock.withLock {
        if (identity.identity.value == null) return SyncOutcome.NoIdentity
        try {
            // A first keyring only with the listener there — never from a round in the background (audit 2026-10-22, H-01).
            val signer = keys.signer(interactive, create = interactive) ?: return SyncOutcome.WaitsForSigner
            val urls = identity.syncRelays()
            val own = identity.writeRelays()
            val now = nowMs()
            val cutoff = now - REMOVALS_KEPT.inWholeMilliseconds
            store.pruneRemoved(doc.name, cutoff)
            doc.prepare()
            var mine = store.entries(doc.name)
            mine.filter { (k, e) -> doc.expired(k, e, now) }.keys.takeIf { it.isNotEmpty() }?.let { store.remove(doc.name, it); mine = mine - it }
            val fetched = relays.fetch(signer, doc.name, urls, own)
            // A gap that will stay (Fetched.Incomplete): written over with everything that arrived — one this
            // device left, one of older parts only, or one every relay has shown for [STUCK_FOR] (maintainer
            // 2026-10-30: a version cut off by a relay slowing down, left before Hopp kept which).
            var repair = fetched is Fetched.Incomplete && fetched.settled &&
                (fetched.stale || (fetched.newestRev != null && fetched.newestRev == store.brokenRev(doc.name)))
            var rewriteIn: Long? = null
            if (fetched is Fetched.Incomplete && fetched.settled && !repair && fetched.newestRev != null) {
                val (rev, since) = store.stuck(doc.name)
                if (rev == fetched.newestRev && since != null) repair = now - since >= STUCK_FOR.inWholeMilliseconds
                else store.markStuck(doc.name, fetched.newestRev, now)
                if (!repair) rewriteIn = STUCK_FOR.inWholeMilliseconds - (now - (since?.takeIf { rev == fetched.newestRev } ?: now))
            }
            // The listener's own word on the sync page: this device's copy, whole, over whatever came in part —
            // as the waits above, only with a majority read to the end: a relay that did not answer may hold a
            // newer version whole, and written over it would be gone from the relays (review 2026-10-31).
            if (fetched is Fetched.Incomplete && fetched.settled && rewrite) repair = true
            if (fetched is Fetched.Complete) store.markStuck(doc.name, null, null)
            val (read, notBefore) = when (fetched) {
                is Fetched.Complete -> fetched.entries to fetched.newestAt
                is Fetched.Incomplete -> (if (repair) fetched.all else fetched.entries) to fetched.newestAt
                Fetched.Absent -> emptyMap<String, SyncEntry>() to 0L
                is Fetched.Unknown -> return SyncOutcome.Failed(SyncNotReached(fetched.answered, fetched.asked))
            }
            // A removal counts from when it first went out, never later than now (HN-05): one made offline
            // and sent months later is not too old the moment it arrives.
            val theirsRead = read.mapValues { (_, e) -> if (e.del) e.copy(out = e.out?.coerceAtMost(now)) else e.copy(out = null) }
                .filter { (k, e) -> !(e.del && (e.out ?: e.t) < cutoff) && !doc.expired(k, e, now) }
            // What another entry already says (a show's "heard up to" its old episodes, maintainer 2026-10-30):
            // left out here and in what goes out; many of them on the relays, a smaller version goes out.
            val newest = buildMap { putAll(mine); theirsRead.forEach { (k, e) -> val m = this[k]; if (m == null || e.winsOver(m)) put(k, e) } }
            val covered = doc.redundant(newest)
            if (covered.isNotEmpty()) {
                store.remove(doc.name, covered.filterTo(mutableSetOf()) { it in mine })
                mine = mine - covered
            }
            val theirs = theirsRead - covered
            val compact = covered.count { it in theirsRead } >= COMPACT_AT
            // An entry dated more than a day ahead is a clock gone wrong: not taken into the app, but kept in
            // the store, so it goes along in what goes out — under a new key too — and into the app once its
            // time has come (delta audit 2026-10-22 and 2, MN-02).
            val ahead = theirs.filterValues { it.t > now + MAX_AHEAD.inWholeMilliseconds }.keys
            // A seed gives way to any value the relays hold — on a second device its defaults never
            // win over the first one's settings (HN-01).
            val beaten = store.seeds(doc.name).filterTo(mutableSetOf()) { it in theirs }
            if (beaten.isNotEmpty()) { store.dropSeeds(doc.name, beaten); mine = mine - beaten }
            mine = mine - rebase(doc, mine, fetched, theirs, cutoff)
            val merged = SyncMerge.merge(mine.filter { (k, e) -> !doc.expired(k, e, now) }, theirs)
            hold(doc, merged.fromThem.filterKeys { it in ahead })
            val (applied, waiting) = applyNewer(doc, merged.fromThem - ahead)
            repair(doc, now, skip = merged.fromThem.keys)
            if (fetched is Fetched.Incomplete && !repair) return SyncOutcome.Incomplete(applied, doc.name, fetched.found, rewriteIn, canRewrite = fetched.settled)
            store.markFetched(doc.name, nowMs())
            // The gap goes under a whole version, even when the relays lack nothing of ours. A smaller version
            // for its own sake at most every [COMPACT_EVERY]: a Hopp before 0.3.2 puts the covered entries back
            // each round, and the two would answer each other for as long as both are in use (review 2026-10-31).
            val compactOnly = compact && !merged.theyLack && !repair
            val publish = merged.theyLack || repair ||
                (compactOnly && compactedAt[doc.name]?.let { now - it >= COMPACT_EVERY.inWholeMilliseconds } != false)
            // A removal made here goes out with the time it goes out (HN-05).
            val pendingHere = store.pendingKeys(doc.name)
            val all = merged.all.mapValues { (k, e) -> if (e.del && k in pendingHere) e.copy(out = now) else e }
            if (publish) {
                // Kept before the first part goes, cleared once all went: however a round ends between
                // (refused, timed out, the tab closed), this device knows the gap it may have left.
                val rev = SyncRelays.newRev()
                store.markBroken(doc.name, rev)
                relays.publish(signer, doc.name, all, urls, notBefore, rev)
                if (compactOnly) compactedAt[doc.name] = now
                store.markBroken(doc.name, null)
                store.markStuck(doc.name, null, null)
            }
            // The relays hold these versions now; anything changed here since stays pending.
            store.markSent(doc.name, all)
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
        store.markUnapplied(doc.name, gone.keys)
        // Each removal only while the entry is still as it was here (delta audit 2 2026-10-22, HN-07).
        doc.apply(gone.mapValues { (_, e) -> SyncEntry(e.t, del = true, d = e.d) }) { k, _ -> store.entry(doc.name, k) == gone[k] }
        // Removed only as it was: a change the listener made while it was applied stays, and holds
        // in the app again (delta audit 2026-10-22, HN-04).
        val removed = store.removeIfSent(doc.name, gone)
        store.applied(doc.name, removed.associateWith { null })
        reassert(doc, gone.keys - removed)
        return removed
    }

    /** Entries dated far ahead ([MAX_AHEAD]): into the store, not the app — [repair] takes them in once their time has come. */
    private suspend fun hold(doc: SyncedDocument, ahead: Map<String, SyncEntry>) {
        if (ahead.isEmpty()) return
        store.markUnapplied(doc.name, store.putIfNewer(doc.name, ahead))
    }

    /**
     * What the app may not hold as the store does — a round cut off halfway, a change under it the
     * listener kept making, an entry whose time has come ([hold]) — put into the app from the store
     * (delta audit 2 2026-10-22, HN-07/MN-02/MN-07). [skip]: keys this round just applied.
     */
    private suspend fun repair(doc: SyncedDocument, now: Long, skip: Set<String>) {
        val due = store.unapplied(doc.name).filter { (k, e) -> k !in skip && (e == null || e.t <= now + MAX_AHEAD.inWholeMilliseconds) }
        if (due.isEmpty()) return
        // Nothing in the store: the app holds another device's entry the store never took — it comes again from the relays.
        val ours = due.filterValues { it != null }.mapValues { it.value!! }
        val done = if (ours.isEmpty()) emptySet() else doc.apply(ours) { k, e -> store.entry(doc.name, k) == e }
        store.applied(doc.name, due.filterKeys { it in done || due[it] == null })
    }

    /**
     * [incoming] applied where it is still newer than this device's, and taken into the store
     * where it still is after — the listener may change the same thing meanwhile (delta audit
     * 2026-10-22, HN-07): then the app holds theirs and the store ours, and ours goes back
     * into the app. How many were taken, and how many wait (an episode its feed has not brought yet).
     */
    private suspend fun applyNewer(doc: SyncedDocument, incoming: Map<String, SyncEntry>): Pair<Int, Int> {
        val toApply = store.stillNewer(doc.name, incoming)
        if (toApply.isEmpty()) return 0 to 0
        // Noted first, cleared once the app and the store agree again: cut off in between, the next round
        // puts it right (delta audit 2 2026-10-22, MN-07).
        store.markUnapplied(doc.name, toApply.keys)
        val applied = doc.apply(toApply) { k, e -> store.entry(doc.name, k)?.let { e.winsOver(it) } ?: true }
        val taken = store.putIfNewer(doc.name, toApply.filterKeys { it in applied })
        // Taken, and not applied at all (the app still has what it had): the app holds what the store does.
        store.applied(doc.name, toApply.filterKeys { it in taken })
        store.clearUnapplied(doc.name, toApply.keys - applied)
        reassert(doc, applied - taken)
        val notYet = toApply.filterKeys { it !in applied }
        return taken.size to (notYet.size - doc.beyondReach(notYet).size)
    }

    /** This device's own entries for [keys] into the app once more — over what another device's
     *  applied a moment after the listener changed them. Again while they change meanwhile; what
     *  still differs after that stays noted, and the next round puts it right ([repair]). */
    private suspend fun reassert(doc: SyncedDocument, keys: Set<String>) {
        var left = keys
        repeat(REASSERT_TRIES) {
            if (left.isEmpty()) return
            val ours = left.mapNotNull { k -> store.entry(doc.name, k)?.let { k to it } }.toMap()
            if (ours.isEmpty()) return
            val done = doc.apply(ours) { k, e -> store.entry(doc.name, k) == e }
            store.applied(doc.name, ours.filterKeys { it in done })
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
            when (val f = relays.fetch(signer, name, identity.syncRelays(), identity.writeRelays())) {
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

    /** What a key this device moved off sealed goes from the relays — only after a round under the new one
     *  read every document back complete with nothing left to send ([SyncManager.syncNow]). */
    suspend fun retireMoved(): Boolean = lock.withLock { keys.retireMoved() }

    /** Whether a key this device moved off still waits to be retired. */
    fun movedPending(): Boolean = keys.movedPending()

    /** Changes whenever this device moves to another sync key: a round that saw it change did not run under one key throughout. */
    fun keyGeneration(): Int = keys.generation

    /** This device forgets the sync key; the keyring stays on the relays. */
    suspend fun forgetKey() = lock.withLock { keys.forget() }

    /** How many relays answered the last read, of how many. */
    fun reach(): Pair<Int, Int>? = relays.lastReach

    /** Entries changed here the relays have not taken yet, over every document. */
    suspend fun toSend(): Int = store.pendingCount()

    /** Whether [doc] changed here since the relays last took it. */
    suspend fun unpublished(doc: SyncedDocument): Boolean = store.unpublished(doc.name)

    companion object {
        /** Removal markers stay this long; a device away longer starts from the relays' list ([rebase]). */
        val REMOVALS_KEPT = 90.days
        /** How far ahead of this device's clock an entry may be dated. */
        val MAX_AHEAD = 1.days
        /** How long a gap must stay, every relay answering, before it is written over though no one
         *  here knows who left it: a relay holding back a part for a moment still stops the round (HN-02). */
        val STUCK_FOR = 3.minutes
        /** This many covered entries on the relays: a smaller version goes out though nothing changed. */
        const val COMPACT_AT = 50
        /** A smaller version with nothing else to send goes out at most this often. */
        val COMPACT_EVERY = 6.hours
        private const val REASSERT_TRIES = 3
    }
}
