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
import space.sovereigncast.hopp.value.nwc.NwcClient
import space.sovereigncast.hopp.value.nwc.NwcConnection
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
    /** The node's key from get_info — so a boost does not pay the wallet itself. */
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
    private val _wallet = MutableStateFlow(load())
    val wallet: StateFlow<ConnectedWallet?> = _wallet.asStateFlow()

    private fun load(): ConnectedWallet? {
        if (secrets.get(URI) == null) return null
        return secrets.get(INFO)?.let { runCatching { json.decodeFromString(ConnectedWallet.serializer(), it) }.getOrNull() } ?: ConnectedWallet()
    }

    fun client(): NwcClient? {
        val connection = secrets.get(URI)?.let { runCatching { NwcConnection.parse(it) }.getOrNull() } ?: return null
        return NwcClient(connection, connector, { clock.now() }, timeout)
    }

    /** Checks the string, asks the wallet what it can, and only then keeps it.
     *  Throws with the reason when either fails — nothing is stored then. */
    suspend fun connect(uri: String): ConnectedWallet {
        val connection = NwcConnection.parse(uri)
        val client = NwcClient(connection, connector, { clock.now() }, 20.seconds)
        val info = client.info()
        // Name and node key, if the wallet answers get_info — not required.
        val (alias, pubkey) = if ("get_info" in info.methods) runCatching { client.nodeInfo() }.getOrDefault(null to null) else null to null
        val wallet = ConnectedWallet(alias ?: info.alias, info.methods, info.encryptions, connection.relays.first(), connection.lud16, pubkey)
        secrets.put(URI, uri.trim())
        secrets.put(INFO, json.encodeToString(ConnectedWallet.serializer(), wallet))
        _wallet.value = wallet
        return wallet
    }

    /** A wallet connected before Hopp asked for its node key gets asked once now. */
    suspend fun ensureNodeInfo() {
        val w = _wallet.value ?: return
        if (w.pubkey != null || "get_info" !in w.methods) return
        val (alias, pubkey) = runCatching { client()?.nodeInfo() }.getOrNull() ?: return
        if (pubkey == null) return
        val updated = w.copy(alias = w.alias ?: alias, pubkey = pubkey)
        secrets.put(INFO, json.encodeToString(ConnectedWallet.serializer(), updated))
        _wallet.value = updated
    }

    fun disconnect() {
        secrets.put(URI, null)
        secrets.put(INFO, null)
        _wallet.value = null
    }

    /** null when the wallet does not answer or does not tell. */
    suspend fun balanceMsat(): Long? = runCatching { client()?.balanceMsat() }.getOrNull()

    private companion object {
        const val URI = "nwc_uri"
        const val INFO = "nwc_info"
    }
}
