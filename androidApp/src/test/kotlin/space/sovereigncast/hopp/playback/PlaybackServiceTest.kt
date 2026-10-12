package space.sovereigncast.hopp.playback

import androidx.media3.common.Player
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import space.sovereigncast.hopp.ui.TestApp

/** The service starts — session, library callback, chapter player — and
 *  offers next/previous even for a single episode (ADR 0012). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class PlaybackServiceTest {
    @Test
    fun theServiceStartsAndOffersChapterJumps() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        ShadowLooper.idleMainLooper()
        val session = service.onGetSession(androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "test", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false))!!
        assertTrue(session.player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(session.player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue("no boost button without a wallet", session.mediaButtonPreferences.isEmpty())
        controller.destroy()
    }

    /** With a wallet, the lock screen and the notification get a boost button. */
    @Test
    fun aConnectedWalletPutsABoostButtonOnTheLockScreen() {
        val koin = org.koin.mp.KoinPlatform.getKoin()
        val secrets = koin.get<space.sovereigncast.hopp.platform.SecretStore>()
        secrets.put("nwc_uri", "nostr+walletconnect://" + "a".repeat(64) + "?relay=wss://r.test&secret=" + "b".repeat(64))
        secrets.put("nwc_info", """{"methods":["pay_invoice","pay_keysend"]}""")
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        repeat(5) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
        val session = controller.get().onGetSession(androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "test", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false))!!
        assertEquals(listOf("Boost"), session.mediaButtonPreferences.map { it.displayName.toString() })
        controller.destroy()
    }

    /**
     * Security report 2026-10-10, H-01: the session is exported, so any app
     * may connect — but only Hopp, its notification, the car and controllers
     * the system trusts (System UI) get the boost, and a boost sent without
     * it pays nothing.
     */
    @Test
    fun onlyTrustedControllersMayBoost() {
        val koin = org.koin.mp.KoinPlatform.getKoin()
        val secrets = koin.get<space.sovereigncast.hopp.platform.SecretStore>()
        secrets.put("nwc_uri", "nostr+walletconnect://" + "a".repeat(64) + "?relay=wss://r.test&secret=" + "b".repeat(64))
        secrets.put("nwc_info", """{"methods":["pay_invoice","pay_keysend"]}""")
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        repeat(5) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
        val service = controller.get()
        fun info(pkg: String, trusted: Boolean) = androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            pkg, 0, 0, 0, 0, trusted, android.os.Bundle.EMPTY, false)
        val session = service.onGetSession(info("test", false))!!
        val callback = service.callback!!
        val boost = androidx.media3.session.SessionCommand("space.sovereigncast.hopp.BOOST", android.os.Bundle.EMPTY)

        val stranger = info("com.example.stranger", trusted = false)
        val offered = callback.onConnect(session, stranger)
        assertTrue("a stranger is offered the boost", boost !in offered.availableSessionCommands.commands)
        assertTrue("a stranger sees the boost button", offered.mediaButtonPreferences.isNullOrEmpty())
        // Sent anyway: refused, and nothing paid.
        val result = callback.onCustomCommand(session, stranger, boost, android.os.Bundle.EMPTY).get()
        assertEquals(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        repeat(3) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
        val payments = kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.value.ValueService>().payments.first() }
        assertTrue("a stranger's boost was paid: $payments", payments.isEmpty())

        for (allowed in listOf(info("space.sovereigncast.hopp", trusted = false), info("com.android.systemui", trusted = true))) {
            assertTrue("${allowed.packageName} may not boost", boost in callback.onConnect(session, allowed).availableSessionCommands.commands)
        }
        controller.destroy()
    }
}
