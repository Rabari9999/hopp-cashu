package space.sovereigncast.hopp.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds
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
import space.sovereigncast.hopp.nostr.BunkerSigner
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.BunkerUri
import space.sovereigncast.hopp.nostr.ExternalNostrSigner
import space.sovereigncast.hopp.nostr.Nip46Client
import space.sovereigncast.hopp.nostr.NostrConnectOffer
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
import space.sovereigncast.hopp.platform.SecretUnreadable

/** A pasted key that cannot be used. */
class KeyImportException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { Unreadable, NeedsPassword, WrongPassword }
}

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
    /** A remote signer's key and relays, for an identity behind a bunker (NIP-46). */
    val remoteSigner: String? = null,
    val signerRelays: List<String> = emptyList(),
    /** The listener's relays (NIP-65) as last read or set; empty: not known yet. */
    val relays: List<RelayEntry> = emptyList(),
) {
    val npub: String get() = npub(pubkey)
    /** Whether Hopp holds the key — and so is responsible for its backup. */
    val holdsKey: Boolean get() = source == Source.Local

    /** Local: a key Hopp made and keeps. Amber: a signer app on the device keeps it (stage 1b).
     *  Bunker: a remote signer keeps it (NIP-46, stage 1c) — on iOS the only external one. */
    enum class Source { Local, Amber, Bunker }
}

/** One relay of the listener's list (NIP-65 `r` tag): read from, written to, or both. */
@Serializable
data class RelayEntry(val url: String, val read: Boolean = true, val write: Boolean = true)

