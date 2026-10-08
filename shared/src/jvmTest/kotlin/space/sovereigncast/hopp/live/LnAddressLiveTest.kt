package space.sovereigncast.hopp.live

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.value.Bolt11
import space.sovereigncast.hopp.value.LnAddressClient
import kotlin.test.Test

/** The Lightning addresses of the Nodesignal feed, through Hopp's own code —
 *  invoices are fetched, nothing is paid. Runs only with HOPP_LIVE_LNADDRESS=1. */
class LnAddressLiveTest {
    @Test
    fun nodesignalAddresses() = runBlocking {
        if (System.getenv("HOPP_LIVE_LNADDRESS") != "1") return@runBlocking
        val ln = LnAddressClient(createHttpClient(AppInfo("0.0.1", "JVM", "Test")))
        for (address in listOf("altbierjupp@getalby.com", "chrissha256@getalby.com", "karstenblock@getalby.com", "egge@npubx.cash", "v4v@d11n.net")) {
            val route = runCatching { ln.keysendInfo(address) }
            println("$address keysend: ${route.getOrNull()?.let { "${it.pubkey.take(12)}… ${it.customKey}=${it.customValue}" } ?: route.exceptionOrNull()}")
            for (comment in listOf(null, "Danke!")) {
                val r = runCatching {
                    val req = ln.payRequest(address)
                    val pr = ln.invoice(req, 10_000, comment)
                    "ok ${Bolt11.parse(pr).amountMsat} msat"
                }
                println("  lnurl comment=$comment: ${r.getOrNull() ?: r.exceptionOrNull()}")
            }
        }
    }
}
