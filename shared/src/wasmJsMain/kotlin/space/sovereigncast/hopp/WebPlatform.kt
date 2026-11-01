package space.sovereigncast.hopp

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
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

    // No downloads on the web in the first version (ADR 0022, 1).
    override val downloads: Boolean get() = false
    override fun downloadDir(): String = "/downloads"
    override fun downloadScheduler(): DownloadScheduler = DownloadScheduler.None

    override fun secretStore(): SecretStore = secrets

    // ADR 0022, 2: only signers that keep the key — an extension (NIP-07) or a remote signer (NIP-46).
    override val keysInApp: Boolean get() = false
    // No NWC connection in the browser until it has a passkey's protection and a budget of its own
    // (ADR 0022, 4, stage W5; maintainer 2026-10-30): one kept before goes at the next start.
    override val wallet: Boolean get() = false
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
