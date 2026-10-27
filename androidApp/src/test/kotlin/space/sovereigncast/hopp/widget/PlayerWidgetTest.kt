package space.sovereigncast.hopp.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import io.ktor.client.engine.mock.respond
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.sovereigncast.hopp.ui.TestApp

/** The home-screen widget (0.3.1): the lock screen's player, four cells wide, one or two high. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class PlayerWidgetTest {
    private val now = WidgetNow(
        title = "E299 – Gemischter Satz", show = "Nodesignal", chapter = "Bark", artworkUrl = null,
        positionMs = 30 * 60_000L, durationMs = 71 * 60_000L, playing = true, boostSats = 100,
        skipBackSeconds = 15, skipForwardSeconds = 30,
    )

    @Test
    fun twoCellsHighItIsTheLockScreensPlayer() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(320.dp, 160.dp))
        setContext(org.robolectric.RuntimeEnvironment.getApplication())
        provideComposable { PlayerWidget().Content(now, null) }
        onNode(hasText("E299 – Gemischter Satz")).assertExists()
        onNode(hasText("Nodesignal · Bark")).assertExists()
        onNode(hasText("41 min left")).assertExists()
        onNode(hasContentDescription("Back 15 seconds")).assertExists()
        onNode(hasContentDescription("Pause")).assertExists()
        onNode(hasContentDescription("Forward 30 seconds")).assertExists()
        onNode(hasContentDescription("Next chapter")).assertExists()
        onNode(hasText("100")).assertExists()
    }

    @Test
    fun oneCellHighItKeepsTitlePlayAndBoost() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(320.dp, 70.dp))
        setContext(org.robolectric.RuntimeEnvironment.getApplication())
        provideComposable { PlayerWidget().Content(now.copy(playing = false, boostSats = null), null) }
        onNode(hasText("E299 – Gemischter Satz")).assertExists()
        onNode(hasContentDescription("Play")).assertExists()
        onNode(hasContentDescription("Next chapter")).assertDoesNotExist()
        onNode(hasContentDescription("Boost")).assertDoesNotExist()
    }

    /** Device feedback 2026-10-26: paused from the widget, its button stayed "pause" — the widget
     *  draws what the player state says now, not what it read once (Glance's test harness does not
     *  recompose, so the change itself is checked on a device). */
    @Test
    fun itDrawsWhatThePlayerSaysNow() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(320.dp, 160.dp))
        setContext(org.robolectric.RuntimeEnvironment.getApplication())
        WidgetState.now.value = now.copy(playing = false, positionMs = 40 * 60_000L)
        try {
            provideComposable { PlayerWidget().Live(lastHeard = now) }
            onNode(hasContentDescription("Play")).assertExists()
            onNode(hasText("31 min left")).assertExists()
        } finally {
            WidgetState.now.value = null
        }
    }

    @Test
    fun nothingHeardYetItOpensHopp() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(320.dp, 160.dp))
        setContext(org.robolectric.RuntimeEnvironment.getApplication())
        provideComposable { PlayerWidget().Content(null, null) }
        onNode(hasText("Nothing playing – tap to open Hopp")).assertExists()
    }

    /** Zetti's beta test 2026-10-27: a feed's cover address answered with more than a cover — said
     *  up front or only while it streams — is not read whole; the widget goes without a cover. */
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // real decoding: text is no picture
    fun anOversizedCoverIsNotRead() = kotlinx.coroutines.runBlocking {
        val big = Artwork.MAX_BYTES + 1
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { req ->
            when (req.url.encodedPath) {
                "/declared.jpg" -> respond(io.ktor.utils.io.ByteReadChannel(ByteArray(16)), io.ktor.http.HttpStatusCode.OK,
                    io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentLength, big.toString()))
                "/endless.jpg" -> respond(ByteArray(big.toInt()))
                else -> respond("not a picture")
            }
        })
        org.junit.Assert.assertNull(Artwork.load("https://cdn.test/declared.jpg", client))
        org.junit.Assert.assertNull(Artwork.load("https://cdn.test/endless.jpg", client))
        org.junit.Assert.assertNull(Artwork.load("https://cdn.test/text.jpg", client))
    }
}
