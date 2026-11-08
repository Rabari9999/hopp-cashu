package space.sovereigncast.hopp

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.data.DownloadScheduler
import space.sovereigncast.hopp.di.Platform
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.nostr.ExternalSigner
import space.sovereigncast.hopp.nostr.Nip07Signer
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.SecretStore
import space.sovereigncast.hopp.playback.HtmlAudioPlaybackEngine

/** Hopp in the browser (ADR 0022). [driver]: the database, opened before ([openWebDatabase]). */
class WebPlatform(private val driver: SqlDriver, private val secrets: SecretStore = MemorySecretStore()) : Platform {
    override val app: AppInfo = AppInfo(version = WebVersion.NAME, platform = "Web", deviceClass = "Browser")
    override fun sqlDriver(): SqlDriver = driver
    override fun playbackEngine(): PlaybackEngine = HtmlAudioPlaybackEngine()

    // Downloads on request, kept in the browser (maintainer 2026-11-08, ADR 0022, 1); none by themselves:
    // a tab has no background.
    private val store = WebDownloads()
    override val downloads: Boolean get() = true
    override val autoDownloads: Boolean get() = false
    override fun downloadDir(): String = "/downloads"
    override fun downloadFiles(): space.sovereigncast.hopp.data.DownloadFiles = WebDownloadFiles()
    override fun browserDownloads(): space.sovereigncast.hopp.data.BrowserDownloads = store
    private val downloadScope = kotlinx.coroutines.MainScope()
    private var downloading: kotlinx.coroutines.Job? = null
    // While the tab is open: one run at a time; one the network broke off is tried again a minute later,
    // and what was left half done when the tab closed goes on when Hopp opens again.
    override fun downloadScheduler(): DownloadScheduler = DownloadScheduler { _ -> runDownloads() }.also {
        downloadScope.launch { kotlinx.coroutines.delay(5_000); runDownloads() }
    }
    private fun runDownloads() {
        if (downloading?.isActive == true) {
            // Asked while one runs: that run takes the new one too, as it looks for the next in the queue.
            return
        }
        downloading = downloadScope.launch {
            val repository = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.DownloadRepository>()
            while (!runCatching { repository.runPending(includeAuto = false) }.getOrDefault(true)) kotlinx.coroutines.delay(60_000)
        }
    }

    override fun secretStore(): SecretStore = secrets

    // ADR 0022, 2: only signers that keep the key — an extension (NIP-07) or a remote signer (NIP-46).
    override val keysInApp: Boolean get() = false
    // No NWC connection in the browser until it has a passkey's protection and a budget of its own
    // (ADR 0022, 4, stage W5; maintainer 2026-10-30): one kept before goes at the next start.
    override val wallet: Boolean get() = false
    // The browser's own wallet instead, where an extension offers one (WebLN, ADR 0022, 4, stage W3).
    override suspend fun webBuild(): space.sovereigncast.hopp.web.WebBuild? = WebUpdates.current()
    override val browserWallet: space.sovereigncast.hopp.value.BrowserWallet = space.sovereigncast.hopp.value.WeblnWallet()
    // No background job in a browser tab: the feeds are looked at while Hopp is open (maintainer 2026-11-01).
    override val backgroundRefresh: Boolean get() = false
    override suspend fun signedOut() = wipeOnNextStart()
    override fun externalSigner(): ExternalSigner = Nip07Signer()

    override fun httpClient(): HttpClient = createHttpClient(app) {
        val proxy = feedProxyBase()
        install(FeedProxy) { base = proxy }
        // The proxy's "not now" — its share per listener used up (429) or every slot busy (503) — asked
        // again when it says (review 2026-10-31: a library restored at once ran into the limit, and
        // its feeds and covers simply failed). Only the proxy's: a host's own 503 stays an answer.
        install(HttpRequestRetry) {
            retryIf(maxRetries = 3) { request, response ->
                proxy.isNotEmpty() && request.url.toString().startsWith(proxy.trimEnd('/')) &&
                    (response.status == HttpStatusCode.TooManyRequests || response.status == HttpStatusCode.ServiceUnavailable)
            }
            retryOnExceptionIf { _, _ -> false }
            delayMillis(respectRetryAfterHeader = true) { retry -> retry * 1_000L }
        }
    }.also { client ->
        // Ktor's browser engine reports a failed fetch (offline, CORS, a refused connection) as a
        // kotlin.Error, which Hopp's `catch (e: Exception)` would not see: an IOException, as elsewhere.
        client.plugin(HttpSend).intercept { request ->
            try {
                execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Error) {
                throw IOException(e.message ?: "network error", e)
            } catch (e: JsException) {
                throw IOException(e.message ?: "network error", e)
            }
        }
    }
}
