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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.LocalSigner
import space.sovereigncast.hopp.nostr.RelayConnector
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.RelaySession
import space.sovereigncast.hopp.sync.SyncDocs
import space.sovereigncast.hopp.sync.SyncEntry
import space.sovereigncast.hopp.sync.SyncRelays

/** Relays that keep what they are sent and answer filters by kind, author and `d` tag. */
private class KeepingRelays : RelayConnector {
    private val stored = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())

    override suspend fun connect(url: String) = object : RelaySession {
        private val channel = Channel<String>(Channel.UNLIMITED)
        override val incoming = kotlinx.coroutines.flow.flow { for (m in channel) emit(m) }
        override suspend fun send(message: String) {
            val arr = org.json.JSONArray(message)
            when (arr.getString(0)) {
                "REQ" -> {
                    val sub = arr.getString(1)
                    val filters = (2 until arr.length()).map { arr.getJSONObject(it) }
                    stored.toList().filter { e -> filters.any { matches(it, e) } }.forEach { e ->
                        channel.trySend(org.json.JSONArray().put("EVENT").put(sub).put(e).toString())
                    }
                    channel.trySend("""["EOSE","$sub"]""")
                }
                "EVENT" -> {
                    val e = arr.getJSONObject(1)
                    stored += e
                    channel.trySend("""["OK","${e.getString("id")}",true,""]""")
                }
            }
        }
        override suspend fun close() { channel.close() }
    }

    private fun matches(f: org.json.JSONObject, e: org.json.JSONObject): Boolean {
        fun list(k: String) = f.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.get(it).toString() } }
        list("kinds")?.let { if (e.get("kind").toString() !in it) return false }
        list("authors")?.let { if (e.getString("pubkey") !in it) return false }
        val tags = e.getJSONArray("tags").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { t -> (0 until t.length()).map(t::getString) } } }
        for (key in f.keys().asSequence().filter { it.startsWith("#") }) list(key)?.let { vals ->
            if (tags.none { it.size >= 2 && it[0] == key.drop(1) && it[1] in vals }) return false
        }
        return true
    }
}

/** Hopp installed anew, the Nostr key brought back: the library comes back too
 *  (ADR 0021 stage 5, package 6 of docs/tasks/nostr-sync.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncRestoreTest {
    @get:Rule val rule = createComposeRule()

    private val koin get() = org.koin.mp.KoinPlatform.getKoin()
    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String, substring: Boolean = false) =
        try { rule.waitUntil(15_000) { has(text, substring) } }
        catch (e: Throwable) { throw AssertionError("'$text' not on screen", e) }
    private fun tap(text: String) {
        val node = rule.onNode(hasText(text) and hasClickAction())
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    @Test
    fun aKeyBroughtBackOffersTheLibraryAndRestoresIt() {
        val relays = KeepingRelays()
        loadKoinModules(module { single { RelayPublisher(relays) } })
        // What the listener's phone left on the relays: one show, sealed with their key.
        val keys = KeyPair.generate()
        runBlocking {
            val show = SyncDocs.show("https://pod.test/nodesignal.xml", "Nodesignal", null, 1.25f, true)
            SyncRelays(RelayPublisher(relays)) { 1_790_000_000 }.publish(LocalSigner(keys), SyncDocs.SUBSCRIPTIONS,
                mapOf("url:https://pod.test/nodesignal.xml" to SyncEntry(1_790_000_000_000, d = show)), listOf("wss://relay.damus.io"))
        }

        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Nostr identity")
        tap("I already have a Nostr account")
        rule.onNode(hasText("nsec1… or ncryptsec1…") and hasSetTextAction()).performTextInput(Bech32.encode("nsec", keys.secretKey))
        tap("Use key")
        waitFor("Restore from your other devices")
        waitFor("1 shows, 0 episodes in progress")
        tap("Restore")
        rule.waitUntil(30_000) { runBlocking { koin.get<SettingsRepository>().settings.first().nostrSync } }
        // The show, with the speed it had there.
        val db = koin.get<space.sovereigncast.hopp.db.HoppDatabase>()
        rule.waitUntil(30_000) { db.podcastQueries.selectAll().executeAsList().singleOrNull()?.playback_speed == 1.25 }
        check(db.podcastQueries.selectAll().executeAsList().single().feed_url == "https://pod.test/nodesignal.xml")
        rule.waitUntil(30_000) { !has("Restore from your other devices") }
    }
}
