package space.sovereigncast.hopp.web

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.RelayConnector
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** ADR 0022, 14: the build running, against what Nodesignal signed (sign.mjs's NIP-94 event). */
class BuildCheckTest {
    private val nodesignal = KeyPair.generate()
    private val relay = FakeRelay()
    private val build = WebBuild("v0.3.3", "ab".repeat(32))
    private fun check(connector: RelayConnector = relay) =
        BuildCheck(RelayPublisher(connector), listOf("wss://relay.test"), { build }, signer = nodesignal.publicKeyHex)
    private fun sign(keys: KeyPair, version: String, hash: String, at: Long = 1_780_000_000) = Event.sign(
        keys, BuildCheck.KIND, "Hopp Web $version",
        listOf(listOf("x", hash), listOf("version", version), listOf("t", BuildCheck.TOPIC)), at,
    ).also(relay::publish)

    @Test
    fun theBuildNodesignalSignedIsSaidToMatch(): Unit = runBlocking {
        val event = sign(nodesignal, "v0.3.3", build.hash)
        assertEquals(BuildStatus.Signed(event.id), check().status(build))
    }

    @Test
    fun anotherBuildUnderTheSameVersionDiffers(): Unit = runBlocking {
        sign(nodesignal, "v0.3.3", "cd".repeat(32))
        assertEquals(BuildStatus.Differs("cd".repeat(32)), check().status(build))
    }

    /** Anyone can publish such an event: only Nodesignal's key counts. */
    @Test
    fun aSignatureBySomeoneElseCountsForNothing(): Unit = runBlocking {
        sign(KeyPair.generate(), "v0.3.3", build.hash)
        sign(nodesignal, "v0.3.2", "ef".repeat(32))
        assertEquals(BuildStatus.Unsigned, check().status(build))
    }

    @Test
    fun withoutARelayNothingIsSaid(): Unit = runBlocking {
        val none = RelayConnector { _ -> throw IllegalStateException("unreachable") }
        assertIs<BuildStatus.Unknown>(check(none).status(build))
    }
}
