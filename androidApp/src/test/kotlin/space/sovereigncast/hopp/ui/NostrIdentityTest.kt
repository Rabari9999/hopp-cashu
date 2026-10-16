package space.sovereigncast.hopp.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.identity.IdentityRepository

/** The optional Nostr identity, stage 1 (ADR 0021): made in the settings, it
 *  names the listener and signs their votes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NostrIdentityTest {
    @get:Rule val rule = createComposeRule()

    private val koin get() = org.koin.mp.KoinPlatform.getKoin()
    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String, substring: Boolean = false) =
        try { rule.waitUntil(10_000) { has(text, substring) } }
        catch (e: Throwable) { throw AssertionError("'$text' not on screen: " + screen(), e) }
    private fun screen(): List<String> {
        val root = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }.orEmpty().map { it.text } + n.children.flatMap(::walk)
        return root.flatMap(::walk)
    }
    /** Scrolled into view where it sits in a scrolling page; the tab bar and dialogs do not scroll. */
    private fun tap(text: String) {
        val node = rule.onNode(hasText(text) and hasClickAction())
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun subscribe(feed: String) {
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput(feed)
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
    }

    @Test
    fun anIdentityIsMadeNamedAndGreetsTheListener() {
        rule.setContent { App() }
        // Home greets only once there is something to show.
        subscribe("https://pod.test/feed.xml")
        tap("Home")
        rule.onNode(hasContentDescription("Settings")).performClick()
        waitFor("Optional — vote as a person, comment later")
        tap("Nostr identity")
        // Said before anything is published: it stays.
        waitFor("is public and stays on Nostr for good", substring = true)
        rule.onNode(hasSetTextAction()).performTextInput("Thorsten")
        tap("Create identity")
        // Hopp's three relays and the two indexes.
        waitFor("Profile published on 5 relays.")
        waitFor("npub1", substring = true)
        waitFor("Not backed up yet.")
        // The name is the boost name too, as none was set.
        rule.waitUntil(10_000) { runBlocking { koin.get<SettingsRepository>().settings.first().senderName } == "Thorsten" }
        tap("Home")
        waitFor("Thorsten", substring = true)
    }

    @Test
    fun theBackupNeedsTheSamePasswordTwice() {
        runBlocking { koin.get<IdentityRepository>().create("X") }
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Nostr identity")
        waitFor("Back up your key")
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("correct horse")
        fields[1].performTextInput("correct hors")
        waitFor("The two passwords differ.")
        fields[1].performTextInput("e")
        tap("Create backup")
        rule.waitUntil(30_000) { has("ncryptsec1", substring = true) }
        check(koin.get<IdentityRepository>().identity.value?.exported == true)
    }

    private fun vote(): String? {
        subscribe("https://pod.test/sc.xml")
        waitFor("Community")
        tap("Community"); waitFor("BIP110")
        scPosted = false; lastScAuth = null
        // BIP110's — another test may have added a suggestion below it.
        rule.onAllNodes(hasText("▲", substring = true) and hasClickAction())[0].performClick()
        // Idling each round: the vote runs on the main thread, which only moves when the UI idles.
        rule.waitUntil(10_000) { rule.waitForIdle(); scPosted }
        return lastScAuth
    }

    /** NIP-98 on the vote: one vote per person, on every device. */
    @Test
    fun aVoteIsSigned() {
        runBlocking { koin.get<IdentityRepository>().create("X") }
        rule.setContent { App() }
        val auth = vote()
        check(auth?.startsWith("Nostr ") == true) { "not NIP-98: $auth" }
    }

    @Test
    fun aListenerWhoStaysAnonymousVotesUnsigned() {
        runBlocking {
            koin.get<IdentityRepository>().create("X")
            koin.get<SettingsRepository>().update { it.copy(scAnonymous = true) }
        }
        rule.setContent { App() }
        check(vote() == null) { "signed although anonymous" }
    }

    @Test
    fun signingOutLeavesTheKeyNowhere() {
        runBlocking { koin.get<IdentityRepository>().create("X") }
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Nostr identity")
        tap("Sign out of Hopp")
        waitFor("What you published stays on Nostr.", substring = true)
        tap("Sign out")
        waitFor("Create identity")
        check(koin.get<IdentityRepository>().signer() == null)
    }

    @org.junit.After fun noAmber() { FakeAmber.installed = false; FakeAmber.reject = false }

    /** With Amber installed, signing in with it comes first; the key stays there. */
    @Test
    fun aSignerAppSignsInAndSignsTheVote() {
        FakeAmber.installed = true
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Nostr identity")
        tap("Sign in with Amber")
        waitFor("Signed in with your signer app — your key stays there.")
        check(!has("Back up your key")) { "a backup offered for a key Hopp does not hold" }
        val me = koin.get<IdentityRepository>().identity.value!!
        check(me.pubkey == FakeAmber.keys.publicKeyHex && !me.holdsKey)
        tap("Home")
        val auth = vote()
        check(auth?.startsWith("Nostr ") == true) { "not NIP-98: $auth" }
    }

    @Test
    fun noInTheSignerAppIsSaidAndLeavesNoIdentity() {
        FakeAmber.installed = true; FakeAmber.reject = true
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Nostr identity")
        tap("Sign in with Amber")
        waitFor("The signer app said no.")
        check(koin.get<IdentityRepository>().identity.value == null)
    }

    @Test
    fun withoutASignerAppThereIsNoAmberButton() {
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Nostr identity")
        waitFor("Create identity")
        check(!has("Sign in with Amber"))
    }
}
