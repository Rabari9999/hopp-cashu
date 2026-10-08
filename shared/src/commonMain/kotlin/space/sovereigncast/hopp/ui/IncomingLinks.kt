package space.sovereigncast.hopp.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** Links the platform hands to the app — today a `nostr+walletconnect:` URI
 *  from a wallet's "connect" button, which opens the wallet page filled in. */
object IncomingLinks {
    val walletUri = MutableStateFlow<String?>(null)
}
