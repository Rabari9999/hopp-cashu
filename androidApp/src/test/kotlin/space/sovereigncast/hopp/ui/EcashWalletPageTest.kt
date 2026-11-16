package space.sovereigncast.hopp.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.ui.theme.HoppTheme
import space.sovereigncast.hopp.ui.value.WalletScreen
import space.sovereigncast.hopp.ui.value.WalletViewModel
import space.sovereigncast.hopp.value.WalletRepository
import space.sovereigncast.hopp.value.cashu.EcashState
import space.sovereigncast.hopp.value.cashu.Proof
import space.sovereigncast.hopp.value.cashu.ProofStore

/** The wallet page with ecash at a Cashu mint (ADR 0026): taking one up, and not losing what it holds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EcashWalletPageTest {
    @get:Rule val rule = createComposeRule()

    private val koin get() = org.koin.mp.KoinPlatform.getKoin()
    private val secrets = MemorySecretStore()
    private val mintCalls = mutableListOf<String>()
    // Just what taking up a mint asks: what it is, and that it issues sats.
    private val http = HttpClient(MockEngine { req ->
        mintCalls += req.url.toString()
        val json = headersOf("Content-Type", "application/json")
        when (req.url.encodedPath) {
            "/v1/info" -> respond("""{"name":"Test mint","nuts":{}}""", HttpStatusCode.OK, json)
            "/v1/keysets" -> respond("""{"keysets":[{"id":"009a1f293253e41e","unit":"sat","active":true}]}""", HttpStatusCode.OK, json)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String, substring: Boolean = false) = rule.waitUntil(10_000) { has(text, substring) }
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()

    private fun page() {
        val vm = WalletViewModel(WalletRepository(secrets, http, { 1_800_000_000L }), koin.get(), koin.get())
        rule.setContent { HoppTheme { WalletScreen(vm) } }
    }

    @Test
    fun aMintIsTakenUpOnlyOnTheTap() {
        page()
        waitFor("Use this mint")
        check(mintCalls.isEmpty()) { "the mint was contacted before the tap: $mintCalls" }
        rule.onNode(hasTestTag("ecash_mint")).performScrollTo().performTextInput("https://mint.test")
        tap("Use this mint")
        waitFor("Connected: mint.test (ecash)")
        waitFor("Add by Lightning")
        check(has("Send ecash") && has("Withdraw") && has("Receive")) { "the ecash actions are missing" }
        check(!has("Use this mint")) { "the mint form stays once a mint is in use" }
    }

    @Test
    fun ecashThatHoldsSatsIsNotDisconnected() {
        ProofStore(secrets).save(EcashState(
            "https://mint.test",
            proofs = listOf(Proof(1, "009a1f293253e41e", "aa".repeat(32), "02" + "bb".repeat(32)), Proof(4, "009a1f293253e41e", "cc".repeat(32), "02" + "dd".repeat(32))),
        ))
        page()
        waitFor("Connected: mint.test (ecash)")
        waitFor("Balance: 5 sats")
        tap("Disconnect")
        waitFor("still holds sats", substring = true)
        check(has("Connected: mint.test (ecash)")) { "the ecash wallet went although it held sats" }
        check(ProofStore(secrets).load()?.balance == 5L) { "the proofs were touched" }
    }
}
