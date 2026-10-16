package space.sovereigncast.hopp.live

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.identity.NostrIdentity
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.nostr.ExternalSigner
import space.sovereigncast.hopp.nostr.KtorRelayConnector
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.SignerPermission
import space.sovereigncast.hopp.nostr.SignerResult
import space.sovereigncast.hopp.nostr.npubToHex
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The real relays (device feedback 2026-10-16): the maintainer's profile is
 * on none of Hopp's relays, only on an index. `HOPP_LIVE_NOSTR=1` to run.
 */
class NostrProfileLiveTest {
    @Test
    fun theMaintainersProfileIsFound() = runBlocking<Unit> {
        if (System.getenv("HOPP_LIVE_NOSTR") != "1") return@runBlocking
        val pubkey = npubToHex("npub1qqqqqrctxmea7ct0zr6m3uaedqydpdevm3hv0w4kkhcen8skd98snft0d0")!!
        val signer = object : ExternalSigner {
            override fun available() = true
            override suspend fun login(permissions: List<SignerPermission>) = pubkey to "test"
            override suspend fun signEvent(signerPackage: String, eventJson: String, eventId: String, currentUser: String) = SignerResult(null, null)
        }
        val http = createHttpClient(AppInfo("0.0.1", "JVM", "Test"))
        val repo = IdentityRepository(MemorySecretStore(), RelayPublisher(KtorRelayConnector(http)), { 0 }, signer)
        val me: NostrIdentity = repo.signInWithSigner()
        println("live profile: name=${me.name} picture=${me.picture}")
        // Whatever it says today: found at all is the point.
        assertNotNull(me.name)
        assertNotNull(me.picture)
    }
}
