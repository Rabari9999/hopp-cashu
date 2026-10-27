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

/** The service starts — session, library callback, player — and offers next/previous even
 *  for a single episode (ADR 0012): the media keys of headsets and the car. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class PlaybackServiceTest {
    @Test
    fun theServiceStartsAndOffersChapterJumps() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        repeat(5) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
        val session = service.onGetSession(androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "test", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false))!!
        assertTrue(session.player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(session.player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        // The lock screen: skip back and forward by the listener's seconds (10 and 30 by default),
        // the next chapter — and no boost without a wallet.
        assertEquals(listOf("Back 10 seconds", "Forward 30 seconds", "Next chapter"), session.mediaButtonPreferences.map { it.displayName.toString() })
        assertEquals(androidx.media3.session.CommandButton.ICON_SKIP_BACK_10, session.mediaButtonPreferences[0].icon)
        assertEquals(Player.COMMAND_SEEK_BACK, session.mediaButtonPreferences[0].playerCommand)
        assertEquals(Player.COMMAND_SEEK_FORWARD, session.mediaButtonPreferences[1].playerCommand)
        // The next chapter is a command of its own: next on a headset or the steering wheel skips.
        assertEquals("space.sovereigncast.hopp.NEXT_CHAPTER", session.mediaButtonPreferences[2].sessionCommand?.customAction)
        controller.destroy()
    }

    /** Maintainer 2026-10-27: "forward" on the car's steering wheel jumped to the next episode.
     *  The media keys' next and previous skip by the listener's seconds, as forward and back. */
    @Test
    fun theSteeringWheelsNextSkipsForward() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        repeat(5) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
        val service = controller.get()
        val session = service.onGetSession(androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "test", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false))!!
        val player = session.player
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri("https://cdn.test/1.mp3"), 60_000)
        ShadowLooper.idleMainLooper()
        player.seekToNext()
        ShadowLooper.idleMainLooper()
        assertEquals(90_000L, player.currentPosition)
        player.seekToPrevious()
        ShadowLooper.idleMainLooper()
        assertEquals(80_000L, player.currentPosition)
        // Every controller is offered the next chapter, and it is done.
        val car = androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "com.google.android.projection.gearhead", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false)
        val next = androidx.media3.session.SessionCommand("space.sovereigncast.hopp.NEXT_CHAPTER", android.os.Bundle.EMPTY)
        assertTrue(next in service.callback!!.onConnect(session, car).availableSessionCommands.commands)
        assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS, service.callback!!.onCustomCommand(session, car, next, android.os.Bundle.EMPTY).get().resultCode)
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
        assertEquals(listOf("Back 10 seconds", "Forward 30 seconds", "Boost", "Next chapter"), session.mediaButtonPreferences.map { it.displayName.toString() })
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
        assertTrue("a stranger sees the boost button", offered.mediaButtonPreferences.orEmpty().none { it.sessionCommand == boost })
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

    /** The notification and the lock screen skip by the listener's intervals
     *  (device feedback 2026-10-14: a fixed 10 and 30 seconds). */
    @Test
    fun theLockScreenSkipsByTheListenersIntervals() {
        val settings = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.SettingsRepository>()
        kotlinx.coroutines.runBlocking { settings.update { it.copy(skipBackSeconds = 15, skipForwardSeconds = 45) } }
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        repeat(5) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
        val player = controller.get().onGetSession(androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "test", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false))!!.player
        assertEquals(15_000L, player.seekBackIncrement)
        assertEquals(45_000L, player.seekForwardIncrement)
        controller.destroy()
    }
}
