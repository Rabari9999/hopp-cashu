package space.sovereigncast.hopp.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.sovereigncast.hopp.MainActivity
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.SettingsRepository
import org.junit.Assert.assertEquals

/**
 * Review 2026-10-31: the status bar's icons followed the system's theme, not the one chosen in Hopp —
 * dark icons on Hopp's dark background with "Dark" on a light phone. Robolectric's phone is light.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
class SystemBarsThemeTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var activity: ActivityScenario<MainActivity>? = null

    @After fun close() { activity?.close() }

    private fun choose(theme: String) {
        kotlinx.coroutines.runBlocking { org.koin.mp.KoinPlatform.getKoin().get<SettingsRepository>().update { it.copy(theme = theme) } }
        rule.waitForIdle()
    }

    /** Whether the bars draw dark icons, for a light background. */
    private fun lightBars(): Pair<Boolean, Boolean> {
        var bars = false to false
        activity!!.onActivity { a ->
            val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
            bars = c.isAppearanceLightStatusBars to c.isAppearanceLightNavigationBars
        }
        return bars
    }

    @Test
    fun theBarsFollowTheThemeChosenInHopp() {
        activity = ActivityScenario.launch(Intent(Intent.ACTION_MAIN).setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java))
        rule.waitForIdle()
        assertEquals("the system's: light", true to true, lightBars())
        choose(Settings.THEME_DARK)
        rule.waitUntil(5_000) { lightBars() == (false to false) }
        choose(Settings.THEME_SYSTEM)
        rule.waitUntil(5_000) { lightBars() == (true to true) }
    }
}
