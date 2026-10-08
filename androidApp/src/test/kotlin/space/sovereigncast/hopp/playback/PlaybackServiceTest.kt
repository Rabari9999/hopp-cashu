package space.sovereigncast.hopp.playback

import androidx.media3.common.Player
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
}
