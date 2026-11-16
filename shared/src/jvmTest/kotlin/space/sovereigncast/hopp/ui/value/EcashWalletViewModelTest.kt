package space.sovereigncast.hopp.ui.value

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.testDatabase
import space.sovereigncast.hopp.value.LnAddressClient
import space.sovereigncast.hopp.value.ValueService
import space.sovereigncast.hopp.value.WalletRepository
import space.sovereigncast.hopp.value.cashu.FakeMint
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The wallet page's ecash actions against a mint that signs and checks as a real one does. */
class EcashWalletViewModelTest {
    private val now = 1_800_000_000L
    private val mint = FakeMint()
    private val db = testDatabase()
    private val settings = SettingsRepository(db)
    private val wallet = WalletRepository(MemorySecretStore(), mint.http, { now })
    private val value = ValueService(
        db, wallet, EpisodeRepository(db, mint.http, { now }), PodcastRepository(db, FeedFetcher(mint.http), { now }), settings,
        LnAddressClient(mint.http), FeedFetcher(mint.http), null, { now }, AppInfo("0.0.1", "JVM", "Test"),
    )

    @BeforeTest fun main() = Dispatchers.setMain(Dispatchers.Unconfined)
    @AfterTest fun reset() = Dispatchers.resetMain()

    private suspend fun until(check: () -> Boolean) = withTimeout(10_000) { while (!check()) delay(10) }

    private suspend fun WalletViewModel.settled(): EcashAction {
        until { ecash.value != EcashAction.Working && !checking.value }
        return ecash.value
    }

    @Test
    fun aMintIsTakenUpFundedSpentAndKeptWhileItHoldsSats() = runBlocking {
        val vm = WalletViewModel(wallet, value, settings)
        vm.connectEcash("https://mint.test/")
        until { vm.connect.value == ConnectState.Idle && vm.connected.value != null }
        assertEquals("mint.test (ecash)", vm.connected.value?.shown)

        // Add: an invoice to pay elsewhere, then Check takes the sats in.
        vm.addEcash(64)
        val invoice = assertIs<EcashAction.Invoice>(vm.settled())
        assertTrue(invoice.invoice.startsWith("lnbc"))
        mint.payInvoices()
        vm.checkEcash()
        assertEquals(EcashAction.Idle, vm.settled())
        until { vm.balanceMsat.value == 64_000L }

        // Send: a token for exactly the amount; the rest stays.
        vm.sendEcash(10)
        val token = assertIs<EcashAction.Token>(vm.settled()).token
        assertTrue(token.startsWith("cashuB"))
        until { vm.balanceMsat.value == 54_000L }

        // Receive it back, as someone handing one over.
        vm.receiveEcash(" $token ")
        assertEquals(EcashAction.Received(10), vm.settled())
        until { vm.balanceMsat.value == 64_000L }

        // A token already taken in is spent: said, not crashed.
        vm.receiveEcash(token)
        assertIs<EcashAction.Failed>(vm.settled())

        // Withdraw: 20 sats to an invoice, 1 sat Lightning fee, the reserve's rest back as change.
        vm.withdrawEcash("lightning:lnbc1invoice", null)
        assertEquals(EcashAction.Withdrawn, vm.settled())
        until { vm.balanceMsat.value == 43_000L }

        // Disconnect would throw the sats away: refused, the wallet stays.
        vm.disconnect()
        assertEquals(EcashAction.StillHeld, vm.ecash.value)
        assertNotNull(vm.connected.value)
        Unit
    }

    @Test
    fun aWithdrawalTheMintHasNotSettledIsUnknownNotFailed() = runBlocking {
        val vm = WalletViewModel(wallet, value, settings)
        vm.connectEcash("https://mint.test")
        until { vm.connected.value != null }
        vm.addEcash(32); vm.settled()
        mint.payInvoices(); vm.checkEcash(); vm.settled()
        until { vm.balanceMsat.value == 32_000L }

        mint.meltOutcome = "PENDING"
        vm.withdrawEcash("lnbc1invoice", null)
        assertEquals(EcashAction.Unknown, vm.settled())

        // Once the mint has paid, Check takes the change in and the wallet empties as it should.
        mint.settlePendingMelts()
        vm.checkEcash()
        assertEquals(EcashAction.Idle, vm.settled())
        until { vm.balanceMsat.value == 11_000L }
    }

    @Test
    fun aMintThatIsNotHttpsIsRefusedBeforeAnyContact() = runBlocking {
        val vm = WalletViewModel(wallet, value, settings)
        vm.connectEcash("http://mint.test")
        until { vm.connect.value is ConnectState.Failed }
        assertTrue(mint.calls.isEmpty(), "contacted: ${mint.calls}")
    }
}
