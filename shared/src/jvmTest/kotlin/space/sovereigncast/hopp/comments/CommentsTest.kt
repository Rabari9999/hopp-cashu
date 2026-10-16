package space.sovereigncast.hopp.comments

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.noteRef
import space.sovereigncast.hopp.nostr.npub
import space.sovereigncast.hopp.nostr.profileKey
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.sovereigncast.ListenerApi
import space.sovereigncast.hopp.sovereigncast.SovereigncastRepository
import space.sovereigncast.hopp.value.FakeRelay
import space.sovereigncast.hopp.value.LnAddressClient
import space.sovereigncast.hopp.value.ValueService
import space.sovereigncast.hopp.value.WalletRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Reading comments, ADR 0021 stage 2. */
class CommentsTest {
    private val guid = "e8df0b13-47de-544a-99b7-ec7cbd960a16"
    private val item = "podcast:item:guid:e297"
    private val show = KeyPair.generate()
    private val alice = KeyPair.generate()
    private val bob = KeyPair.generate()
    private val troll = KeyPair.generate()
    private val now = 1_790_000_000L
    private val relay = FakeRelay()

    /** The show's notes: one in the feed, one only the instance knows (a PodHome feed carries no socialInteract). */
    private val feedNote = Event.sign(show, 1, "E297 is out", listOf(listOf("i", item)), now - 1000)
    private val preNote = Event.sign(show, 1, "Boost E297 out", emptyList(), now - 5000)

    private fun nevent(id: String, relay: String, author: String): String {
        val r = relay.encodeToByteArray()
        val tlv = byteArrayOf(0, 32) + id.hexToBytes() + byteArrayOf(1, r.size.toByte()) + r + byteArrayOf(2, 32) + author.hexToBytes()
        return "nostr:" + Bech32.encode("nevent", tlv)
    }