/** A relay to add that is no `wss://` address. */
class BadRelayAddress : Exception("not a wss:// relay address")

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
    private val _unreadable = MutableStateFlow(false)
    /**
     * The secret store holds an identity it could not give up ([SecretUnreadable]:
     * the platform's key store not answering). There is one — none is made or
     * imported in its place; [signOut] is the one way past it (security review
     * 2026-10-20: read as "none", a new key replaced the old one for good).
     */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()
    private val _identity = MutableStateFlow(load())
    val identity: StateFlow<NostrIdentity?> = _identity.asStateFlow()

    private fun load(): NostrIdentity? = try {
        val info = secrets.get(INFO)?.let { runCatching { json.decodeFromString(NostrIdentity.serializer(), it) }.getOrNull() }
        // A key made here without its key is no identity; one in a signer app has no key here.
        info?.takeIf { !it.holdsKey || secrets.get(KEY) != null }
    } catch (e: SecretUnreadable) {
        _unreadable.value = true
        null
    }

    private fun requireReadable() { if (_unreadable.value) throw SecretUnreadable(KEY) }

    /** Who signs for the listener now, or null without an identity. Not [interactive]:
     *  a signer app is never opened, only what the listener allowed for good is asked. */
    fun signer(interactive: Boolean = true): NostrSigner? {
        val me = _identity.value ?: return null
        return when (me.source) {
            NostrIdentity.Source.Local -> runCatching { secrets.get(KEY) }.getOrNull()?.let { runCatching { LocalSigner(KeyPair.fromHex(it)) }.getOrNull() }
            NostrIdentity.Source.Amber -> {
                val app = externalSigner ?: return null
                ExternalNostrSigner(me.pubkey, me.signerPackage ?: return null, app, interactive)
            }
            NostrIdentity.Source.Bunker -> bunkerClient(me)?.let { BunkerSigner(me.pubkey, it) }
        }
    }

    private fun bunkerClient(me: NostrIdentity, onAuthUrl: (String) -> Unit = {}): Nip46Client? {
        val keys = secrets.get(BUNKER_KEY)?.let { runCatching { KeyPair.fromHex(it) }.getOrNull() } ?: return null
        return Nip46Client(keys, me.remoteSigner ?: return null, me.signerRelays, publisher.connector, { clock.now() }, onAuthUrl = onAuthUrl)
    }

    /**
     * Connects to a remote signer from its `bunker://` address (NIP-46): Hopp
     * makes a key of its own for the conversation only, asks the signer for
     * standing permission to sign NIP-98, learns its relays and the listener's
     * key. [onAuthUrl]: the signer wants the listener to confirm on a page.
     */
    suspend fun signInWithBunker(address: String, onAuthUrl: (String) -> Unit = {}): NostrIdentity {
        val bunker = BunkerUri.parse(address)
        val keys = KeyPair.generate()
        val client = Nip46Client(keys, bunker.remoteSignerPubkey, bunker.relays, publisher.connector, { clock.now() }, onAuthUrl = onAuthUrl)
        client.connect(bunker.secret, BUNKER_PERMS)
        return finishBunker(keys, client)
    }

    /** A `nostrconnect://` code for the listener's signer — the other way round. */
    fun nostrConnectOffer(): NostrConnectOffer = NostrConnectOffer.create(CONNECT_RELAYS, BUNKER_PERMS)

    /** Waits until the signer takes up [offer], then signs in as with a bunker address. */
    suspend fun signInWithNostrConnect(offer: NostrConnectOffer): NostrIdentity =
        finishBunker(offer.clientKeys, Nip46Client.awaitConnect(offer, publisher.connector, { clock.now() }))

    private suspend fun finishBunker(keys: KeyPair, client: Nip46Client): NostrIdentity {
        requireReadable()
        client.switchRelays()
        val pubkey = client.getPublicKey()
        secrets.put(KEY, null)
        secrets.put(BUNKER_KEY, keys.secretKey.toHex())
        val me = NostrIdentity(pubkey, NostrIdentity.Source.Bunker, null, remoteSigner = client.remoteSignerPubkey, signerRelays = client.relays)
        save(me)
        return refreshProfile() ?: me
    }

    /** Tells a remote signer the session is over — a courtesy, never waited on long (NIP-46). */
    suspend fun endRemoteSession() {
        val me = _identity.value?.takeIf { it.source == NostrIdentity.Source.Bunker } ?: return
        bunkerClient(me)?.logout()
    }

    /**
     * Logs in with the signer app (NIP-55): it names the key, Hopp keeps only the
     * public key and the app's package. An identity that exists already has its
     * profile — Hopp reads the name from the relays and never writes it
     * (ADR 0021, 4). Throws [SignerRejected] when the listener says no there.
     */
    suspend fun signInWithSigner(): NostrIdentity {
        requireReadable()
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
        val list = relayEntries(publisher.latest(10002, me.pubkey, lookup))
        // Their own relays from now on — an imported key's too (device feedback 2026-10-19).
        if (list.isNotEmpty()) _identity.value?.takeIf { it.pubkey == me.pubkey }?.let { save(it.copy(relays = list)) }
        val theirs = list.filter { it.write }.map { it.url }.filter { it !in lookup }.take(MAX_THEIR_RELAYS)
        val profile = publisher.latest(0, me.pubkey, lookup + theirs) ?: return null
        val obj = runCatching { json.parseToJsonElement(profile.content).jsonObject }.getOrNull() ?: return null
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
        val name = text("display_name", "displayName", "name")
        val picture = text("picture")?.takeIf { it.startsWith("https://") }
        if (name == null && picture == null) return null
        // Still the same identity — the listener may have signed out meanwhile.
        if (_identity.value?.pubkey != me.pubkey) return _identity.value
        val now = _identity.value ?: me
        return now.copy(name = name ?: now.name, picture = picture ?: now.picture).also(::save)
    }

    /**
     * A new key with a profile: kind 0 with the name, kind 10002 with Hopp's
     * relays. Published best effort — the identity exists either way, and
     * [publishProfile] can try again. Returns the relays that took the profile.
     */
    suspend fun create(name: String): Set<String> {
        requireReadable()
        val keys = KeyPair.generate()
        secrets.put(KEY, keys.secretKey.toHex())
        save(NostrIdentity(keys.publicKeyHex, NostrIdentity.Source.Local, name.trim().ifEmpty { null }, relays = DEFAULT_RELAYS.map { RelayEntry(it) }))
        return publishProfile()
    }

    /** The profile and the relay list, to Hopp's relays — only for a key Hopp made. */
    suspend fun publishProfile(): Set<String> {
        val me = _identity.value?.takeIf { it.holdsKey } ?: return emptySet()
        val signer = signer() ?: return emptySet()
        val now = clock.now()
        val profile = signer.sign(UnsignedEvent(0, buildJsonObject { me.name?.let { put("name", it) } }.toString(), emptyList(), now))
        // The listener's own list once there is one — published again, never overwritten with Hopp's.
        val list = me.relays.ifEmpty { DEFAULT_RELAYS.map { RelayEntry(it) } }
        val relays = signer.sign(UnsignedEvent(10002, "", tags(list), now))
        val to = (list.filter { it.write }.map { it.url } + DEFAULT_RELAYS + INDEX_RELAYS).distinct()
        // To the indexes too: other clients look a new profile up there.
        val took = publisher.publish(profile, to)
        publisher.publish(relays, to)
        return took
    }

    /**
     * The listener's relays (NIP-65): as kept, else looked up — the person's
     * list where they have one, Hopp's three where not. Not kept when nothing
     * answered, so the next call asks again.
     */
    suspend fun relays(): List<RelayEntry> {
        val me = _identity.value ?: return emptyList()
        if (me.relays.isNotEmpty()) return me.relays
        val found = relayEntries(publisher.latest(10002, me.pubkey, DEFAULT_RELAYS + INDEX_RELAYS, 2000.milliseconds))
        if (found.isNotEmpty()) _identity.value?.takeIf { it.pubkey == me.pubkey }?.let { save(it.copy(relays = found)) }
        return found.ifEmpty { DEFAULT_RELAYS.map { RelayEntry(it) } }
    }

    /** Where the listener's events go: their write relays. */
    suspend fun writeRelays(): List<String> = relays().filter { it.write }.map { it.url }.take(MAX_WRITE_RELAYS)

    /** Where the listener reads, as kept — for comments asked of them too; nothing until known. */
    fun readRelays(): List<String> = _identity.value?.relays.orEmpty().filter { it.read }.map { it.url }.take(MAX_THEIR_RELAYS)

    /** Whether [url] answers at all — before it goes onto the list. */
    suspend fun reachable(url: String): Boolean = publisher.reachable(url)

    /** [url] onto the listener's list, for reading and writing; [BadRelayAddress] for anything but wss://. */
    suspend fun addRelay(url: String): List<RelayEntry> {
        val clean = relayAddress(url) ?: throw BadRelayAddress()
        return changeRelays { list -> if (list.any { it.url == clean }) list else list + RelayEntry(clean) }
    }

    /** [url] off the list — never the last one written to. */
    suspend fun removeRelay(url: String): List<RelayEntry> = changeRelays { list ->
        val next = list.filterNot { it.url == url }
        if (next.none { it.write }) list else next
    }

    private val relayLock = kotlinx.coroutines.sync.Mutex()

    /**
     * The list (kind 10002) changed and published, as the mute list is
     * (ADR 0021): the newest one read first — every relay asked to the end
     * when the quick look finds none, as a slow relay may hold it — so what
     * another client set is kept. Published to the relays before and after
     * the change and to the indexes, where other clients look it up.
     */
    private suspend fun changeRelays(change: (List<RelayEntry>) -> List<RelayEntry>): List<RelayEntry> = relayLock.withLock {
        val me = _identity.value ?: throw space.sovereigncast.hopp.comments.NoIdentity()
        val signer = signer() ?: throw space.sovereigncast.hopp.comments.NoIdentity()
        val lookup = (me.relays.map { it.url } + DEFAULT_RELAYS + INDEX_RELAYS).distinct()
        val quick = publisher.latestChecked(10002, me.pubkey, lookup, 3000.milliseconds)
        val read = if (quick.event != null) quick else publisher.latestChecked(10002, me.pubkey, lookup)
        val current = read.event
        // Found nowhere, and a relay did not answer: the list may be there, newer
        // than what Hopp kept — not written over from the local copy (security
        // audit 2026-10-20, M-08).
        if (current == null && !read.complete) throw space.sovereigncast.hopp.comments.ListNotRead()
        val before = relayEntries(current).ifEmpty { me.relays }.ifEmpty { DEFAULT_RELAYS.map { RelayEntry(it) } }
        val after = change(before)
        if (after == before && current != null) return@withLock after.also { list -> _identity.value?.takeIf { it.pubkey == me.pubkey }?.let { save(it.copy(relays = list)) } }
        val event = signer.sign(UnsignedEvent(10002, current?.content.orEmpty(), tags(after), clock.now()))
        val to = ((before + after).filter { it.write }.map { it.url } + INDEX_RELAYS).distinct()
        if (publisher.publish(event, to).isEmpty()) throw space.sovereigncast.hopp.comments.NotPublished()
        _identity.value?.takeIf { it.pubkey == me.pubkey }?.let { save(it.copy(relays = after)) }
        after
    }

    /** The key Hopp holds, as NIP-19 nsec — for the password manager or another app; null for a key held elsewhere. */
    fun nsec(): String? = _identity.value?.takeIf { it.holdsKey }?.let { runCatching { secrets.get(KEY) }.getOrNull() }?.let { Bech32.encode("nsec", it.hexToBytes()) }

    /** The key is kept somewhere beyond this device: the backup reminder is done. */
    fun markBackedUp() { _identity.value?.let { if (!it.exported) save(it.copy(exported = true)) } }

    /**
     * An existing key, pasted or from the password manager: `nsec1…`, or a
     * NIP-49 `ncryptsec1…` with its [password]. It is kept like a key made
     * here; the profile is read from the relays, never written. Counts as
     * backed up — it came from somewhere.
     */
    suspend fun importKey(text: String, password: String? = null): NostrIdentity {
        requireReadable()
        val t = text.trim().removePrefix("nostr:")
        val secret = when {
            t.startsWith("nsec1") -> runCatching { Bech32.decode(t) }.getOrNull()?.takeIf { it.first == "nsec" && it.second.size == 32 }?.second
                ?: throw KeyImportException(KeyImportException.Reason.Unreadable)
            t.startsWith("ncryptsec1") -> {
                if (password.isNullOrEmpty()) throw KeyImportException(KeyImportException.Reason.NeedsPassword)
                withContext(Dispatchers.Default) {
                    try { Nip49.decrypt(t, password) } catch (e: space.sovereigncast.hopp.nostr.Nip49Exception) {
                        // Only a wrong password is one; a bad or too strong ncryptsec is unreadable (audit 2026-10-20, L-01).
                        throw KeyImportException(if (e.message == "wrong password") KeyImportException.Reason.WrongPassword else KeyImportException.Reason.Unreadable)
                    }
                }
            }
            else -> throw KeyImportException(KeyImportException.Reason.Unreadable)
        }
        val keys = runCatching { KeyPair(secret) }.getOrElse { throw KeyImportException(KeyImportException.Reason.Unreadable) }
        secrets.put(BUNKER_KEY, null)
        secrets.put(KEY, secret.toHex())
        val me = NostrIdentity(keys.publicKeyHex, NostrIdentity.Source.Local, null, exported = true)
        save(me)
        return refreshProfile() ?: me
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
        secrets.put(BUNKER_KEY, null)
        secrets.put(INFO, null)
        _identity.value = null
        _unreadable.value = false
    }

    private fun save(identity: NostrIdentity) {
        secrets.put(INFO, json.encodeToString(NostrIdentity.serializer(), identity))
        _identity.value = identity
    }

    companion object {
        private const val KEY = "nostr_identity_key"
        private const val INFO = "nostr_identity"
        /** Hopp's own key for talking to a remote signer — not the listener's. */
        private const val BUNKER_KEY = "nostr_bunker_client_key"

        /** What Hopp asks a remote signer to allow for good: NIP-98 for votes and suggestions,
         *  since stage 3 comments, likes, the mute list and reports. Never encryption — see
         *  [ExternalSigner.PERMISSIONS]. */
        val BUNKER_PERMS = ExternalSigner.SIGNED_KINDS.joinToString(",") { "sign_event:$it" }

        /** Where Hopp waits for a signer that takes up a nostrconnect:// code. */
        val CONNECT_RELAYS = listOf("wss://relay.nsec.app", "wss://nos.lol")

        /** For a key made in Hopp, which has no relays of its own yet (ADR 0021, 2). */
        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")

        /** Relays that collect profiles and relay lists (kinds 0, 3, 10002) to look people up. */
        val INDEX_RELAYS = listOf("wss://purplepag.es", "wss://user.kindpag.es")

        /** A person's own relays asked at most, for one profile. */
        private const val MAX_THEIR_RELAYS = 4
        /** Write relays an event goes to at most, besides the ones it needs to reach. */
        private const val MAX_WRITE_RELAYS = 6

        /** The `r` tags of a relay list (NIP-65): wss:// only, each once; no marker means both. */
        fun relayEntries(list: space.sovereigncast.hopp.nostr.Event?): List<RelayEntry> =
            list?.tags.orEmpty().filter { it.size >= 2 && it[0] == "r" }.mapNotNull { tag ->
                val url = relayAddress(tag[1]) ?: return@mapNotNull null
                val marker = tag.getOrNull(2)
                RelayEntry(url, read = marker != "write", write = marker != "read")
            }.distinctBy { it.url }

        private fun tags(list: List<RelayEntry>) = list.map { r ->
            listOfNotNull("r", r.url, when { r.read && r.write -> null; r.read -> "read"; else -> "write" })
        }

        /** A relay address as Hopp keeps it — `wss://host[:port][/path]`, lower-case host, no trailing slash;
         *  "relay.example.com" alone is taken for wss://. Null for anything else, ws:// included: a phone
         *  on mobile data and other people's clients reach a relay only over TLS. */
        fun relayAddress(text: String): String? {
            val t = text.trim().trimEnd('/')
            val withScheme = if ("://" in t) t else "wss://$t"
            val url = runCatching { io.ktor.http.Url(withScheme) }.getOrNull() ?: return null
            if (!withScheme.startsWith("wss://", ignoreCase = true) || url.host.isBlank() || '.' !in url.host || ' ' in t) return null
            val port = if (url.specifiedPort > 0 && url.specifiedPort != 443) ":${url.specifiedPort}" else ""
            val path = url.encodedPath.trimEnd('/')
            return "wss://${url.host.lowercase()}$port$path"
        }
    }
}
