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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import space.sovereigncast.hopp.nostr.ExternalNostrSigner
import space.sovereigncast.hopp.nostr.ExternalSigner
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.SignerRejected
import space.sovereigncast.hopp.nostr.SignerUnavailable
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
    /** The signer app's package, for an identity that lives in one (NIP-55). */
    val signerPackage: String? = null,
    /** The profile picture (kind 0 `picture`), https only. */
    val picture: String? = null,
) {
    val npub: String get() = npub(pubkey)
    /** Whether Hopp holds the key — and so is responsible for its backup. */
    val holdsKey: Boolean get() = source == Source.Local

    /** Local: a key Hopp made and keeps. Amber: a signer app on the device keeps it (stage 1b). */
    enum class Source { Local, Amber }
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
    /** A signer app on the device (Android, NIP-55); null where there is none. */
    val externalSigner: ExternalSigner? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _identity = MutableStateFlow(load())
    val identity: StateFlow<NostrIdentity?> = _identity.asStateFlow()

    private fun load(): NostrIdentity? {
        val info = secrets.get(INFO)?.let { runCatching { json.decodeFromString(NostrIdentity.serializer(), it) }.getOrNull() } ?: return null
        // A key made here without its key is no identity; one in a signer app has no key here.
        return info.takeIf { !it.holdsKey || secrets.get(KEY) != null }
    }

    /** Who signs for the listener now, or null without an identity. */
    fun signer(): NostrSigner? {
        val me = _identity.value ?: return null
        return when (me.source) {
            NostrIdentity.Source.Local -> secrets.get(KEY)?.let { runCatching { LocalSigner(KeyPair.fromHex(it)) }.getOrNull() }
            NostrIdentity.Source.Amber -> {
                val app = externalSigner ?: return null
                ExternalNostrSigner(me.pubkey, me.signerPackage ?: return null, app)
            }
        }
    }

    /**
     * Logs in with the signer app (NIP-55): it names the key, Hopp keeps only the
     * public key and the app's package. An identity that exists already has its
     * profile — Hopp reads the name from the relays and never writes it
     * (ADR 0021, 4). Throws [SignerRejected] when the listener says no there.
     */
    suspend fun signInWithSigner(): NostrIdentity {
        val app = externalSigner ?: throw SignerUnavailable("no signer app")
        val (pubkey, signerPackage) = app.login(ExternalSigner.PERMISSIONS)
        secrets.put(KEY, null)
        val me = NostrIdentity(pubkey, NostrIdentity.Source.Amber, null, signerPackage = signerPackage)
        save(me)
        return refreshProfile() ?: me
    }

    /**
     * The profile (kind 0: name, picture) of an identity Hopp did not make. Found
     * the way Nostr clients find one (device feedback 2026-10-16: a listener's
     * profile was on none of Hopp's relays, only on an index): Hopp's relays and
     * two indexes first, which also hold the person's relay list (NIP-65), then
     * the relays that list names as theirs.
     */
    suspend fun refreshProfile(): NostrIdentity? {
        val me = _identity.value ?: return null
        val lookup = DEFAULT_RELAYS + INDEX_RELAYS
        val theirs = publisher.latest(10002, me.pubkey, lookup)?.tags.orEmpty()
            .filter { it.size >= 2 && it[0] == "r" && it[1].startsWith("wss://") && (it.size < 3 || it[2] == "write") }
            .map { it[1].trimEnd('/') }.filter { it !in lookup }.take(MAX_THEIR_RELAYS)
        val profile = publisher.latest(0, me.pubkey, lookup + theirs) ?: return null
        val obj = runCatching { json.parseToJsonElement(profile.content).jsonObject }.getOrNull() ?: return null
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
        val name = text("display_name", "displayName", "name")
        val picture = text("picture")?.takeIf { it.startsWith("https://") }
        if (name == null && picture == null) return null
        // Still the same identity — the listener may have signed out meanwhile.
        if (_identity.value?.pubkey != me.pubkey) return _identity.value
        return me.copy(name = name ?: me.name, picture = picture ?: me.picture).also(::save)
    }

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

    /** The profile and the relay list, to Hopp's relays — only for a key Hopp made. */
    suspend fun publishProfile(): Set<String> {
        val me = _identity.value?.takeIf { it.holdsKey } ?: return emptySet()
        val signer = signer() ?: return emptySet()
        val now = clock.now()
        val profile = signer.sign(UnsignedEvent(0, buildJsonObject { me.name?.let { put("name", it) } }.toString(), emptyList(), now))
        val relays = signer.sign(UnsignedEvent(10002, "", DEFAULT_RELAYS.map { listOf("r", it) }, now))
        // To the indexes too: other clients look a new profile up there.
        val took = publisher.publish(profile, DEFAULT_RELAYS + INDEX_RELAYS)
        publisher.publish(relays, DEFAULT_RELAYS + INDEX_RELAYS)
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

    /** Signs out of Hopp: the key leaves this device (or Hopp forgets the signer
     *  app). Nothing published is touched — on Nostr it stays (ADR 0021, 8). */
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

        /** Relays that collect profiles and relay lists (kinds 0, 3, 10002) to look people up. */
        val INDEX_RELAYS = listOf("wss://purplepag.es", "wss://user.kindpag.es")

        /** A person's own relays asked at most, for one profile. */
        private const val MAX_THEIR_RELAYS = 4
    }
}
