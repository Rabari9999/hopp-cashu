package space.sovereigncast.hopp.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.ui.theme.HoppTheme
import space.sovereigncast.hopp.ui.value.EcashAction
import space.sovereigncast.hopp.ui.value.WalletScreen
import space.sovereigncast.hopp.ui.value.WalletViewModel
import space.sovereigncast.hopp.value.WalletRepository
import space.sovereigncast.hopp.value.cashu.MintClient

/**
 * The ecash wallet page against a real mint, drawn step by step into build/ecash-demo/.
 * Off unless HOPP_LIVE_MINT is set — CI never goes to the network:
 *
 *     HOPP_LIVE_MINT=https://testnut.cashu.space ./gradlew :androidApp:testFossDebugUnitTest --tests '*EcashDemoShotsTest'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EcashDemoShotsTest {
    @get:Rule val rule = createComposeRule()

    private val mintUrl = System.getenv("HOPP_LIVE_MINT")
    private val koin get() = org.koin.mp.KoinPlatform.getKoin()
    private val out = java.io.File("build/ecash-demo")
    private var shot = 0

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String, substring: Boolean = false) = rule.waitUntil(30_000) { has(text, substring) }
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
    private fun type(tag: String, text: String) = rule.onNode(hasTestTag(tag)).performScrollTo().performTextInput(text)

    /** The window as it is now, after scrolling [show] into view. */
    private fun snap(name: String, show: String? = null) {
        show?.let { rule.onAllNodesWithText(it, substring = true)[0].performScrollTo() }
        rule.waitForIdle()
        val file = java.io.File(out, "%02d-%s.png".format(++shot, name))
        // The window's view drawn directly, as HomeLayoutTest does: captureToImage can wait on a redraw forever here.
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<android.view.View>
        rule.runOnIdle {
            val v = views.last()
            val bitmap = android.graphics.Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bitmap))
            java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        println("DEMO $file")
    }

    /** Waits for the action to finish and says what it ended in when that is not [T]. */
    private inline fun <reified T : EcashAction> expect(vm: WalletViewModel): T {
        // Real time, with the main looper pumped: the mint is on the network, not on the test clock.
        repeat(120) {
            if (vm.ecash.value != EcashAction.Working) return@repeat
            Thread.sleep(250)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        println("DEMO state ${vm.ecash.value.toString().take(60)}")
        rule.waitForIdle()
        val s = vm.ecash.value
        return s as? T ?: error("expected ${T::class.simpleName}, got $s")
    }

    @Test
    fun theWalletPageAtARealMint() {
        if (mintUrl.isNullOrBlank()) return
        out.mkdirs()
        val http = createHttpClient(AppInfo("0.0.1", "Android", "Demo"))
        val vm = WalletViewModel(WalletRepository(MemorySecretStore(), http, { System.currentTimeMillis() / 1000 }), koin.get(), koin.get())
        rule.setContent { HoppTheme { WalletScreen(vm) } }

        waitFor("Use this mint")
        type("ecash_mint", mintUrl)
        snap("choose-a-mint", "Use this mint")
        tap("Use this mint")
        waitFor("(ecash)", substring = true)
        snap("connected")

        type("ecash_amount", "100")
        tap("Add by Lightning")
        expect<EcashAction.Invoice>(vm)
        snap("invoice-to-pay", "Check")
        tap("Check")
        waitFor("Balance: 100 sats")
        snap("funded")

        // The amount field still says 100; send 10 of it.
        rule.onNode(hasTestTag("ecash_amount")).performScrollTo().performClick()
        rule.onNode(hasTestTag("ecash_amount")).performTextClearance()
        type("ecash_amount", "10")
        tap("Send ecash")
        val token = expect<EcashAction.Token>(vm).token
        snap("token-to-hand-over", "Done")
        tap("Done")

        type("ecash_token", token)
        tap("Receive")
        waitFor("Received", substring = true)
        snap("received-back", "Received")

        // Withdraw to an invoice the mint itself issued (testnut's Lightning is a fake that pays anything).
        val invoice = runBlocking { MintClient(http, mintUrl).mintQuote(20).request }
        type("ecash_invoice", invoice)
        tap("Withdraw")
        // Withdrawn, or Unknown when the mint returns no preimage (ADR 0026: testnut settles its own invoices so).
        val end = expect<EcashAction>(vm)
        println("DEMO withdraw → $end, balance ${vm.balanceMsat.value?.div(1000)}")
        snap("withdrawn", "Withdraw")
    }
}
