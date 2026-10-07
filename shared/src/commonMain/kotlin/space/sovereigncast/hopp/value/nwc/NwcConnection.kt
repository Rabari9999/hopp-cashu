package space.sovereigncast.hopp.value.nwc

import space.sovereigncast.hopp.nostr.KeyPair

/**
 * A Nostr Wallet Connect URI (NIP-47):
 * `nostr+walletconnect://<wallet pubkey>?relay=wss://…&secret=<hex>[&lud16=…]`.
 * The secret is the client key Hopp signs and encrypts with — it lives in the
 * Keystore/Keychain, never in the database (ADR 0005).
 */
data class NwcConnection(
    val walletPubkey: String,
    val relays: List<String>,
    val clientKeys: KeyPair,
    val lud16: String? = null,
) {
    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun parse(uri: String): NwcConnection {
            val trimmed = uri.trim()
            val rest = listOf("nostr+walletconnect://", "nostrwalletconnect://", "nostr+walletconnect:")
                .firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
                ?.let { trimmed.substring(it.length) }
                ?: throw IllegalArgumentException("not a nostr+walletconnect URI")
            val pubkey = rest.substringBefore('?').trim('/').lowercase()
            require(HEX64.matches(pubkey)) { "wallet pubkey must be 64 hex characters" }
            val params = rest.substringAfter('?', "").split('&').filter { '=' in it }.map {
                it.substringBefore('=') to percentDecode(it.substringAfter('='))
            }
            val relays = params.filter { it.first == "relay" }.map { it.second }
            require(relays.isNotEmpty()) { "no relay in the URI" }
            val secret = params.firstOrNull { it.first == "secret" }?.second?.lowercase()
            require(secret != null && HEX64.matches(secret)) { "secret must be 64 hex characters" }
            return NwcConnection(pubkey, relays, KeyPair.fromHex(secret), params.firstOrNull { it.first == "lud16" }?.second)
        }

        private fun percentDecode(s: String): String {
            if ('%' !in s && '+' !in s) return s
            val out = mutableListOf<Byte>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '%' && i + 2 < s.length -> {
                        out += s.substring(i + 1, i + 3).toInt(16).toByte(); i += 3
                    }
                    c == '+' -> { out += ' '.code.toByte(); i++ }
                    else -> { out += c.toString().encodeToByteArray().toList(); i++ }
                }
            }
            return out.toByteArray().decodeToString()
        }
    }
}
