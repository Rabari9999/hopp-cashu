package space.sovereigncast.hopp.value

import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.model.ValueBlock
import space.sovereigncast.hopp.model.ValueRecipient
import space.sovereigncast.hopp.value.nwc.NwcClient
import space.sovereigncast.hopp.value.nwc.NwcException
import space.sovereigncast.hopp.value.nwc.WalletInfo

/** What happened to one recipient's share. */
sealed interface ShareResult {
    val share: Share
    data class Paid(override val share: Share, val route: String, val preimage: String?, val feesMsat: Long?) : ShareResult
    /** Not attempted — ADR 0005: shown to the listener, neither paid nor redistributed. */
    data class Skipped(override val share: Share, val reason: String) : ShareResult
    data class Failed(
        override val share: Share, val route: String, val reason: String,
        /** Sent, but no answer said whether it was paid: never sent again — not by
         *  another route, not by a later streaming batch (security report 2026-10-10, H-04). */
        val outcomeUnknown: Boolean = false,
    ) : ShareResult
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
    /** For a Sovereigncast show: stores a share's boost metadata at the
     *  instance and returns the `rss::payment::…` comment prefix (ADR 0005). */
    private val metadata: (suspend (Share, Long, BoostContext) -> String?)? = null,
) {
    suspend fun send(block: ValueBlock, totalMsat: Long, ctx: BoostContext, remote: ValueBlock? = null): List<ShareResult> =
        Splits.divideAtWholeSats(block, totalMsat, ctx.positionSeconds?.toDouble(), remote).map { share -> payShare(share, totalMsat, ctx) }

    /** One recipient's share — streaming settles them one by one. */
    suspend fun payShare(share: Share, totalMsat: Long, given: BoostContext): ShareResult {
        if (share.amountMsat < 1000) return ShareResult.Skipped(share, SkipReason.DUST)
        val r = share.recipient
        if (isSelf(r, wallet)) return ShareResult.Skipped(share, SkipReason.SELF)
        // "Support Hopp" gets the amount and nothing about the listener: no
        // show, episode, position, name or message, and no metadata stored
        // at an instance for it — as the privacy page says (ADR 0015).
        val ctx = if (isSupport(r)) given.anonymous() else given
        return when (r.type.lowercase()) {
            Splits.REMOTE -> ShareResult.Skipped(share, SkipReason.REMOTE)
            "node" -> if (wallet.canKeysend) keysend(share, r.address, r.customKey, r.customValue, totalMsat, ctx, "keysend")
                else ShareResult.Skipped(share, SkipReason.KEYSEND)
            "lnaddress" -> payLnAddress(share, totalMsat, ctx)
            else -> ShareResult.Skipped(share, "unknown recipient type ${r.type}")
        }
    }

    /**
     * A Lightning address: by its keysend route when the wallet can (full
     * boostagram), and if that fails — Alby's shared node behind its older
     * addresses no longer forwards everywhere — by LNURL-pay after all
     * (device feedback 2026-10-08: such shares failed although an invoice
     * would have gone through).
     */
    private suspend fun payLnAddress(share: Share, totalMsat: Long, ctx: BoostContext): ShareResult {
        val address = share.recipient.address
        var keysendFailure: String? = null
        if (wallet.canKeysend) {
            val route = runCatching { lnAddress.keysendInfo(address) }.getOrNull()
            if (route != null) {
                val result = keysend(share, route.pubkey, route.customKey, route.customValue, totalMsat, ctx, "lnaddress keysend")
                // A keysend that may have arrived is not followed by an invoice.
                if (result !is ShareResult.Failed || result.outcomeUnknown || !wallet.canPayInvoice) return result
                keysendFailure = result.reason
            }
        }
        if (!wallet.canPayInvoice) return ShareResult.Skipped(share, SkipReason.INVOICE)
        val route = if (keysendFailure != null) "lnurl after keysend failed" else "lnurl"
        return try {
            val msat = share.amountMsat / 1000 * 1000
            val request = lnAddress.payRequest(address)
            // The instance keeps the full boostagram behind a URL; the comment
            // only has to carry that and the message.
            val prefix = if (ctx.rssPaymentUrl == null && !isSupport(share.recipient)) runCatching { metadata?.invoke(share, totalMsat, ctx) }.getOrNull() else null
            val invoice = lnAddress.invoice(request, msat, comment(ctx, prefix))
            val paid = nwc.payInvoice(invoice)
            ShareResult.Paid(share.copy(amountMsat = msat), route, paid.preimage, paid.feesPaidMsat)
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName ?: "error"
            ShareResult.Failed(share, route, keysendFailure?.let { "$reason (keysend: $it)" } ?: reason, outcomeUnknown = (e as? NwcException)?.outcomeUnknown == true)
        }
    }

    private fun isSupport(r: ValueRecipient) =
        r.fee && r.type.equals("lnaddress", ignoreCase = true) && r.address.equals(Settings.SUPPORT_ADDRESS, ignoreCase = true)

    private fun BoostContext.anonymous() = BoostContext(action, appVersion, null, null, null, null, null, null, null, null, null)

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
        ShareResult.Failed(share, route, e.message ?: e::class.simpleName ?: "error", outcomeUnknown = (e as? NwcException)?.outcomeUnknown == true)
    }

    private fun comment(ctx: BoostContext, prefix: String? = null): String? {
        val message = ctx.message?.trim().orEmpty()
        return when {
            prefix != null -> "$prefix $message".trim()
            ctx.rssPaymentUrl != null -> "rss::payment::${ctx.action} ${ctx.rssPaymentUrl} $message".trim()
            message.isNotEmpty() -> message
            else -> null
        }
    }
}

