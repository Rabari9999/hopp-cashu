package space.sovereigncast.hopp.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.data.Clock
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.LocalSigner
import space.sovereigncast.hopp.nostr.Nip49
import space.sovereigncast.hopp.nostr.NostrSigner
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.UnsignedEvent
import space.sovereigncast.hopp.nostr.npub
import space.sovereigncast.hopp.platform.SecretStore

/** The listener's Nostr identity as Hopp shows it (ADR 0006/0021). */
@Serializable
data class NostrIdentity(
    val pubkey: String,
    val source: Source = Source.Local,
    /** The profile name (kind 0) — what Hopp greets with and signs boosts with. */
    val name: String? = null,
    /** Whether the key made in Hopp has been exported once (ADR 0021, 9). */
    val exported: Boolean = false,
) {
    val npub: String get() = npub(pubkey)

    enum class Source { Local }
}

/**
 * The optional Nostr identity (ADR 0021, stage 1). Without one, nothing
 * changes: Hopp stays account-free. A key made here lives only in the
 * platform's secret store, outside every backup (ADR 0015) — so its export is
 * part of making it.
 */
class IdentityRepository(
    private val secrets: SecretStore,
    private val publisher: RelayPublisher,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _identity = MutableStateFlow(load())
    val identity: StateFlow<NostrIdentity?> = _identity.asStateFlow()

    private fun load(): NostrIdentity? {
        if (secrets.get(KEY) == null) return null
        return secrets.get(INFO)?.let { runCatching { json.decodeFromString(NostrIdentity.serializer(), it) }.getOrNull() }
    }

    /** Who signs for the listener now, or null without an identity. */
    fun signer(): NostrSigner? = secrets.get(KEY)?.let { runCatching { LocalSigner(KeyPair.fromHex(it)) }.getOrNull() }

    /**
     * A new key with a profile: kind 0 with the name, kind 10002 with Hopp's
     * relays. Published best effort — the identity exists either way, and
     * [publishProfile] can try again. Returns the relays that took the profile.
     */
    suspend fun create(name: String): Set<String> {
        val keys = KeyPair.generate()
        secrets.put(KEY, keys.secretKey.toHex())
        save(NostrIdentity(keys.publicKeyHex, NostrIdentity.Source.Local, name.trim().ifEmpty { null }))
        return publishProfile()
    }

    /** The profile and the relay list, to Hopp's relays. */
    suspend fun publishProfile(): Set<String> {
        val signer = signer() ?: return emptySet()
        val me = _identity.value ?: return emptySet()
        val now = clock.now()
        val profile = signer.sign(UnsignedEvent(0, buildJsonObject { me.name?.let { put("name", it) } }.toString(), emptyList(), now))
        val relays = signer.sign(UnsignedEvent(10002, "", DEFAULT_RELAYS.map { listOf("r", it) }, now))
        val took = publisher.publish(profile, DEFAULT_RELAYS)
        publisher.publish(relays, DEFAULT_RELAYS)
        return took
    }

    /** NIP-49 with [password]; scrypt takes a moment, so off the main thread. */
    suspend fun export(password: String): String {
        val secret = secrets.get(KEY)?.hexToBytes() ?: error("no key")
        val out = withContext(Dispatchers.Default) { Nip49.encrypt(secret, password) }
        secret.fill(0)
        _identity.value?.let { save(it.copy(exported = true)) }
        return out
    }

    /** Signs out of Hopp: the key leaves this device. Nothing published is
     *  touched — on Nostr it stays (ADR 0021, 8). */
    fun signOut() {
        secrets.put(KEY, null)
        secrets.put(INFO, null)
        _identity.value = null
    }

    private fun save(identity: NostrIdentity) {
        secrets.put(INFO, json.encodeToString(NostrIdentity.serializer(), identity))
        _identity.value = identity
    }

    companion object {
        private const val KEY = "nostr_identity_key"
        private const val INFO = "nostr_identity"

        /** For a key made in Hopp, which has no relays of its own yet (ADR 0021, 2). */
        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")
    }
}
