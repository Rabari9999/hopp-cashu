package space.sovereigncast.hopp.comments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import space.sovereigncast.hopp.crypto.Aes
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.secureRandomBytes
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.sharedX
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

/** Who a zap names (device feedback 2026-10-19, as in Amethyst): everyone sees the sender;
 *  only the author does, with the message; nobody does. */
enum class ZapPrivacy { Public, Private, Anonymous }

/** Where a comment's author takes zaps: the Lightning address in their profile and its pay request. */
data class ZapTarget(val pubkey: String, val address: String, val request: PayRequest)

/**
 * Zapping a comment (NIP-57; device feedback 2026-10-19), beside liking and
 * answering it: the author's Lightning address from their profile (`lud16`),
 * a zap request (kind 9734) naming the comment, and the invoice its server
 * gives for it paid from the connected wallet, within today's budget. The
 * server publishes the receipt (kind 9735) to the relays the comments came
 * from, so every client — Hopp too — counts the sats on the comment.
 * [ZapPrivacy] as in Amethyst (device feedback 2026-10-19): public — signed
 * with the listener's identity; anonymous (and always without an identity) —
 * a key made for this zap alone and the `anon` tag, so no client shows who
 * sent it; private — the same outside, and inside the `anon` tag the request
 * signed by the listener (kind 9733), encrypted for the author alone
 * (AES-256-CBC under the ECDH secret, as NIP-04; `pzap1…_iv1…`). The key it
 * is sent with is Amethyst's, derived from the listener's own, where Hopp
 * holds that; with a signer app a random one — the author can open it all
 * the same, only the listener's other apps cannot.
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
        podcastId: Long?, episodeId: Long?, recipientName: String?, privacy: ZapPrivacy = ZapPrivacy.Public,
    ): Long? {
        val msat = sats * 1000
        val text = message.trim()
        return value.zap(podcastId, episodeId, msat, text.takeIf { it.isNotEmpty() }, recipientName, target.address) {
            // Signed only once the budget allows it — a signer app asks the listener.
            val at = now()
            val tags = listOf(
                listOf("relays") + (relays.take(MAX_RECEIPT_RELAYS - 1) + FOUNTAIN).distinct(),
                listOf("amount", "$msat"),
                listOf("p", comment.pubkey),
                listOf("e", comment.id),
                listOf("k", "${comment.kind}"),
            )
            val own = signer()
            val request = when {
                own != null && privacy == ZapPrivacy.Public -> own.sign(UnsignedEvent(ZAP_REQUEST, text, tags, at))
                own != null && privacy == ZapPrivacy.Private -> {
                    val inner = own.sign(UnsignedEvent(PRIVATE_ZAP, text, tags, at))
                    val outer = (own as? LocalSigner)?.privateZapKey(comment.id, at) ?: KeyPair.generate()
                    val sealed = sealPrivateZap(Json.encodeToString(Event.serializer(), inner), outer, comment.pubkey)
                    Event.sign(outer, ZAP_REQUEST, "", tags + listOf(listOf("anon", sealed)), at)
                }
                else -> Event.sign(KeyPair.generate(), ZAP_REQUEST, text, tags + listOf(listOf("anon")), at)
            }
            lnAddress.zapInvoice(target.request, msat, Json.encodeToString(Event.serializer(), request))
        }
    }

    companion object {
        const val ZAP_REQUEST = 9734
        /** The request inside a private zap's `anon` tag. */
        const val PRIVATE_ZAP = 9733
        private const val MAX_RECEIPT_RELAYS = 5
        private const val FOUNTAIN = "wss://relay.fountain.fm"
    }
}

/** A private zap's `anon` value: [json] for [recipient] alone, as Amethyst writes and reads it. */
internal fun sealPrivateZap(json: String, sender: KeyPair, recipient: String, iv: ByteArray = secureRandomBytes(16)): String {
    val secret = sharedX(sender.secretKey, recipient.hexToBytes())
    val sealed = Aes.encryptCbc(secret, iv, json.encodeToByteArray())
    return Bech32.encode("pzap", sealed) + "_" + Bech32.encode("iv", iv)
}
