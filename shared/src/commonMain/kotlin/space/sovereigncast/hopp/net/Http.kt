package space.sovereigncast.hopp.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.websocket.WebSockets
import space.sovereigncast.hopp.platform.AppInfo

/** The one HTTP client: Hopp's User-Agent on every request (ADR 0015) — the
 *  default OkHttp agent would be counted as a bot by Sovereigncast. */
fun createHttpClient(app: AppInfo): HttpClient = HttpClient {
    install(UserAgent) { agent = app.userAgent }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
    }
    install(WebSockets)
    followRedirects = true
}
