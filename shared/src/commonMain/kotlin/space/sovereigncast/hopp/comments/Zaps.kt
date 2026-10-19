package space.sovereigncast.hopp.comments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.LocalSigner
import space.sovereigncast.hopp.nostr.NostrSigner
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.UnsignedEvent
import space.sovereigncast.hopp.value.LnAddressClient
import space.sovereigncast.hopp.value.PayRequest
import space.sovereigncast.hopp.value.ValueService
import kotlin.time.Duration.Companion.milliseconds

/** Where a comment's author takes zaps: the Lightning address in their profile and its pay request. */
data class ZapTarget(val pubkey: String, val address: String, val request: PayRequest)

/**
 * Zapping a comment (NIP-57; device feedback 2026-10-19), beside liking and
 * answering it: the author's Lightning address from their profile (`lud16`),
 * a zap request (kind 9734) naming the comment, and the invoice its server
 * gives for it paid from the connected wallet, within today's budget. The
 * server publishes the receipt (kind 9735) to the relays the comments came
 * from, so every client — Hopp too — counts the sats on the comment.
 * Signed with the listener's identity; without one, with a key made for this
 * zap alone — an anonymous zap.
 */
class CommentZapper(
    private val publisher: RelayPublisher,
    /** The listener's signer; null without an identity. */
    private val signer: () -> NostrSigner?,
    private val lnAddress: LnAddressClient,
    private val value: ValueService,
    private val now: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The author's zap address — null when their profile names none, or its server takes no zaps. */
    suspend fun target(pubkey: String, relays: List<String>): ZapTarget? {
        val profile = publisher.latest(0, pubkey, (relays + IdentityRepository.INDEX_RELAYS + FOUNTAIN).distinct(), 2000.milliseconds) ?: return null
        val address = runCatching { json.parseToJsonElement(profile.content).jsonObject["lud16"] }.getOrNull()
            .let { it as? JsonPrimitive }?.contentOrNull?.trim()?.takeIf { '@' in it } ?: return null
        val request = runCatching { lnAddress.payRequest(address) }.getOrNull()?.takeIf { it.nostrPubkey != null } ?: return null
        return ZapTarget(pubkey, address, request)
    }

    /** Zaps [comment] with [sats]; returns the routing fee if the wallet says. */
    suspend fun zap(
        comment: Comment, target: ZapTarget, sats: Long, message: String, relays: List<String>,
        podcastId: Long?, episodeId: Long?, recipientName: String?,
    ): Long? {
        val msat = sats * 1000
        val text = message.trim()
        return value.zap(podcastId, episodeId, msat, text.takeIf { it.isNotEmpty() }, recipientName, target.address) {
            // Signed only once the budget allows it — a signer app asks the listener.
            val request = (signer() ?: LocalSigner(KeyPair.generate())).sign(UnsignedEvent(ZAP_REQUEST, text, listOf(
                listOf("relays") + (relays.take(MAX_RECEIPT_RELAYS - 1) + FOUNTAIN).distinct(),
                listOf("amount", "$msat"),
                listOf("p", comment.pubkey),
                listOf("e", comment.id),
                listOf("k", "${comment.kind}"),
            ), now()))
            lnAddress.zapInvoice(target.request, msat, Json.encodeToString(Event.serializer(), request))
        }
    }

    companion object {
        const val ZAP_REQUEST = 9734
        private const val MAX_RECEIPT_RELAYS = 5
        private const val FOUNTAIN = "wss://relay.fountain.fm"
    }
}
