package space.sovereigncast.hopp.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What the phone says about the network Hopp talks over (the privacy page,
 *  device feedback 2026-10-11); null = the platform cannot tell. */
data class NetworkPrivacy(val vpn: Boolean? = null, val privateDns: Boolean? = null)

fun interface NetworkPrivacySource {
    fun observe(): Flow<NetworkPrivacy>

    companion object {
        val Unknown = NetworkPrivacySource { flowOf(NetworkPrivacy()) }
    }
}
