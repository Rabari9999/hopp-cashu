package space.sovereigncast.hopp.sovereigncast

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.platform.ioDispatcher
import space.sovereigncast.hopp.value.BoostContext
import space.sovereigncast.hopp.value.Share

/**
 * Boost metadata for Lightning-address shares of a Sovereigncast show (ADR
 * 0005): an LNURL comment cannot carry a boostagram, so the instance stores it
 * and the comment points there (`rss::payment::boost <url> <message>`). Only
 * for verified shows whose instance offers `payment_metadata`; for any other
 * show the comment stays the plain message — Hopp hosts nothing.
 */
class PaymentMetadataSource(private val db: HoppDatabase, private val api: ListenerApi) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The provider for one show's boost, or null when it has no such instance. */
    suspend fun forShow(podcastId: Long, appVersion: String): (suspend (Share, Long, BoostContext) -> String?)? {
        val row = withContext(ioDispatcher) { db.podcastQueries.selectSovereigncast(podcastId).awaitAsOneOrNull() } ?: return null
        val base = row.sc_base_url ?: return null
        val show = row.sc_capabilities_json?.let { runCatching { json.decodeFromString(ScShow.serializer(), it) }.getOrNull() } ?: return null
        if (!show.capabilities.paymentMetadata) return null
        return { share, total, ctx ->
            api.paymentMetadata(base, show.podcastGuid, PaymentMetadataRequest(
                action = ctx.action, appName = "Hopp", appVersion = appVersion, episodeGuid = ctx.episodeGuid,
                message = ctx.message, senderName = ctx.senderName,
                recipientName = share.recipient.name, recipientAddress = share.recipient.address,
                valueMsat = share.amountMsat, valueMsatTotal = total, position = ctx.positionSeconds,
            )).commentPrefix
        }
    }
}
