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

    /** Entries from another device, newer than this one's: make them so here. */
    suspend fun apply(entries: Map<String, SyncEntry>)
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
 * another device added since.
 */
class SyncEngine(
    private val store: SyncStore,
    private val relays: SyncRelays,
    private val identity: IdentityRepository,
    private val nowMs: () -> Long,
) {
    private val lock = Mutex()

    suspend fun sync(doc: SyncedDocument, interactive: Boolean = false): SyncOutcome = lock.withLock {
        val signer = identity.signer(interactive) ?: return SyncOutcome.NoIdentity
        try {
            val urls = identity.writeRelays()
            val cutoff = nowMs() - REMOVALS_KEPT.inWholeMilliseconds
            store.pruneRemoved(doc.name, cutoff)
            val theirs = relays.fetch(signer, doc.name, urls).orEmpty().filterValues { !(it.del && it.t < cutoff) }
            val merged = SyncMerge.merge(store.entries(doc.name), theirs)
            if (merged.fromThem.isNotEmpty()) {
                doc.apply(merged.fromThem)
                store.putAll(doc.name, merged.fromThem)
            }
            store.markFetched(doc.name, nowMs())
            val publish = merged.theyLack
            if (publish) {
                relays.publish(signer, doc.name, merged.all, urls)
                store.markPublished(doc.name, merged.all.values.maxOf { it.t })
            } else {
                merged.all.values.maxOfOrNull { it.t }?.let { store.markPublished(doc.name, it) }
            }
            SyncOutcome.Done(merged.fromThem.size, publish)
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

    /** Whether [doc] changed here since the relays last took it. */
    fun unpublished(doc: SyncedDocument): Boolean = store.unpublished(doc.name)

    companion object {
        /** Removal markers stay this long; a device away longer starts from the relays' list. */
        val REMOVALS_KEPT = 90.days
    }
}
