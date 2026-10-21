package space.sovereigncast.hopp.value

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.sovereigncast.hopp.data.Clock
import space.sovereigncast.hopp.nostr.KtorRelayConnector
import space.sovereigncast.hopp.nostr.RelayConnector
import space.sovereigncast.hopp.platform.SecretStore
import space.sovereigncast.hopp.platform.SecretUnreadable
import space.sovereigncast.hopp.value.nwc.NwcClient
import space.sovereigncast.hopp.value.nwc.NwcConnection
import space.sovereigncast.hopp.value.nwc.NwcException
import space.sovereigncast.hopp.value.nwc.WalletInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The connected wallet as the app shows it — what it can, not its secret. */
@Serializable
data class ConnectedWallet(
    val alias: String? = null,
    val methods: Set<String> = emptySet(),
    val encryptions: Set<String> = emptySet(),
    val relay: String = "",
    val lud16: String? = null,
    /** The node's key from get_info — shared by every sub-wallet on that node. */
    val pubkey: String? = null,
) {
    val info: WalletInfo get() = WalletInfo(methods, encryptions, alias, pubkey, lud16)
    val canKeysend: Boolean get() = info.canKeysend
    val canPayInvoice: Boolean get() = info.canPayInvoice
    /** A name to show: the wallet's alias, else the relay's host. */
    val label: String get() = alias?.takeIf { it.isNotBlank() } ?: relay.substringAfter("://").substringBefore('/')
}

/**
 * The listener's wallet over Nostr Wallet Connect (ADR 0005). The connection
 * string can spend: it lives in the [SecretStore] only. What the wallet can do
 * (`get_info`) is kept next to it, so a boost does not wait for a round trip
 * before it starts.
 */
class WalletRepository(
    private val secrets: SecretStore,
    private val http: HttpClient,
    private val clock: Clock,
    private val connector: RelayConnector = KtorRelayConnector(http),
    private val timeout: Duration = 45.seconds,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _unreadable = MutableStateFlow(false)
    /** A connection is stored that the secret store could not give up ([SecretUnreadable]) —
     *  not replaced by a new one; [disconnect] is the way past it (security review 2026-10-20). */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()
    private val _wallet = MutableStateFlow(load())
    val wallet: StateFlow<ConnectedWallet?> = _wallet.asStateFlow()

    private fun load(): ConnectedWallet? = try {
        if (secrets.get(URI) == null) null
        else secrets.get(INFO)?.let { runCatching { json.decodeFromString(ConnectedWallet.serializer(), it) }.getOrNull() } ?: ConnectedWallet()
    } catch (e: SecretUnreadable) {
        _unreadable.value = true
        null
    }

    fun client(): NwcClient? {
        val connection = runCatching { secrets.get(URI) }.getOrNull()?.let { runCatching { NwcConnection.parse(it) }.getOrNull() } ?: return null
        return NwcClient(connection, connector, { clock.now() }, timeout)
    }

    /** Checks the string, asks the wallet what it can, and only then keeps it.
     *  Throws with the reason when either fails — nothing is stored then. */
    suspend fun connect(uri: String): ConnectedWallet {
        if (_unreadable.value) throw SecretUnreadable(URI)
        val connection = NwcConnection.parse(uri)
        val client = NwcClient(connection, connector, { clock.now() }, 20.seconds)
        val info = client.info()
        // Hopp speaks NIP-44 only; a wallet that does not (no `encryption` tag
        // means NIP-04, NIP-47) could never read a request — every payment
        // would end as an unknown outcome and count against the budget
        // (security review 2026-10-20, L-4). Refused here, with the reason.
        if (NIP44 !in info.encryptions) throw NwcException(UNSUPPORTED_ENCRYPTION, "the wallet encrypts with ${info.encryptions.joinToString()} only; Hopp speaks $NIP44")
        // Name and node key, if the wallet answers get_info — not required.
        val (alias, pubkey) = if ("get_info" in info.methods) runCatching { client.nodeInfo() }.getOrDefault(null to null) else null to null
        val wallet = ConnectedWallet(alias ?: info.alias, info.methods, info.encryptions, connection.relays.first(), connection.lud16, pubkey)
        secrets.put(URI, uri.trim())
        secrets.put(INFO, json.encodeToString(ConnectedWallet.serializer(), wallet))
        _wallet.value = wallet
        return wallet
    }

    fun disconnect() {
        secrets.put(URI, null)
        secrets.put(INFO, null)
        _wallet.value = null
        _unreadable.value = false
    }

    /** null when the wallet does not answer or does not tell. */
    suspend fun balanceMsat(): Long? = runCatching { client()?.balanceMsat() }.getOrNull()

    companion object {
        private const val URI = "nwc_uri"
        private const val INFO = "nwc_info"
        private const val NIP44 = "nip44_v2"
        /** [NwcException.code] of a wallet refused at connect for speaking NIP-04 only. */
        const val UNSUPPORTED_ENCRYPTION = "UNSUPPORTED_ENCRYPTION"
    }
}
