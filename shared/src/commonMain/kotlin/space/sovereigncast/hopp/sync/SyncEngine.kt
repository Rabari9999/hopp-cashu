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
    /** [applied] entries came from elsewhere; [published]: this device's went out. */
    data class Done(val applied: Int, val published: Boolean) : SyncOutcome
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
            val signer = keys.signer(interactive) ?: return SyncOutcome.NoIdentity
            val urls = identity.writeRelays()
            val cutoff = nowMs() - REMOVALS_KEPT.inWholeMilliseconds
            val now = nowMs()
            store.pruneRemoved(doc.name, cutoff)
            val mine = store.entries(doc.name)
            mine.filter { (k, e) -> doc.expired(k, e, now) }.keys.takeIf { it.isNotEmpty() }?.let { store.remove(doc.name, it) }
            val theirs = relays.fetch(signer, doc.name, urls).orEmpty().filter { (k, e) -> !(e.del && e.t < cutoff) && !doc.expired(k, e, now) }
            val seeded = seed.filterKeys { it !in theirs && it !in mine }
            if (seeded.isNotEmpty()) store.putAll(doc.name, seeded)
            val merged = SyncMerge.merge((mine + seeded).filter { (k, e) -> !doc.expired(k, e, now) }, theirs)
            val applied = if (merged.fromThem.isEmpty()) emptySet() else doc.apply(merged.fromThem)
            store.putAll(doc.name, merged.fromThem.filterKeys { it in applied })
            store.markFetched(doc.name, nowMs())
            val publish = merged.theyLack
            if (publish) {
                relays.publish(signer, doc.name, merged.all, urls)
                store.markPublished(doc.name, merged.all.values.maxOf { it.t })
            } else {
                merged.all.values.maxOfOrNull { it.t }?.let { store.markPublished(doc.name, it) }
            }
            SyncOutcome.Done(applied.size, publish)
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
     * What the relays hold of document [name], read without taking anything —
     * for the offer to restore. Null: nothing there. [Sealed]: the listener has
     * a sync key, but the signer would have to ask before it opens.
     */
    suspend fun peek(name: String): Peek? {
        if (identity.identity.value == null) return null
        return try {
            val signer = keys.signer(interactive = false, create = false) ?: return null
            relays.fetch(signer, name, identity.writeRelays())?.let { Peek.Open(it) }
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

    /** This device forgets the sync key; the keyring stays on the relays. */
    suspend fun forgetKey() = lock.withLock { keys.forget() }

    /** Whether [doc] changed here since the relays last took it. */
    fun unpublished(doc: SyncedDocument): Boolean = store.unpublished(doc.name)

    companion object {
        /** Removal markers stay this long; a device away longer starts from the relays' list. */
        val REMOVALS_KEPT = 90.days
    }
}
