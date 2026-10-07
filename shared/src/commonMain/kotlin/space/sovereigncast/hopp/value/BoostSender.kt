package space.sovereigncast.hopp.value

import space.sovereigncast.hopp.model.ValueBlock
import space.sovereigncast.hopp.value.nwc.NwcClient
import space.sovereigncast.hopp.value.nwc.WalletInfo

/** What happened to one recipient's share. */
sealed interface ShareResult {
    val share: Share
    data class Paid(override val share: Share, val route: String, val preimage: String?, val feesMsat: Long?) : ShareResult
    /** Not attempted — ADR 0005: shown to the listener, neither paid nor redistributed. */
    data class Skipped(override val share: Share, val reason: String) : ShareResult
    data class Failed(override val share: Share, val route: String, val reason: String) : ShareResult
}

/** The boost-wide fields of the boostagram; the per-recipient ones are filled in. */
data class BoostContext(
    val action: String,
    val appVersion: String,
    val podcast: String?,
    val episode: String?,
    val feedUrl: String?,
    val podcastGuid: String?,
    val episodeGuid: String?,
    val positionSeconds: Long?,
    val senderName: String?,
    val message: String?,
    /** `rss::payment` URL from the show's Sovereigncast instance, if any (ADR 0005). */
    val rssPaymentUrl: String? = null,
)

/**
 * The payment router of ADR 0005, one share at a time:
 *
 * - `node` → keysend with the bLIP-10 boostagram, if the wallet can keysend;
 *   otherwise skipped.
 * - `lnaddress` → keysend through the address's `.well-known/keysend` route
 *   when the wallet can (full metadata), else LNURL-pay with a LUD-12
 *   comment — `rss::payment::<action> <url> <message>` when the show's
 *   instance serves the metadata, the plain message otherwise.
 *
 * Shares below one sat are not paid (LNURL works in whole sats, and a
 * keysend of a few msat costs more in fees than it moves).
 */
class BoostSender(
    private val nwc: NwcClient,
    private val lnAddress: LnAddressClient,
    private val wallet: WalletInfo,
) {
    suspend fun send(block: ValueBlock, totalMsat: Long, ctx: BoostContext): List<ShareResult> =
        Splits.divideAt(block, totalMsat, ctx.positionSeconds?.toDouble()).map { share ->
            if (share.amountMsat < 1000) ShareResult.Skipped(share, "below 1 sat")
            else pay(share, totalMsat, ctx)
        }

    private suspend fun pay(share: Share, totalMsat: Long, ctx: BoostContext): ShareResult {
        val r = share.recipient
        return when (r.type.lowercase()) {
            "node" -> if (wallet.canKeysend) keysend(share, r.address, r.customKey, r.customValue, totalMsat, ctx, "keysend")
                else ShareResult.Skipped(share, "wallet cannot send keysend")
            "lnaddress" -> payLnAddress(share, totalMsat, ctx)
            else -> ShareResult.Skipped(share, "unknown recipient type ${r.type}")
        }
    }

    private suspend fun payLnAddress(share: Share, totalMsat: Long, ctx: BoostContext): ShareResult {
        val address = share.recipient.address
        if (wallet.canKeysend) {
            val route = runCatching { lnAddress.keysendInfo(address) }.getOrNull()
            if (route != null) return keysend(share, route.pubkey, route.customKey, route.customValue, totalMsat, ctx, "lnaddress keysend")
        }
        if (!wallet.canPayInvoice) return ShareResult.Skipped(share, "wallet cannot pay invoices")
        return try {
            val msat = share.amountMsat / 1000 * 1000
            val request = lnAddress.payRequest(address)
            val invoice = lnAddress.invoice(request, msat, comment(ctx))
            val paid = nwc.payInvoice(invoice)
            ShareResult.Paid(share.copy(amountMsat = msat), "lnurl", paid.preimage, paid.feesPaidMsat)
        } catch (e: Exception) {
            ShareResult.Failed(share, "lnurl", e.message ?: e::class.simpleName ?: "error")
        }
    }

    private suspend fun keysend(
        share: Share, pubkey: String, customKey: String?, customValue: String?,
        totalMsat: Long, ctx: BoostContext, route: String,
    ): ShareResult = try {
        val boostagram = Boostagram(
            action = ctx.action, appName = "Hopp", appVersion = ctx.appVersion, podcast = ctx.podcast,
            episode = ctx.episode, feedUrl = ctx.feedUrl, podcastGuid = ctx.podcastGuid, episodeGuid = ctx.episodeGuid,
            positionSeconds = ctx.positionSeconds, valueMsat = share.amountMsat, valueMsatTotal = totalMsat,
            recipientName = share.recipient.name, senderName = ctx.senderName, message = ctx.message,
        )
        val paid = nwc.payKeysend(share.amountMsat, pubkey, keysendRecords(boostagram, customKey, customValue))
        ShareResult.Paid(share, route, paid.preimage, paid.feesPaidMsat)
    } catch (e: Exception) {
        ShareResult.Failed(share, route, e.message ?: e::class.simpleName ?: "error")
    }

    private fun comment(ctx: BoostContext): String? {
        val message = ctx.message?.trim().orEmpty()
        return when {
            ctx.rssPaymentUrl != null -> "rss::payment::${ctx.action} ${ctx.rssPaymentUrl} $message".trim()
            message.isNotEmpty() -> message
            else -> null
        }
    }
}

/** For tests and diagnostics: who got what by which route. */
fun List<ShareResult>.describe(): String = joinToString("\n") { r ->
    val who = "${r.share.recipient.name ?: r.share.recipient.address} ${r.share.amountMsat / 1000} sat"
    when (r) {
        is ShareResult.Paid -> "PAID    $who via ${r.route}, fees ${r.feesMsat ?: "?"} msat"
        is ShareResult.Skipped -> "SKIPPED $who: ${r.reason}"
        is ShareResult.Failed -> "FAILED  $who via ${r.route}: ${r.reason}"
    }
}