/** Why a share was not paid — the texts the UI translates. */
object SkipReason {
    const val DUST = "below 1 sat"
    const val SELF = "paying yourself"
    const val KEYSEND = "wallet cannot send keysend"
    const val INVOICE = "wallet cannot pay invoices"
    const val REMOTE = "the remote item's value block could not be found"
}

/**
 * The recipient is the paying wallet itself. Only its Lightning address says
 * so: a node key does not — sub-wallets on one node (Alby Hub, LNbits …) share
 * it and are told apart by the recipient's customKey/customValue, so a share
 * to the wallet's own node may well be another wallet's (device feedback
 * 2026-10-08). Should it really be this wallet, the wallet refuses and the
 * listener sees its reason.
 */
fun isSelf(recipient: space.sovereigncast.hopp.model.ValueRecipient, wallet: WalletInfo): Boolean =
    recipient.type.equals("lnaddress", ignoreCase = true) && wallet.ownAddress != null &&
        recipient.address.trim().equals(wallet.ownAddress.trim(), ignoreCase = true)

/** Why a share cannot be paid with this wallet, before trying (ADR 0005:
 *  shown in the boost dialog, neither paid nor handed to the others). */
fun unpayableReason(recipient: space.sovereigncast.hopp.model.ValueRecipient, wallet: WalletInfo): String? = if (isSelf(recipient, wallet)) "self" else when (recipient.type.lowercase()) {
    "node" -> if (wallet.canKeysend) null else "keysend"
    "lnaddress" -> if (wallet.canPayInvoice || wallet.canKeysend) null else "invoice"
    Splits.REMOTE -> "remote"
    else -> "type"
}

/** For tests and diagnostics: who got what by which route. */
fun List<ShareResult>.describe(): String = joinToString("\n") { r ->
    val who = "${r.share.recipient.name ?: r.share.recipient.address} ${r.share.amountMsat / 1000} sat"
    when (r) {
        is ShareResult.Paid -> "PAID    $who via ${r.route}, fees ${r.feesMsat ?: "?"} msat"
        is ShareResult.Skipped -> "SKIPPED $who: ${r.reason}"
        is ShareResult.Failed -> (if (r.outcomeUnknown) "UNKNOWN $who via ${r.route}: ${r.reason}" else "FAILED  $who via ${r.route}: ${r.reason}")
    }
}

