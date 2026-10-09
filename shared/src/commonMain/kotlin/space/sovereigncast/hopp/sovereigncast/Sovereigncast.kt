package space.sovereigncast.hopp.sovereigncast

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import space.sovereigncast.hopp.crypto.secureRandomBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.data.Clock
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.directory.feedKey
import space.sovereigncast.hopp.model.Podcast
import space.sovereigncast.hopp.value.Bolt11
import space.sovereigncast.hopp.value.ValueService
import space.sovereigncast.hopp.value.WalletRepository
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A show's verified Sovereigncast instance and what it offers. */
data class ScLayer(val base: String, val show: ScShow) {
    /** For the quiet line naming where community data comes from. */
    val host: String get() = base.substringAfter("://").substringBefore('/')
}

/** What a listener pays for through the instance's own invoices. */
sealed interface ScTarget {
    data object ReleaseBoosting : ScTarget
    data class Boost2Unlock(val episodeGuid: String) : ScTarget
}

/** An invoice that did not ask for what the listener chose (ADR 0005: never paid). */
class InvoiceMismatch(message: String) : Exception(message)

/**
 * The slim Sovereigncast layer of phase 1 (ADR 0002, 0010, 0018): find the
 * marker in the feed, have the instance confirm the show, then Release
 * Boosting and Boost2Unlock progress and payment, the open suggestions and
 * the free vote.
 */
