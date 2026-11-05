package space.sovereigncast.hopp.nostr

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.toHex
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A Blossom server did not take the file — [reason] as it said, if it did. */
class BlossomFailed(val reason: String?) : Exception(reason ?: "upload failed")

/**
 * A profile picture onto a Blossom server (BUD-01/02; maintainer 2026-11-03): `PUT /upload`, allowed
 * by a kind-24242 event the listener's signer signs for exactly this file (its SHA-256), valid for
 * five minutes. The address it answers with is checked: https, and the file it names is this one.
 */
class Blossom(private val http: HttpClient) {
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun upload(server: String, bytes: ByteArray, type: String, signer: NostrSigner, now: Long): String {
        val hash = Sha256.digest(bytes).toHex()
        val auth = signer.sign(
            UnsignedEvent(
                KIND, "Upload profile picture",
                listOf(listOf("t", "upload"), listOf("x", hash), listOf("expiration", (now + 300).toString())), now,
            ),
        )
        val response = http.put(server.trimEnd('/') + "/upload") {
            header(HttpHeaders.Authorization, "Nostr " + Base64.encode(Json.encodeToString(Event.serializer(), auth).encodeToByteArray()))
            contentType(ContentType.parse(type))
            setBody(bytes)
        }
        if (!response.status.isSuccess()) throw BlossomFailed(response.headers["X-Reason"] ?: response.status.toString())
        val blob = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() ?: throw BlossomFailed(null)
        fun text(key: String) = (blob[key] as? JsonPrimitive)?.contentOrNull
        val url = text("url")?.takeIf { it.startsWith("https://") } ?: throw BlossomFailed(null)
        // The server's own word on what it stored: this file, or nothing — and no word is no upload
        // (review 2026-11-04: a server leaving it out could hand back any address for the profile).
        if (text("sha256")?.lowercase() != hash) throw BlossomFailed("hash")
        return url
    }

    companion object {
        const val KIND = 24242
        /** Where a picture goes when the listener names no server of their own (kind 10063): nostr.build's, free. */
        const val DEFAULT_SERVER = "https://blossom.band"
        /** The listener's servers (BUD-03): kind 10063, its `server` tags, https only, in their order. */
        const val SERVER_LIST_KIND = 10063

        fun servers(list: Event?): List<String> =
            (list?.tags ?: emptyList()).filter { it.size >= 2 && it[0] == "server" }.map { it[1].trim().trimEnd('/') }
                .filter { it.startsWith("https://") }.distinct()
    }
}