    private fun feed() = """<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
        <title>Nodesignal</title><podcast:guid>$guid</podcast:guid>
        <podcast:txt purpose="sovereigncast">https://pm.test</podcast:txt>
        <item><title>E297</title><guid>e297</guid><enclosure url="https://cdn.test/e.mp3" type="audio/mpeg"/>
        <podcast:socialInteract uri="${nevent(feedNote.id, "wss://show.test", show.publicKeyHex)}" protocol="nostr" accountId="${npub(show.publicKeyHex)}" priority="1"/>
        </item></channel></rss>"""

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        when {
            req.url.host == "pod.test" -> respond(feed())
            path == "/api/listener/meta" -> respond("""{"api_versions":["v1"],"instance":{"id":"i","software":"sovereigncast","version":"dev"}}""")
            path == "/api/listener/v1/shows/by-guid/$guid" -> respond("""{"podcast_guid":"$guid","title":"Nodesignal","feed_url":"https://pod.test/feed.xml",
                "capabilities":{"episode_notes":true},"poll_after_seconds":3600}""")
            path == "/api/listener/v1/shows/$guid/episodes/e297/notes" -> respond("""{"notes":[
                {"uri":"nostr:${Bech32.encode("note", preNote.id.hexToBytes())}","author_npub":"${npub(show.publicKeyHex)}","purpose":"pre_release"}]}""")
            else -> respondError(HttpStatusCode.NotFound)
        }
    })
    private val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
    private val settings = SettingsRepository(db)
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { now })
    private val episodes = EpisodeRepository(db, http, { now })
    private val wallet = WalletRepository(MemorySecretStore(), http, { now }, relay, timeout = Duration.parse("5s"))
    private val value = ValueService(db, wallet, episodes, podcasts, settings, LnAddressClient(http), FeedFetcher(http), null, { now }, AppInfo("0.0.1", "JVM", "Test"))
    private val sc = SovereigncastRepository(db, ListenerApi(http), podcasts, settings, wallet, value, { now })
    private val publisher = RelayPublisher(relay)
    private val identities = IdentityRepository(MemorySecretStore(), publisher, { now })
    private val reader = CommentReader(publisher, { now }) { identities.identity.value?.pubkey }
    private val comments = CommentsRepository(reader, episodes, podcasts, sc)

    private fun test(block: suspend () -> Unit) { runBlocking { withTimeout(30_000) { block() } } }

    private suspend fun episode(): Long {
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        sc.verify(id)
        return episodes.byGuid(id, "e297")!!.id
    }

    private fun post(keys: KeyPair, kind: Int, content: String, tags: List<List<String>>, at: Long) = Event.sign(keys, kind, content, tags, at).also(relay::publish)

    @Test
    fun theAnchorCombinesFeedInstanceAndKeys() = test {
        val anchor = comments.anchor(episode())!!
        assertEquals("e297", anchor.episodeGuid)
        assertEquals(guid, anchor.podcastGuid)
        assertEquals(setOf(feedNote.id, preNote.id), anchor.notes.map { it.id }.toSet())
        assertEquals(listOf("wss://show.test"), anchor.notes.first { it.id == feedNote.id }.relays)
        assertEquals(setOf(show.publicKeyHex), anchor.showKeys)
    }

    @Test
    fun repliesToTheShowsNotesAndNip73CommentsMakeOneThread() = test {
        relay.publish(feedNote); relay.publish(preNote)
        // A Hopp-style reply to the show's note (NIP-10), one to the instance-only note.
        val a = post(alice, 1, "Great episode, at 12:34 especially", listOf(listOf("e", feedNote.id, "", "root"), listOf("p", show.publicKeyHex)), now - 900)
        val b = post(bob, 1, "Boosted!", listOf(listOf("e", preNote.id, "", "root")), now - 800)
        // Fountain: a kind-1 note naming the episode, and an answer that names only that note.
        val f = post(bob, 1, "Listening in Fountain", listOf(listOf("i", item), listOf("k", "podcast:item:guid")), now - 700)
        val fReply = post(alice, 1, "Me too", listOf(listOf("e", f.id, "", "root")), now - 600)
        // NIP-22: a top-level comment, and a reply to it.
        val c = post(alice, 1111, "A kind-1111 comment", listOf(listOf("I", item), listOf("K", "podcast:item:guid"), listOf("i", item), listOf("k", "podcast:item:guid")), now - 500)
        val cReply = post(bob, 1111, "Answer", listOf(listOf("I", item), listOf("K", "podcast:item:guid"), listOf("e", c.id, "", alice.publicKeyHex), listOf("k", "1111")), now - 400)
        // The podcaster answers Alice, Bob answers the podcaster: under Alice, naming whom.
        val podcaster = post(show, 1, "Thanks!", listOf(listOf("e", feedNote.id, "", "root"), listOf("e", a.id, "", "reply")), now - 300)
        val deep = post(bob, 1, "Agreed", listOf(listOf("e", feedNote.id, "", "root"), listOf("e", podcaster.id, "", "reply")), now - 200)
        // Not about this episode.
        post(alice, 1, "Unrelated", listOf(listOf("i", "podcast:item:guid:other")), now - 100)

        val thread = reader.load(comments.anchor(episode())!!)
        assertEquals(listOf(c.id, f.id, b.id, a.id), thread.comments.map { it.id }, "newest thread first; the show's notes are no comments")
        val underA = thread.comments.first { it.id == a.id }.replies
        assertEquals(listOf(podcaster.id, deep.id), underA.map { it.id })
        assertNull(underA[0].replyTo, "answers the top comment")
        assertEquals(show.publicKeyHex, underA[1].replyTo)
        assertEquals(listOf(fReply.id), thread.comments.first { it.id == f.id }.replies.map { it.id })
        assertEquals(listOf(cReply.id), thread.comments.first { it.id == c.id }.replies.map { it.id })
        assertEquals(8, thread.count)
        assertTrue("wss://show.test" in thread.relays && thread.relays.size <= CommentReader.MAX_RELAYS)
    }

    @Test
    fun theShowsMuteListHidesAPersonAndTheAnswersBelow() = test {
        relay.publish(feedNote)
        val t = post(troll, 1, "spam", listOf(listOf("e", feedNote.id, "", "root")), now - 500)
        post(alice, 1, "don't feed", listOf(listOf("e", feedNote.id, "", "root"), listOf("e", t.id, "", "reply")), now - 400)
        val ok = post(alice, 1, "Fine comment", listOf(listOf("i", item)), now - 300)
        post(show, 10000, "", listOf(listOf("p", troll.publicKeyHex)), now - 200)
        val thread = reader.load(comments.anchor(episode())!!)
        assertEquals(listOf(ok.id), thread.comments.map { it.id })
    }

    @Test
    fun namesAndMentionsAreLookedUp() = test {
        relay.publish(feedNote)
        post(alice, 0, """{"name":"alice","display_name":"Alice","picture":"https://img.test/a.png"}""", emptyList(), now - 100)
        post(bob, 0, """{"name":"bob","picture":"http://img.test/b.png"}""", emptyList(), now - 100)
        post(alice, 1, "Hi nostr:${npub(bob.publicKeyHex)}", listOf(listOf("i", item)), now - 50)
        val thread = reader.load(comments.anchor(episode())!!)
        assertEquals(setOf(alice.publicKeyHex, bob.publicKeyHex), thread.people)
        val names = reader.authors(thread.people, thread.relays)
        assertEquals(CommentAuthor("Alice", "https://img.test/a.png"), names[alice.publicKeyHex])
        assertEquals(CommentAuthor("bob", null), names[bob.publicKeyHex], "http pictures are not loaded")
    }

    /** Device feedback 2026-10-16: 30–40 s until comments and names were in — a relay
     *  that never answers cost the full timeout at every step. */
    @Test
    fun aHangingRelayDoesNotHoldTheCommentsUp() = test {
        relay.publish(feedNote)
        val c = post(alice, 1, "Quick", listOf(listOf("i", item)), now - 10)
        post(alice, 0, """{"name":"Alice"}""", emptyList(), now - 10)
        val hanging = space.sovereigncast.hopp.nostr.RelayConnector { url ->
            if (url == "wss://relay.damus.io") kotlinx.coroutines.awaitCancellation() else relay.connect(url)
        }
        val slow = CommentReader(RelayPublisher(hanging), { now })
        val started = System.nanoTime()
        val thread = slow.load(comments.anchor(episode())!!)
        val names = slow.authors(thread.people, thread.relays)
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(listOf(c.id), thread.comments.map { it.id })
        assertEquals("Alice", names[alice.publicKeyHex]?.name)
        assertTrue(seconds < 8, "took $seconds s; the relay timeout is 10 s per step")
    }

    /** Fountain posts a boost as a comment embedding its zap receipt (device feedback 2026-10-16). */
    @Test
    fun aFountainBoostShowsItsSatsOnlyWhenTheReceiptHoldsUp() = test {
        relay.publish(feedNote)
        val zapper = KeyPair.generate()
        fun receipt(sender: KeyPair, msat: Long, invoiceMsat: Long = msat): Event {
            val request = Event.sign(sender, 9734, "", listOf(listOf("amount", "$msat"), listOf("i", item)), now - 100)
            val description = kotlinx.serialization.json.Json.encodeToString(Event.serializer(), request)
            val bolt11 = space.sovereigncast.hopp.value.TestInvoices.make(invoiceMsat, space.sovereigncast.hopp.crypto.Sha256.digest(description.encodeToByteArray()))
            return post(zapper, 9735, "", listOf(listOf("bolt11", bolt11), listOf("description", description), listOf("P", sender.publicKeyHex)), now - 99)
        }
        fun embed(e: Event) = "nostr:" + Bech32.encode("nevent", byteArrayOf(0, 32) + e.id.hexToBytes())
        val tail = "\n\nhttps://fountain.fm/episode/yUUIctmvqiIO2fiWKLKI\n\n"
        val bare = post(bob, 1, tail.trim() + "\n\n" + embed(receipt(bob, 600_000)), listOf(listOf("i", item)), now - 50)
        val worded = post(bob, 1, "Glückwunsch!$tail" + embed(receipt(bob, 210_000)), listOf(listOf("i", item)), now - 40)
        // Someone else's receipt, and one whose invoice says another amount: no sats.
        val borrowed = post(alice, 1, "Me!$tail" + embed(receipt(bob, 21_000)), listOf(listOf("i", item)), now - 30)
        val lying = post(alice, 1, "Big!$tail" + embed(receipt(alice, 1_000_000, invoiceMsat = 1_000)), listOf(listOf("i", item)), now - 20)
        val empty = post(alice, 1, tail.trim(), listOf(listOf("i", item)), now - 10)

        val thread = reader.load(comments.anchor(episode())!!)
        val boosts = reader.boosts(thread)
        assertEquals(mapOf(bare.id to 600L, worded.id to 210L), boosts)
        assertEquals("", commentBody(bare.content), "a boost without a message: only the sats to show")
        assertEquals("Glückwunsch!", commentBody(worded.content))
        assertEquals("", commentBody(empty.content), "nothing to show — the list leaves it out")
        assertTrue(borrowed.id !in boosts && lying.id !in boosts)
    }

    /** Device feedback 2026-10-16: loaded once, the comments are kept, and only what is new is asked for. */
    @Test
    fun aSecondLoadAsksOnlyForWhatIsNew() = test {
        relay.publish(feedNote)
        val old = post(alice, 1, "First", listOf(listOf("i", item)), now - 5000)
        val sent = mutableListOf<String>()
        val recording = space.sovereigncast.hopp.nostr.RelayConnector { url ->
            val session = relay.connect(url)
            object : space.sovereigncast.hopp.nostr.RelaySession by session {
                override suspend fun send(message: String) { sent += message; session.send(message) }
            }
        }
        var clock = now
        val r = CommentReader(RelayPublisher(recording), { clock })
        val anchor = comments.anchor(episode())!!
        assertNull(r.cached(anchor))
        assertEquals(listOf(old.id), r.load(anchor).comments.map { it.id })
        assertTrue(sent.none { "\"since\"" in it })

        clock = now + 3600
        val answer = post(bob, 1, "Reply to an old one", listOf(listOf("e", old.id, "", "root")), now + 3000)
        val fresh = post(bob, 1, "Second", listOf(listOf("i", item)), now + 3100)
        sent.clear()
        assertEquals(listOf(old.id), r.cached(anchor)?.comments?.map { it.id }, "shown at once")
        val thread = r.load(anchor)
        assertEquals(listOf(fresh.id, old.id), thread.comments.map { it.id }, "the old one from before, the new one asked for")
        assertEquals(listOf(answer.id), thread.comments.first { it.id == old.id }.replies.map { it.id })
        assertTrue(sent.filter { it.startsWith("[\"REQ\"") && "\"#" in it }.all { "\"since\":${now - 600}" in it }, "only since the last load (less the overlap)")
    }

    @Test
    fun nip19ReferencesDecode() {
        // NIP-19's own examples.
        assertEquals("7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e", profileKey("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"))
        assertEquals("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d",
            profileKey("nostr:nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p"))
        val ref = noteRef(nevent(feedNote.id, "wss://show.test/", show.publicKeyHex))!!
        assertEquals(feedNote.id, ref.id)
        assertEquals(listOf("wss://show.test"), ref.relays)
        assertEquals(show.publicKeyHex, ref.author)
        assertEquals(feedNote.id, noteRef(Bech32.encode("note", feedNote.id.hexToBytes()))?.id)
        assertNull(noteRef("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"))
        assertNull(noteRef("nostr:nevent1garbage"))
    }
}
