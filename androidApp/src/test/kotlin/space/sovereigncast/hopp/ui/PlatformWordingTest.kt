package space.sovereigncast.hopp.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.ui.library.PrivacyScreen
import space.sovereigncast.hopp.ui.library.SettingsPage
import space.sovereigncast.hopp.ui.library.SettingsPageScreen
import space.sovereigncast.hopp.ui.theme.HoppTheme

/**
 * Each system in its own words (device feedback 2026-10-13: the iOS app spoke of
 * Android's notifications and Google's backup). The pages run here on Robolectric
 * with the platform Koin hands them, so iOS is set by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformWordingTest {
    @get:Rule val rule = createComposeRule()

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun on(platform: String) =
        loadKoinModules(module { single { AppInfo("0.0.1", platform, "Tablet") } })

    @Test
    fun theIosPrivacyPageNamesApplesBackup() {
        on("iOS")
        rule.setContent { HoppTheme { PrivacyScreen() } }
        rule.waitUntil(10_000) { has("Apple (iCloud backup)") }
        check(!has("Google", substring = true)) { "Google on the iOS privacy page" }
        check(!has("phone", substring = true)) { "a phone on a page that may be a tablet's" }
    }

    @Test
    fun theAndroidPrivacyPageNamesGooglesBackup() {
        on("Android")
        rule.setContent { HoppTheme { PrivacyScreen() } }
        rule.waitUntil(10_000) { has("Google (system backup)") }
    }

    @Test
    fun iosNotificationsSpeakOfIos() {
        on("iOS")
        rule.setContent { HoppTheme { SettingsPageScreen(SettingsPage.Notifications) } }
        rule.waitUntil(10_000) { has("Open iOS Settings") }
        check(!has("Android", substring = true)) { "Android on the iOS notification settings" }
    }
}
