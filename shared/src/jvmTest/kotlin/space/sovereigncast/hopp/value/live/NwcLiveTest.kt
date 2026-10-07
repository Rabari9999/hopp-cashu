package space.sovereigncast.hopp.value.live

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.feed.FeedParser
import space.sovereigncast.hopp.value.BoostContext
import space.sovereigncast.hopp.value.BoostSender
import space.sovereigncast.hopp.value.LnAddressClient
import space.sovereigncast.hopp.value.describe
import space.sovereigncast.hopp.value.nwc.WalletInfo
import space.sovereigncast.hopp.nostr.KtorRelayConnector
import space.sovereigncast.hopp.value.nwc.NwcClient
import space.sovereigncast.hopp.value.nwc.NwcConnection
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Against a REAL wallet — only when HOPP_NWC_URI is set (never in CI, never
 * committed). Spending steps need HOPP_NWC_SPEND too:
 *   HOPP_NWC_URI='nostr+walletconnect://…' ./gradlew :shared:jvmTest --tests '*NwcLiveTest*'
 */
class NwcLiveTest {
    private val uri = System.getenv("HOPP_NWC_URI")

    private val http = HttpClient(OkHttp) {
        install(WebSockets)
        install(UserAgent) { agent = "Hopp/0.0.1 (Android; Phone)" }
    }

    private fun client(): NwcClient {
        return NwcClient(NwcConnection.parse(uri!!), KtorRelayConnector(http), now = { System.currentTimeMillis() / 1000 }, timeout = 45.seconds)
    }

    @Test
    fun infoAndBalance() {
        if (uri.isNullOrBlank()) return
        runBlocking {
            val c = client()
            val info = c.info()
            println("LIVE info: methods=${info.methods.sorted()} encryptions=${info.encryptions}")
            println("LIVE balance: ${c.balanceMsat()} msat")
        }
    }

    /** A real boost on the latest Nodesignal episode, through the router. */
    @Test
    fun realBoostAndLnurlPayment() {
        if (uri.isNullOrBlank() || System.getenv("HOPP_NWC_SPEND") != "1") return
        runBlocking {
            val nwc = client()
            val info = nwc.info()
            val feed = FeedParser.parse(http.get("https://serve.podhome.fm/rss/e8df0b13-47de-544a-99b7-ec7cbd960a16").body<ByteArray>())
            val episode = feed.episodes.first { ep -> ep.value?.recipients?.any { it.type == "lnaddress" } == true }
            println("LIVE episode: ${episode.title} ${episode.guid} value=${episode.value!!.recipients.map { it.name + ":" + it.split }}")
            val ctx = BoostContext(
                action = "boost", appVersion = "0.0.1", podcast = feed.podcast.title, episode = episode.title,
                feedUrl = "https://serve.podhome.fm/rss/e8df0b13-47de-544a-99b7-ec7cbd960a16",
                podcastGuid = feed.podcast.podcastGuid, episodeGuid = episode.guid, positionSeconds = 120,
                senderName = "Hopp-Test", message = "Hopp NWC-Test (Phase 0)",
            )
            val ln = LnAddressClient(http)
            val boost = BoostSender(nwc, ln, info).send(episode.value!!, 21_000, ctx)
            println("LIVE boost:\n" + boost.describe())

            // The invoice route, as a wallet without keysend would take it.
            val karsten = episode.value!!.recipients.first { it.type == "lnaddress" }
            val invoiceOnly = WalletInfo(info.methods - "pay_keysend", info.encryptions)
            val single = space.sovereigncast.hopp.model.ValueBlock("lightning", "lnaddress", recipients = listOf(karsten.copy(split = 100)))
            val lnurl = BoostSender(nwc, ln, invoiceOnly).send(single, 5_000, ctx)
            println("LIVE lnurl:\n" + lnurl.describe())
            println("LIVE balance after: ${nwc.balanceMsat()} msat")
        }
    }
}
