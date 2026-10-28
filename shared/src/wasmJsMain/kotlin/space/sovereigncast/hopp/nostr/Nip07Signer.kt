package space.sovereigncast.hopp.nostr

import kotlinx.coroutines.await
import kotlin.js.Promise

private fun hasNostr(): Boolean = js("typeof window !== 'undefined' && !!window.nostr")
private fun getPublicKey(): Promise<JsString> = js("window.nostr.getPublicKey()")
private fun signEvent(json: String): Promise<JsString> = js("window.nostr.signEvent(JSON.parse(json)).then(e => JSON.stringify(e))")
private fun nip44(encrypt: Boolean, peer: String, text: String): Promise<JsString> =
    js("window.nostr.nip44 ? (encrypt ? window.nostr.nip44.encrypt(peer, text) : window.nostr.nip44.decrypt(peer, text)) : Promise.reject(new Error('this extension has no NIP-44'))")

/**
 * A Nostr extension in the browser (NIP-07: Alby, nos2x …) as Hopp's external
 * signer (ADR 0022, 2): the key stays in the extension, out of the page's
 * reach; Hopp gets signatures and, when sync opens its key, one NIP-44 answer.
 * The extension keeps its own permissions — [interactive] is its to decide.
 */
class Nip07Signer : ExternalSigner {
    override val browserExtension: Boolean get() = true

    override fun available(): Boolean = hasNostr()

    override suspend fun login(permissions: List<SignerPermission>): Pair<String, String> {
        val pubkey = call { getPublicKey() }
        if (!Regex("[0-9a-f]{64}").matches(pubkey)) throw SignerUnavailable("the extension gave no public key")
        return pubkey to ExternalSigner.NIP07_PACKAGE
    }

    override suspend fun signEvent(signerPackage: String, eventJson: String, eventId: String, currentUser: String, interactive: Boolean): SignerResult =
        SignerResult(call { signEvent(eventJson) }, null)

    override suspend fun nip44(signerPackage: String, encrypt: Boolean, text: String, peer: String, currentUser: String, interactive: Boolean): String =
        call { nip44(encrypt, peer, text) }

    /** A JS rejection is no kotlin.Exception (docs/spikes/06-web.md, 3): the listener's "no" becomes [SignerRejected]. */
    private suspend fun call(block: () -> Promise<JsString>): String {
        if (!hasNostr()) throw SignerUnavailable("no Nostr extension in this browser")
        return try {
            block().await<JsString>().toString()
        } catch (e: JsException) {
            throw SignerRejected()
        }
    }
}
