package space.sovereigncast.hopp.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * VPN and private DNS of the network Hopp's requests take — the default
 * network, which is the VPN's while one runs — kept current while the
 * privacy page watches (back from the system's settings, the check has
 * already turned). Private DNS is known from Android 9 on.
 */
class AndroidNetworkPrivacy(context: Context) : NetworkPrivacySource {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override fun observe(): Flow<NetworkPrivacy> = callbackFlow {
        var caps: NetworkCapabilities? = null
        var link: LinkProperties? = null
        fun send() = trySend(NetworkPrivacy(
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false,
            privateDns = if (Build.VERSION.SDK_INT >= 28) link?.isPrivateDnsActive ?: false else null,
        ))
        cm.activeNetwork?.let { caps = cm.getNetworkCapabilities(it); link = cm.getLinkProperties(it) }
        send()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) { caps = c; send() }
            override fun onLinkPropertiesChanged(network: Network, l: LinkProperties) { link = l; send() }
            override fun onLost(network: Network) { caps = null; link = null; send() }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
