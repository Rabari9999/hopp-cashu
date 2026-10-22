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

    /** [seed]: entries of time 0 from [SyncedDocument.snapshot], taken only where neither side has the key. */
    suspend fun sync(doc: SyncedDocument, interactive: Boolean = false, seed: Map<String, SyncEntry> = emptyMap()): SyncOutcome = lock.withLock {
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
                Fetched.Unknown -> return SyncOutcome.Failed(SyncNotReached())
            }
            // An entry dated more than a day ahead is a clock gone wrong: not taken, and gone with the next version (M-02).
            val theirs = read.filter { (k, e) -> !(e.del && e.t < cutoff) && !doc.expired(k, e, now) && e.t <= now + MAX_AHEAD.inWholeMilliseconds }
            mine = mine - rebase(doc, mine, fetched, theirs, cutoff)
            val seeded = seed.filterKeys { it !in theirs && it !in mine }
            if (seeded.isNotEmpty()) store.putIfNewer(doc.name, seeded, pending = true)
            val merged = SyncMerge.merge((mine + seeded).filter { (k, e) -> !doc.expired(k, e, now) }, theirs)
            // Checked again right before: what the listener changed while the relays answered stays (H-03).
            val toApply = store.stillNewer(doc.name, merged.fromThem)
            val applied = if (toApply.isEmpty()) emptySet() else doc.apply(toApply)
            store.putIfNewer(doc.name, toApply.filterKeys { it in applied })
            if (fetched is Fetched.Incomplete) return SyncOutcome.Incomplete(applied.size)
            store.markFetched(doc.name, nowMs())
            val publish = merged.theyLack
            if (publish) relays.publish(signer, doc.name, merged.all, urls, notBefore)
            // The relays hold these versions now; anything changed here since stays pending.
            store.markSent(doc.name, merged.all)
            SyncOutcome.Done(applied.size, publish, pending = toApply.size - applied.size)
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
        store.remove(doc.name, gone.keys)
        return gone.keys
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
                Fetched.Absent, Fetched.Unknown -> null
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

    /** A new sync key (ADR 0022, 3); the old one, for [retire] once the documents are out. */
    suspend fun renewKey() = lock.withLock { keys.renew() }

    /** What the old key sealed goes from the relays. */
    suspend fun retire(old: space.sovereigncast.hopp.nostr.KeyPair) = lock.withLock { keys.retire(old) }

    /** This device forgets the sync key; the keyring stays on the relays. */
    suspend fun forgetKey() = lock.withLock { keys.forget() }

    /** Whether [doc] changed here since the relays last took it. */
    fun unpublished(doc: SyncedDocument): Boolean = store.unpublished(doc.name)

    companion object {
        /** Removal markers stay this long; a device away longer starts from the relays' list ([rebase]). */
        val REMOVALS_KEPT = 90.days
        /** How far ahead of this device's clock an entry may be dated. */
        val MAX_AHEAD = 1.days
    }
}
