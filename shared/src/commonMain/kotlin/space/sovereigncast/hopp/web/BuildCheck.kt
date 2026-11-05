package space.sovereigncast.hopp.web

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import space.sovereigncast.hopp.nostr.RelayPublisher
import kotlin.time.Duration.Companion.seconds

/** A build of Hopp Web: its version, and the SHA-256 of its file list (release.json) — the build's hash. */
data class WebBuild(val version: String, val hash: String)

/** What the build running is, against what Nodesignal signed (ADR 0022, 14). */
sealed interface BuildStatus {
    /** Nodesignal signed exactly this build: [eventId] says so. */
    data class Signed(val eventId: String) : BuildStatus
    /** Nodesignal signed this version as another build, [signedHash]: not the one running. */
    data class Differs(val signedHash: String) : BuildStatus
    /** Nothing signed for it (yet). */
    data object Unsigned : BuildStatus
    /** No relay answered: not checked. */
    data object Unknown : BuildStatus
}

/**
 * Looks up Nodesignal's signature for the build of Hopp Web running (ADR 0022, 14): NIP-94 file
 * metadata (kind 1063) for its release.json, `x` its hash, signed with the key that signs Hopp's
 * releases on Zapstore (web/deploy/sign.mjs). A check from inside the page — a page a server
 * altered can say anything about itself; the one that counts is from outside
 * (web/deploy/verify.mjs). It catches what goes wrong without intent: a build not signed, a deploy
 * half done, the wrong build on the server. [build] is null where no web build runs (the apps).
 */
class BuildCheck(
    private val publisher: RelayPublisher,
    private val relays: List<String>,
    private val build: suspend () -> WebBuild?,
    private val signer: String = SIGNER,
) {
    suspend fun current(): WebBuild? = runCatching { build() }.getOrNull()

    suspend fun status(build: WebBuild): BuildStatus {
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(kotlinx.serialization.json.JsonPrimitive(KIND)) }
            putJsonArray("authors") { add(kotlinx.serialization.json.JsonPrimitive(signer)) }
            putJsonArray("#t") { add(kotlinx.serialization.json.JsonPrimitive(TOPIC)) }
            put("limit", 100)
        }
        val found = runCatching { publisher.queryChecked(listOf(filter), relays, settle = 3.seconds) }.getOrNull()
        if (found == null || found.answered == 0) return BuildStatus.Unknown
        val signed = found.events.filter { it.pubkey == signer && it.kind == KIND }
        signed.firstOrNull { it.tag("x") == build.hash }?.let { return BuildStatus.Signed(it.id) }
        signed.filter { it.tag("version") == build.version }.maxByOrNull { it.created_at }?.tag("x")?.let { return BuildStatus.Differs(it) }
        return BuildStatus.Unsigned
    }

    companion object {
        /** Nodesignal's key (npub1n0devk3h…, zapstore.yaml), which signs Hopp's releases. */
        const val SIGNER = "9bdb965a3757e233699b5aabd8fc55bc0c1f48e39c4d5acac6d37b2f48f2161e"
        const val KIND = 1063
        const val TOPIC = "hopp-web"
    }
}
