package space.sovereigncast.hopp.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.sovereigncast.hopp.ui.library.HomeSections

/** Home's sections arranged and hidden by the listener (device feedback 2026-10-13). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLayoutTest {
    @get:Rule val rule = createComposeRule()

    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = rule.waitUntil(10_000) { has(text) }
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()
    private val settings get() = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.SettingsRepository>()
    private fun screen(): List<String> = rule.onAllNodes(isRoot()).fetchSemanticsNodes().flatMap { root ->
        fun walk(n: SemanticsNode): List<String> =
            n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } + n.children.flatMap(::walk)
        walk(root)
    }

    /** Subscribe to the test show: Ep 2 is new; Ep 1, the back catalogue, stays off Home. */
    private fun home() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        tap("Home"); waitFor("Your shows"); waitFor("New")
    }

    /** Since 0.4.0 Hopp's own order puts listening first (Dennis, maintainer 2026-11-02): what is new before the shows. */
    @Test
    fun aNewInstallListensFirst() {
        runBlocking { settings.update { it.copy(homeOrder033 = true) } }
        home()
        val s = screen()
        check(s.indexOf("New") < s.indexOf("Your shows")) { "not in Hopp's new order: $s" }
    }

    /** Who had Hopp's order before 0.4.0 keeps it: the shows before what is new (device feedback 2026-10-13). */
    @Test
    fun theShowsComeBeforeWhatIsNew() {
        home()
        val s = screen()
        check(s.indexOf("Your shows") < s.indexOf("New")) { "not in Hopp's order: $s" }
        check("From your shows" !in s && "Ep 1" !in s) { "the back catalogue is on Home: $s" }
    }

    @Test
    fun theListenersOrderIsHomesOrder() {
        runBlocking { settings.update { it.copy(homeOrder = listOf(HomeSections.NEW, HomeSections.YOUR_SHOWS)) } }
        home()
        val s = screen()
        check(s.indexOf("New") < s.indexOf("Your shows")) { "not in the listener's order: $s" }
    }

    /** The library's tag filter stays in the library (maintainer 2026-11-04): Home has all the shows. */
    @Test
    fun theLibrarysTagLeavesHomeAlone() {
        home()
        runBlocking {
            val podcasts = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.PodcastRepository>()
            podcasts.setTags(podcasts.shows.first().single().id, listOf("Tech"))
            settings.update { s ->
                s.copy(homeHidden = HomeSections.default.toSet() - HomeSections.YOUR_SHOWS, libraryTag = space.sovereigncast.hopp.data.Settings.TAG_UNTAGGED)
            }
        }
        rule.waitUntil(10_000) { !has("New") }
        waitFor("Your shows")
        check(has("Test Show")) { "the library's filter took the show off Home: ${screen()}" }
    }

    /** From Home to its settings, a section switched off, and back: it is gone. */
    @Test
    fun aSectionSwitchedOffLeavesHome() {
        home()
        rule.onNode(hasContentDescription("Customize Home") and hasClickAction()).performClick(); waitFor("Your shows")
        rule.onNode(isToggleable() and hasContentDescription("Your shows")).performClick()
        rule.waitUntil(10_000) { runBlocking { HomeSections.YOUR_SHOWS in settings.settings.first().homeHidden } }
        // Arranged in place since 0.4.0: "Done" goes back to Home.
        rule.onNode(hasText("Done") and hasClickAction()).performClick()
        waitFor("New")
        check(!has("Your shows")) { "the hidden section is still on Home: ${screen()}" }
        check(has("Ep 2")) { "New went too" }
    }

    /** "Customize Home" at the top, beside the greeting, not below everything (device feedback 2026-10-17). */
    @Test
    fun customizeIsAtTheTop() {
        home()
        rule.onNode(hasContentDescription("Customize Home") and hasClickAction()).assertIsDisplayed().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(isToggleable() and hasContentDescription("Your shows")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Done") and hasClickAction()).performClick()
        waitFor("New")
        check(!has("Customize Home")) { "the button below is still there" }
    }

    /** The name the listener boosts with greets them on Home (device feedback 2026-10-13). */
    @Test
    fun homeGreetsByTheBoostName() {
        runBlocking { settings.update { it.copy(senderName = "Satoshi") } }
        home()
        rule.waitUntil(10_000) { screen().any { "Satoshi" in it } }
    }

    /** No boost name, but a Nostr profile with one: that greets (device feedback 2026-10-21 —
     *  restored from Nostr, Home greeted no one, even after a restart). */
    @Test
    fun homeGreetsByTheNostrNameWhenThereIsNoBoostName() {
        runBlocking {
            org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.identity.IdentityRepository>().create("Nakamoto")
            settings.update { it.copy(senderName = "") }
        }
        home()
        rule.waitUntil(10_000) { screen().any { "Nakamoto" in it } }
    }

    /** Maintainer 2026-10-22: sync says how it went, a tap tells a little more — since 2026-11-03 at the profile
     *  picture: off, its menu asks to turn it on; on, a mark at the picture and a row that opens the sync sheet. */
    @Test
    fun homeShowsHowSyncWent() {
        home()
        check(rule.onAllNodes(hasTestTag("profile-button")).fetchSemanticsNodes().isEmpty()) { "no account, no picture" }
        runBlocking { org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.identity.IdentityRepository>().create("Nakamoto") }
        openProfileMenu()
        waitFor("Sync is off · turn it on ›")
        rule.onNode(hasTestTag("sync-row")).performClick()
        waitFor("Sync across devices")
        rule.onNode(hasContentDescription("Back")).performClick()
        runBlocking { org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.sync.SyncManager>().enableAndWait() }
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("In sync", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shoot("picture")
        openProfileMenu()
        shoot("menu")
        rule.onNode(hasTestTag("sync-row")).performClick()
        waitFor("In sync")
        // Each relay listed, one short line each (maintainer 2026-10-29).
        check(has("nos.lol") && has("Still to send") && has("nothing")) { screen().toString() }
        tap("Settings")
        waitFor("Sync across devices")
    }

    /** Maintainer 2026-11-08: the menu one entry shorter, the profile opened from its head (picture and name). */
    @Test @Config(qualifiers = "en-w411dp-h891dp-night")
    fun theProfileOpensFromTheMenusHead() {
        runBlocking {
            settings.update { it.copy(design = space.sovereigncast.hopp.data.Settings.DESIGN_RELAY) }
            org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.identity.IdentityRepository>().create("Nakamoto")
        }
        home()
        openProfileMenu()
        check(!has("Edit profile")) { "an entry of its own still: ${screen()}" }
        shoot("menu-relay")
        // The Nostr page's switches with room before them, a line before what goes out in public (2026-11-08).
        tap("Nostr identity")
        waitFor("Nostr contacts")
        // Each part under its heading; shared shows only while sharing; the rare things folded (2026-11-08).
        check(has("Sync") && has("Public") && has("Shared shows") && !has("Relays")) { screen().toString() }
        shoot("nostr-top")
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("Advanced"))
        tap("Advanced")
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("Relays"))
        shoot("nostr-relay")
        rule.onNode(hasContentDescription("Back")).performClick()
        openProfileMenu()
        tap("Nakamoto")
        rule.waitUntil(10_000) { has("Your Nostr profile") }
    }

    private fun openProfileMenu() {
        val pic = hasTestTag("profile-button") and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(pic).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(pic).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("sync-row")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The profile menu's sync row, to the sync sheet. */
    private fun openSyncSheet() {
        openProfileMenu()
        rule.onNode(hasTestTag("sync-row")).performClick()
    }

    /**
     * Delta audit 4 2026-10-23: the sync sheet on a small phone and with larger text, German and English.
     * Every label on one line, whole, beside its value; every value inside the table. The labels are
     * measured where they are drawn; a picture of each goes to build/sync-sheet/ to look at.
     */
    @Test @Config(qualifiers = "en-w320dp-h640dp") fun theSyncSheetFitsASmallPhone() = syncSheetFits("In sync", 1f)
    @Test @Config(qualifiers = "de-w320dp-h640dp") fun theSyncSheetFitsASmallPhoneInGerman() = syncSheetFits("Abgeglichen", 1f)
    @Test @Config(qualifiers = "en-w360dp-h640dp") fun theSyncSheetFitsLargerText() = syncSheetFits("In sync", 1.3f)
    @Test @Config(qualifiers = "de-w360dp-h640dp") fun theSyncSheetFitsLargerTextInGerman() = syncSheetFits("Abgeglichen", 1.3f)

    private fun syncSheetFits(done: String, fontScale: Float) {
        org.robolectric.RuntimeEnvironment.setFontScale(fontScale)
        runBlocking { org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.PodcastRepository>().subscribe("https://pod.test/feed.xml") }
        rule.setContent { App() }
        runBlocking {
            val koin = org.koin.mp.KoinPlatform.getKoin()
            koin.get<space.sovereigncast.hopp.identity.IdentityRepository>().create("Nakamoto")
            koin.get<space.sovereigncast.hopp.sync.SyncManager>().enableAndWait()
        }
        openSyncSheet()
        waitFor(done)
        rule.waitForIdle()
        // The relays are listed one by one after a round, not as a row of the table.
        val labels = listOf("Last brought", "Still to send", "Zuletzt geholt", "Noch zu senden").filter(::has)
        check(labels.size == 2) { "not the sheet's rows: ${screen()}" }
        check(listOf("relay.damus.io", "nos.lol", "relay.primal.net").all(::has)) { "not every relay listed: ${screen()}" }
        val report = labels.map { label ->
            val node = rule.onAllNodes(hasText(label)).fetchSemanticsNodes().single()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val l = layouts.first()
            // The table the rows are in, and the value beside this label: the text at the same height.
            val table = node.parent!!
            val box = node.boundsInRoot
            val value = table.children.filter { it.id != node.id && it.boundsInRoot.top < box.bottom && it.boundsInRoot.bottom > box.top }.single()
            val line = "$label: lines=${l.lineCount} ellipsis=${l.isLineEllipsized(0)} text=${l.getLineRight(0)}/${l.size.width}px " +
                "label=$box value=${value.boundsInRoot} table=${table.boundsInRoot}"
            println(line)
            // Not hasVisualOverflow: the layout a label reports is as wide as the row it may take, its size only as wide as
            // the text — so it always "overflows" (delta audit 4: what failed there). Where the line ends is what is seen.
            check(l.lineCount == 1 && !l.isLineEllipsized(0) && l.getLineRight(0) <= l.size.width) { "cut off: $line" }
            check(box.right <= value.boundsInRoot.left && value.boundsInRoot.right <= table.boundsInRoot.right) { "overlaps or runs off: $line" }
            line
        }
        // The two buttons: one line each, whole, and on the screen.
        val window = rule.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        val buttons = listOf("Sync now", "Settings", "Jetzt abgleichen", "Einstellungen").filter { rule.onAllNodes(hasText(it), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        check(buttons.size == 2) { "not the sheet's buttons: ${screen()}" }
        val pressed = buttons.map { text ->
            val node = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().single()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val l = layouts.first()
            val line = "$text: lines=${l.lineCount} ellipsis=${l.isLineEllipsized(0)} text=${l.getLineRight(0)}/${l.size.width}px " +
                "size=${l.layoutInput.style.fontSize} at ${node.boundsInRoot} window=$window"
            println(line)
            check(l.lineCount == 1 && !l.isLineEllipsized(0) && l.getLineRight(0) <= l.size.width) { "cut off: $line" }
            check(node.boundsInRoot.bottom <= window) { "below the screen: $line" }
            line
        }
        java.io.File("build/sync-sheet").mkdirs()
        val name = "${java.util.Locale.getDefault().language}-${rule.onAllNodes(isRoot()).fetchSemanticsNodes().first().size.width}px-$fontScale"
        // Each window drawn as it is — the sheet is one of its own (captureToImage waits on a popup forever).
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<android.view.View>
        rule.runOnIdle {
            views.forEachIndexed { i, v ->
                val bitmap = android.graphics.Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(bitmap))
                java.io.FileOutputStream("build/sync-sheet/$name-$i.png").use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        java.io.File("build/sync-sheet/$name.txt").writeText((report + pressed).joinToString("\n"))
    }

    /**
     * Maintainer 2026-10-29: sync failed and the sheet never said which relay or why. Failed, it lists each
     * relay with its answer in a few words, and one tap shows what it answered per document, in its own words.
     */
    @Test @Config(qualifiers = "en-w320dp-h640dp") fun theSyncSheetSaysWhichRelayFailedAndWhy() =
        relaysExplained("Refused: too many requests", "Not reachable", "Relay’s reply", "Sync key")
    @Test @Config(qualifiers = "de-w320dp-h640dp") fun theSyncSheetSaysWhichRelayFailedAndWhyInGerman() =
        relaysExplained("Abgelehnt: zu viele Anfragen", "Nicht erreichbar", "Antwort des Relays", "Sync-Schlüssel")

    /** Two of three are enough, and the third's reason is there all the same (maintainer 2026-10-29). */
    @Test @Config(qualifiers = "de-w320dp-h640dp") fun aRelayThatFailedInASuccessfulRoundSaysWhy() =
        relaysExplained("Abgelehnt: zu viele Anfragen", null, "Antwort des Relays", "Sync-Schlüssel", done = "Abgeglichen")

    private fun relaysExplained(refused: String, unreachable: String?, reply: String, keyring: String, done: String? = null) {
        val (limited, _, gone) = space.sovereigncast.hopp.identity.IdentityRepository.DEFAULT_RELAYS
        OkRelays.refusing = limited to "rate-limited: slow down"
        OkRelays.unreachable = gone.takeIf { unreachable != null }
        try {
            // Home comes with a show; sync's state is at the profile picture on its bar.
            runBlocking { org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.PodcastRepository>().subscribe("https://pod.test/feed.xml") }
            rule.setContent { App() }
            runBlocking {
                val koin = org.koin.mp.KoinPlatform.getKoin()
                koin.get<space.sovereigncast.hopp.identity.IdentityRepository>().create("Nakamoto")
                koin.get<space.sovereigncast.hopp.sync.SyncManager>().enableAndWait()
            }
            openSyncSheet()
            runCatching { waitFor(refused) }.onFailure { error("no refusal on the sheet: ${screen()}") }
            unreachable?.let { check(has(it)) { "no line for the relay out of reach: ${screen()}" } }
            done?.let { check(has(it)) { "not a round that went through: ${screen()}" } }
            check(has(limited.removePrefix("wss://"))) { "the relay not named: ${screen()}" }
            shoot(if (done != null) "through" else "failed")
            rule.onNode(hasText(limited.removePrefix("wss://")) and hasClickAction(), useUnmergedTree = false).performClick()
            waitFor(reply)
            check(has("rate-limited: slow down")) { "not in its own words: ${screen()}" }
            check(has(keyring)) { "not per document: ${screen()}" }
            shoot("relay")
        } finally {
            OkRelays.refusing = null
            OkRelays.unreachable = null
        }
    }

    /** Each window as it is drawn — a sheet is one of its own (captureToImage waits on a popup forever). */
    private fun shoot(what: String) {
        java.io.File("build/sync-sheet").mkdirs()
        val name = "${java.util.Locale.getDefault().language}-$what"
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<android.view.View>
        rule.runOnIdle {
            views.forEachIndexed { i, v ->
                val bitmap = android.graphics.Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(bitmap))
                java.io.FileOutputStream("build/sync-sheet/$name-$i.png").use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    /** Device feedback 2026-10-17: a long greeting shrinks to one line instead of running off. */
    @Test @Config(qualifiers = "en-w360dp-h800dp")
    fun aLongGreetingFitsOneLine() {
        // In the morning here, whenever it runs: at night "Still up, Thorsten?" fits as it is (CI 2026-10-24, 4 am).
        val zone = java.util.TimeZone.getDefault()
        val utcHour = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).hour
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(java.time.ZoneOffset.ofHours(((8 - utcHour + 36) % 24) - 12)))
        try { longGreeting() } finally { java.util.TimeZone.setDefault(zone) }
    }

    private fun longGreeting() {
        runBlocking { settings.update { it.copy(senderName = "Thorsten") } }
        home()
        rule.waitUntil(10_000) { screen().any { "Thorsten" in it } }
        val node = rule.onAllNodes(androidx.compose.ui.test.hasText("Thorsten", substring = true)).fetchSemanticsNodes().first()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val l = layouts.first()
        check(l.lineCount == 1 && !l.isLineEllipsized(0) && l.getLineRight(0) <= l.size.width) { "the greeting is cut off" }
        // The greeting goes with the hour: "Hello Thorsten" fits as it is, "Good morning, Thorsten" does not.
        check(l.layoutInput.text.length <= 16 || l.layoutInput.style.fontSize.value < 24f) { "not made smaller: ${l.layoutInput.text}" }
    }

    /**
     * Device feedback 2026-10-16: "Your shows" opened scrolled by one. The show
     * with a Release Boosting round moves to the front once its instance answers,
     * after the strip is shown, and the strip kept the old first show in view.
     */
    @Test
    fun yourShowsStartsAtTheFirstShow() {
        // A cold start: the shows are in the database, the instance answers later.
        val podcasts = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.PodcastRepository>()
        kotlinx.coroutines.runBlocking {
            podcasts.subscribe("https://pod.test/alpha.xml")
            podcasts.subscribe("https://pod.test/sc.xml")
        }
        rule.setContent { App() }
        waitFor("Your shows")
        // The tile in the strip — the rows under "New" start at the edge (0).
        fun left(t: String) = rule.onAllNodes(hasText(t) and hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot.left }.filter { it > 0f }.minOrNull()
        // First alphabetical: Alpha, then Nodesignal.
        rule.waitUntil(10_000) { rule.waitForIdle(); (left("Alpha Show") ?: 999f) < (left("Nodesignal") ?: 0f) }
        // The instance has answered (its round is listed to take part in): Nodesignal moves first.
        rule.waitUntil(30_000) { rule.waitForIdle(); has("Take part") }
        rule.waitForIdle()
        check((left("Nodesignal") ?: 999f) < 100f) { "the strip does not start at its first show — Nodesignal at ${left("Nodesignal")}" }
        val strip = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)).fetchSemanticsNodes()
        check(strip.all { it.config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange].value() == 0f }) { "a strip does not start at its first show" }
    }
}