class SovereigncastRepository(
    private val db: HoppDatabase,
    private val api: ListenerApi,
    private val podcasts: PodcastRepository,
    private val settings: SettingsRepository,
    private val wallet: WalletRepository,
    private val value: ValueService,
    private val clock: Clock,
) {
    private val q = db.podcastQueries
    private val json = Json { ignoreUnknownKeys = true }

    /** The show's layer, once verified; null otherwise. */
    fun layer(podcastId: Long): Flow<ScLayer?> = q.selectSovereigncast(podcastId).asFlow().mapToOneOrNull(Dispatchers.IO).map { r ->
        val base = r?.sc_base_url ?: return@map null
        val show = r.sc_capabilities_json?.let { runCatching { json.decodeFromString(ScShow.serializer(), it) }.getOrNull() } ?: return@map null
        ScLayer(base, show)
    }

    /**
     * Marker → instance → confirmation (ADR 0002). The feed alone is not
     * trusted: the instance must manage this podcast:guid and report this very
     * feed, or the layer stays off. A network failure keeps what was known;
     * a "not here" from the instance switches the layer off.
     */
    suspend fun verify(podcastId: Long, force: Boolean = false) {
        val meta = podcasts.podcastMeta(podcastId) ?: return
        val subscribed = podcasts.show(podcastId).first() ?: return
        val base = markerOf(meta)
        val guid = meta.podcastGuid
        if (base == null || guid == null) { clear(podcastId); return }
        val known = withContext(Dispatchers.IO) { q.selectSovereigncast(podcastId).executeAsOneOrNull() }
        if (!force && known?.sc_base_url == base && (clock.now() - (known.sc_verified_at ?: 0)) < REVERIFY_SECONDS) return
        try {
            if ("v1" !in api.meta(base).apiVersions) { clear(podcastId); return }
            val show = api.show(base, guid)
            if (!show.podcastGuid.equals(guid, ignoreCase = true) || feedKey(show.feedUrl) != feedKey(subscribed.feedUrl)) {
                clear(podcastId); return
            }
            withContext(Dispatchers.IO) { q.setSovereigncast(base, clock.now(), json.encodeToString(ScShow.serializer(), show), podcastId) }
        } catch (e: ListenerApiException) {
            if (e.status == 404 || e.code == "show_not_found") clear(podcastId)
        } catch (e: Exception) {
            // Offline or the instance is down: keep what we knew.
        }
    }

    private suspend fun clear(podcastId: Long) = withContext(Dispatchers.IO) { q.clearSovereigncast(podcastId) }

    suspend fun releaseBoosting(layer: ScLayer) = api.releaseBoosting(layer.base, layer.show.podcastGuid)
    suspend fun boost2unlock(layer: ScLayer) = api.boost2unlock(layer.base, layer.show.podcastGuid)
    suspend fun suggestions(layer: ScLayer) = api.suggestions(layer.base, layer.show.podcastGuid)
    suspend fun leaderboard(layer: ScLayer) = api.leaderboard(layer.base, layer.show.podcastGuid)
    /** Boosted sats by episode guid (only episodes that have any). */
    suspend fun episodeSats(layer: ScLayer): Map<String, Long> =
        api.episodeSats(layer.base, layer.show.podcastGuid).episodes.associate { it.episodeGuid to it.boostedSats }

    /** Free, one per listener and suggestion; remembered here to show it. */
    suspend fun vote(layer: ScLayer, suggestionId: Long): VoteResult {
        var voter = settings.current().voterId
        if (voter.isEmpty()) {
            voter = secureRandomBytes(16).toHex()
            settings.update { it.copy(voterId = voter) }
        }
        val result = api.vote(layer.base, layer.show.podcastGuid, suggestionId, voter)
        val key = "${layer.show.podcastGuid}/$suggestionId"
        settings.update { it.copy(votes = it.votes + key) }
        return result
    }

    fun voted(layer: ScLayer, suggestionId: Long, votes: Set<String>) = "${layer.show.podcastGuid}/$suggestionId" in votes

    /** The instance's invoice — checked to be for exactly [amountSats] before anyone pays it (ADR 0005). */
    suspend fun invoice(layer: ScLayer, target: ScTarget, amountSats: Long, message: String?, senderName: String?): Invoice {
        val request = BoostRequest(amountSats, message?.takeIf { it.isNotBlank() }, senderName?.takeIf { it.isNotBlank() })
        val invoice = when (target) {
            ScTarget.ReleaseBoosting -> api.releaseBoostingInvoice(layer.base, layer.show.podcastGuid, request)
            is ScTarget.Boost2Unlock -> api.boost2unlockInvoice(layer.base, layer.show.podcastGuid, target.episodeGuid, request)
        }
        val asked = runCatching { Bolt11.parse(invoice.bolt11).amountMsat }.getOrNull()
        if (asked != amountSats * 1000) throw InvoiceMismatch("invoice asks ${asked ?: "no amount"} msat, not ${amountSats * 1000}")
        return invoice
    }

    /** Pays through the connected wallet; returns the routing fee if the wallet says. */
    suspend fun payWithWallet(invoice: Invoice): Long? {
        val client = wallet.client() ?: throw IllegalStateException("no wallet connected")
        return client.payInvoice(invoice.bolt11).feesPaidMsat
    }

    /** Waits until the instance has seen the payment — paid elsewhere, or by
     *  the wallet a moment ago. True when paid, false when expired or too long. */
    suspend fun awaitPaid(layer: ScLayer, invoice: Invoice, timeoutMinutes: Int = 10): Boolean =
        withTimeoutOrNull(timeoutMinutes.minutes) {
            var paid: Boolean? = null
            while (paid == null) {
                val status = runCatching { api.payment(layer.base, invoice.paymentHash) }.getOrNull()
                when (status?.status) {
                    "paid" -> paid = true
                    "expired" -> paid = false
                    else -> delay((status?.pollAfterSeconds ?: 2).coerceIn(1, 10).seconds)
                }
            }
            paid
        } ?: false

    /** Into the payment history, once the instance confirmed it. */
    suspend fun log(podcastId: Long, layer: ScLayer, target: ScTarget, episodeTitle: String?, amountSats: Long, message: String?, feesMsat: Long?) =
        value.logInvoicePayment(
            if (target is ScTarget.ReleaseBoosting) ValueService.KIND_RELEASE_BOOSTING else ValueService.KIND_BOOST2UNLOCK,
            podcastId, layer.show.title, episodeTitle, amountSats * 1000, message, layer.show.title, layer.host, feesMsat,
        )

    companion object {
        /** Re-ask the instance at most once a day (ADR 0002 verification is cheap but not free). */
        const val REVERIFY_SECONDS = 24 * 3600L

        /** `<podcast:txt purpose="sovereigncast">https://…</podcast:txt>` — https only (ADR 0002). */
        fun markerOf(podcast: Podcast): String? = podcast.txt
            .firstOrNull { it.purpose.equals("sovereigncast", ignoreCase = true) }
            ?.value?.trim()?.trimEnd('/')
            ?.takeIf { it.startsWith("https://") && it.length > "https://".length }
    }
}
