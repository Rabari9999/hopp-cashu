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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.sovereigncast.hopp.contacts.Contact
import space.sovereigncast.hopp.contacts.ContactsRead
import space.sovereigncast.hopp.contacts.PublicSignal
import space.sovereigncast.hopp.contacts.SharedShow

/**
 * What the listener's Nostr contacts listen to (ADR 0024), laid out on a phone: Discover's section,
 * a show's row and its sheet, the Nostr page's section — device feedback 2026-11-02: the section's
 * title stood one letter wide, the row was not in line with the page. Pictures in
 * build/contacts-layout, to look at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "de-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NostrContactsLayoutTest {
    @get:Rule val rule = createComposeRule()

    private val koin get() = org.koin.mp.KoinPlatform.getKoin()
    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = rule.waitUntil(15_000) { has(text) }
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()

    /** A listener who looks at what contacts share — keys held, contacts read a minute ago. */
    private fun looking() = runBlocking {
        val identity = koin.get<space.sovereigncast.hopp.identity.IdentityRepository>()
        val me = space.sovereigncast.hopp.nostr.KeyPair.generate()
        identity.importKey(space.sovereigncast.hopp.nostr.Bech32.encode("nsec", me.secretKey))
        val now = System.currentTimeMillis() / 1000
        val inbox = space.sovereigncast.hopp.nostr.KeyPair.generate()
        koin.get<space.sovereigncast.hopp.platform.SecretStore>().put("contacts_keys",
            """{"owner":"${me.publicKeyHex}","inbox":"${inbox.secretKey.joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }}","note":"n","at":$now}""")
        val anna = Contact("a".repeat(64), mutual = true, inbox = "c".repeat(64), share = "d".repeat(64), name = "Anna Beispiel",
            shows = listOf(SharedShow(null, "https://pod.test/feed.xml", "Test Show", now - 100), SharedShow(null, "https://pod.test/alpha.xml", "Alpha Show", now - 50)))
        val ben = Contact("b".repeat(64), mutual = false, name = "Ben mit einem recht langen Namen",
            signals = listOf(PublicSignal("guid-boosted", boost = true, at = now - 10)))
        val read = ContactsRead(setOf(anna.pubkey, ben.pubkey), mapOf(anna.pubkey to anna, ben.pubkey to ben), now - 60,
            looked = mapOf("guid-boosted" to SharedShow("guid-boosted", "https://pod.test/novalue.xml", "Ein geboosteter Podcast mit langem Titel", 0)))
        koin.get<space.sovereigncast.hopp.db.HoppDatabase>().contactsQueries.put(kotlinx.serialization.json.Json.encodeToString(ContactsRead.serializer(), read), read.at)
    }

    @Test
    fun theContactsSectionsFitAPhone() {
        looking()
        rule.setContent { App() }
        tap("Entdecken")
        waitFor("Bei deinen Nostr-Kontakten")
        rule.onAllNodesWithText("Bei deinen Nostr-Kontakten").onFirst().performScrollTo()
        rule.waitForIdle()
        shoot("discover")
        // A subscribed show both share: its row of faces, in line with the page; then who.
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Als Feed öffnen"); waitFor("Abonnieren")
        shoot("preview")
        tap("Abonnieren"); waitFor("✓ Abonniert")
        // The show's page — the preview opens it once subscribed.
        if (!has("Folgen durchsuchen")) tap("✓ Abonniert")
        waitFor("Folgen durchsuchen")
        shoot("show")
        rule.onNode(hasText("deiner Nostr-Kontakte", substring = true)).performClick()
        waitFor("teilt das Abo")
        shoot("who")
    }

    /** The Nostr page's section: both switches, the counts. */
    @Test
    fun theNostrPageSectionFitsAPhone() {
        looking()
        rule.setContent { App() }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Einstellungen")).performClick()
        waitFor("Nostr-Identität")
        // Below the fold: a tap lands only once it is on the screen.
        rule.onNode(hasText("Nostr-Identität") and hasClickAction()).performScrollTo().performClick()
        waitFor("Nostr-Kontakte")
        rule.onAllNodesWithText("Nostr-Kontakte").onFirst().performScrollTo()
        rule.waitForIdle()
        shoot("nostr")
        // Every show and whether it is shared, one tap away (maintainer 2026-11-02).
        rule.onNode(hasText("Geteilte Shows") and hasClickAction()).performScrollTo().performClick()
        waitFor("Noch keine Abos")
        shoot("shared-shows")
    }

    /** Sync on: its line and, in one row, sync now, the key to show and to renew (maintainer 2026-11-03). */
    @Test
    fun theSyncRowFitsAPhone() {
        looking()
        runBlocking { koin.get<space.sovereigncast.hopp.data.SettingsRepository>().update { it.copy(nostrSync = true) } }
        rule.setContent { App() }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Einstellungen")).performClick()
        waitFor("Nostr-Identität")
        rule.onNode(hasText("Nostr-Identität") and hasClickAction()).performScrollTo().performClick()
        waitFor("Schlüssel zeigen")
        rule.onAllNodesWithText("Schlüssel zeigen").onFirst().performScrollTo()
        rule.waitForIdle()
        shoot("sync-row")
    }

    /** "Support Hopp" (maintainer 2026-11-02): the page, without a wallet — the way to connect one, the address. */
    @Test
    fun theSupportPageFitsAPhone() {
        rule.setContent { App() }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Einstellungen")).performClick()
        waitFor("Hopp unterstützen")
        rule.onNode(hasText("Hopp unterstützen") and hasClickAction()).performScrollTo().performClick()
        waitFor("Hopp ist frei")
        rule.waitForIdle()
        shoot("support")
        rule.onNode(hasText("sovereigncast@getalby.com")).performScrollTo()
        rule.waitForIdle()
        shoot("support-end")
    }

    /** "Als Nächstes" on Home and in the library (Dennis, maintainer 2026-11-02) — pictures to look at. */
    @Test
    fun upNextOnHomeAndInTheLibrary() {
        rule.setContent { App() }
        tap("Entdecken")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Als Feed öffnen"); waitFor("Abonnieren")
        tap("Abonnieren"); waitFor("✓ Abonniert")
        if (!has("Folgen durchsuchen")) tap("✓ Abonniert")
        waitFor("Folgen durchsuchen")
        val more = rule.onAllNodes(hasContentDescription("Mehr") and hasClickAction())
        more[0].performClick(); waitFor("Als Nächstes abspielen"); tap("Als Nächstes abspielen")
        more[1].performClick(); waitFor("Am Ende abspielen"); tap("Am Ende abspielen")
        tap("Home"); waitFor("Als Nächstes")
        rule.waitForIdle()
        shoot("home-queue")
        tap("Bibliothek"); waitFor("Shows")
        rule.waitForIdle()
        shoot("library")
    }

    /** The Spanish draft on a phone (maintainer 2026-11-02): the settings and Home, to look at. */
    @Test @Config(qualifiers = "es-w360dp-h800dp")
    fun theSpanishDraftFitsAPhone() {
        rule.setContent { App() }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Ajustes")).performClick()
        waitFor("Apoyar a Hopp")
        rule.waitForIdle()
        shoot("es-settings")
        rule.onNode(hasText("Apoyar a Hopp") and hasClickAction()).performScrollTo().performClick()
        waitFor("Hopp es libre")
        rule.waitForIdle()
        shoot("es-support")
    }

    /**
     * Maintainer 2026-11-03: tags on a show, the library's row of them, a tag's own page; the tour's language;
     * the profile picture's menu — pictures to look at.
     */
    @Test
    fun tagsTheTourLanguageAndTheProfileMenu() {
        looking()
        rule.setContent { App() }
        tap("Entdecken")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Als Feed öffnen"); waitFor("Abonnieren")
        tap("Abonnieren"); waitFor("✓ Abonniert")
        if (!has("Folgen durchsuchen")) tap("✓ Abonniert")
        waitFor("Folgen durchsuchen")
        rule.onNode(hasContentDescription("Show-Optionen")).performClick()
        waitFor("Tags …"); tap("Tags …")
        waitFor("Neuer Tag")
        rule.onAllNodes(hasSetTextAction()).let { it[it.fetchSemanticsNodes().size - 1] }.performTextInput("Bitcoin")
        tap("Hinzufügen")
        rule.waitForIdle()
        shoot("tags-sheet")
        tap("Fertig")
        tap("Bibliothek"); waitFor("Ohne Tag")
        rule.waitForIdle()
        shoot("tags-library")
        rule.onNode(hasText("Bitcoin") and hasClickAction()).performTouchInput { longClick() }
        waitFor("Shows mit diesem Tag")
        rule.waitForIdle()
        shoot("tag-page")
        // The profile picture's menu, in the top bar beside the cogwheel.
        tap("Home")
        rule.onNode(hasContentDescription("Dein Profil")).performClick()
        waitFor("Aus Hopp abmelden")
        rule.waitForIdle()
        shoot("profile-menu")
    }

    @Test
    fun theTourOffersItsLanguage() {
        runBlocking { koin.get<space.sovereigncast.hopp.data.SettingsRepository>().update { it.copy(onboardingDone = false) } }
        rule.setContent { App() }
        waitFor("Überspringen")
        rule.waitForIdle()
        shoot("tour")
    }

    /** A large window (maintainer 2026-11-03): Home in two columns; the library's tag row with "+ Tag" and its sheet. */
    @Test @Config(qualifiers = "de-w1280dp-h800dp")
    fun homeInTwoColumnsAndANewTag() {
        rule.setContent { App() }
        tap("Entdecken")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Als Feed öffnen"); waitFor("Abonnieren")
        tap("Abonnieren"); waitFor("✓ Abonniert")
        if (!has("Folgen durchsuchen")) tap("✓ Abonniert")
        waitFor("Folgen durchsuchen")
        val more = rule.onAllNodes(hasContentDescription("Mehr") and hasClickAction())
        more[0].performClick(); waitFor("Als Nächstes abspielen"); tap("Als Nächstes abspielen")
        tap("Home"); waitFor("Als Nächstes")
        rule.waitForIdle()
        shoot("home-wide")
        tap("Bibliothek"); waitFor("Shows mit Tags ordnen")
        rule.waitForIdle()
        shoot("library-wide")
        tap("+ Tag"); waitFor("Welche Shows bekommen ihn?")
        rule.waitForIdle()
        shoot("new-tag")
    }

    /** Each window as it is drawn — a sheet is one of its own. */
    private fun shoot(what: String) {
        java.io.File("build/contacts-layout").mkdirs()
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<android.view.View>
        rule.runOnIdle {
            views.forEachIndexed { i, v ->
                val bitmap = android.graphics.Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(bitmap))
                java.io.FileOutputStream("build/contacts-layout/$what-$i.png").use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]
