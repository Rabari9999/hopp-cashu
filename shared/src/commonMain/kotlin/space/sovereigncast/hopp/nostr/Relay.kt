package space.sovereigncast.hopp.nostr

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/** One open connection to a Nostr relay: text frames in, text frames out. */
interface RelaySession {
    val incoming: Flow<String>
    suspend fun send(message: String)
    suspend fun close()
}

fun interface RelayConnector {
    suspend fun connect(url: String): RelaySession
}

/** Relay connections over Ktor WebSockets (OkHttp on Android, Darwin on iOS).
 *  The client must have the WebSockets plugin installed. */
class KtorRelayConnector(private val client: HttpClient) : RelayConnector {
    override suspend fun connect(url: String): RelaySession {
        val session = client.webSocketSession(url)
        return object : RelaySession {
            override val incoming: Flow<String> =
                session.incoming.consumeAsFlow().filterIsInstance<Frame.Text>().map { it.readText() }
            override suspend fun send(message: String) = session.send(Frame.Text(message))
            override suspend fun close() = session.close()
        }
    }
}
