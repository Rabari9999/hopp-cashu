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
    /** The announcement after the release — where new comments go, not under the Release Boosting note. */
    private val postNote = Event.sign(show, 1, "E297 is out, listen", emptyList(), now - 900)

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
                {"uri":"nostr:${Bech32.encode("note", preNote.id.hexToBytes())}","author_npub":"${npub(show.publicKeyHex)}","purpose":"pre_release"},
                {"uri":"${nevent(postNote.id, "wss://post.test", show.publicKeyHex)}","author_npub":"${npub(show.publicKeyHex)}","purpose":"post_release"}]}""")
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
    private val reader = CommentReader(publisher, { now }, listener = { identities.identity.value?.pubkey })
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
        assertEquals(setOf(feedNote.id, preNote.id, postNote.id), anchor.notes.map { it.id }.toSet())
        // Device feedback 2026-10-16: a boost landed under the Release Boosting note.
        assertEquals(postNote.id, anchor.answerTo?.id, "new comments answer the announcement after the release")
        assertEquals(show.publicKeyHex, anchor.notes.first { it.id == preNote.id }.author, "a bare note1 gets its author from the instance")
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
        assertTrue("wss://show.test" in thread.relays && thread.relays.size <= CommentReader.MAX_RELAYS + 1 && "wss://relay.fountain.fm" in thread.relays)
    }

    /** The podcaster's own comment from Hopp quotes their note: a comment, not one of the show's notes. */
    @Test
    fun aQuotingNoteByThePodcasterIsAComment() = test {
        relay.publish(feedNote)
        val own = post(show, 1, "Thanks for listening", listOf(listOf("i", item), listOf("k", "podcast:item:guid"), listOf("q", feedNote.id, "")), now - 100)
        assertEquals(listOf(own.id), reader.load(comments.anchor(episode())!!).comments.map { it.id })
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

    /**
     * Device feedback 2026-10-17 (Einundzwanzig): no note names the episode and the feed no key —
     * the website's NIP-05 key, its note giving the title is the announcement; answers to it and
     * zaps on it with a message are the comments, the show's other notes are not.
     */
    @Test
    fun theWebsitesKeyFindsTheAnnouncementItsAnswersAndZaps() = test {
        val e21 = KeyPair.generate()
        val released = now - 20_000
        val announcement = post(e21, 1, "News Episode 336 - Et is kalt, schmeiß die Miner an - von und mit nostr:npub1x", listOf(listOf("r", "https://fountain.fm/episode/x")), released + 3600)
        val other = post(e21, 1, "GM! Heute Meetup in Köln", emptyList(), released + 7200)
        val answer = post(alice, 1, "Starke Folge", listOf(listOf("e", announcement.id, "", "root"), listOf("p", e21.publicKeyHex)), released + 8000)
        val zapper = KeyPair.generate()
        fun zap(sender: KeyPair, text: String, msat: Long): Event {
            val request = Event.sign(sender, 9734, text, listOf(listOf("amount", "$msat"), listOf("e", announcement.id), listOf("p", e21.publicKeyHex)), released + 9000)
            val description = kotlinx.serialization.json.Json.encodeToString(Event.serializer(), request)
            val bolt11 = space.sovereigncast.hopp.value.TestInvoices.make(msat, space.sovereigncast.hopp.crypto.Sha256.digest(description.encodeToByteArray()))
            return post(zapper, 9735, "", listOf(listOf("e", announcement.id), listOf("p", e21.publicKeyHex), listOf("bolt11", bolt11), listOf("description", description)), released + 9001)
        }
        val worded = zap(bob, "Sehr gute Episode!", 500_000)
        zap(alice, "", 21_000) // no message: no comment
        val asked = mutableListOf<String>()
        val r = CommentReader(publisher, { now }, store, siteKey = { host -> asked += host; e21.publicKeyHex.takeIf { host == "einundzwanzig.test" } })
        val anchor = CommentAnchor("e336", null, emptyList(), emptySet(), null,
            title = "#336 - Et is kalt, schmeiß die Miner an", publishedAt = released, site = "einundzwanzig.test")
        val thread = r.load(anchor)
        assertEquals(listOf("einundzwanzig.test"), asked)
        assertEquals(listOf(worded.id, answer.id), thread.comments.map { it.id }, "the zap with words and the answer; not the announcement, not the other note")
        val z = thread.comments.first()
        assertEquals(bob.publicKeyHex to "Sehr gute Episode!", z.pubkey to z.content)
        assertEquals(500L, r.boosts(thread)[worded.id])
        assertTrue(other.id !in thread.comments.map { it.id })
        // Next time at once, from what was kept.
        assertEquals(thread.comments.map { it.id }, CommentReader(publisher, { now }, store).cached(anchor)?.comments?.map { it.id })
    }

    @Test
    fun anAnnouncementGivesTheTitleOrThePage() {
        val k = KeyPair.generate()
        val a = CommentAnchor("x", null, emptyList(), emptySet(), title = "E297 - Bitcoin und Bier", links = setOf("https://show.test/e297"))
        assertTrue(announces(Event.sign(k, 1, "Neue Folge: E297 – Bitcoin und Bier!", emptyList(), now), a))
        assertTrue(announces(Event.sign(k, 1, "Hört rein", listOf(listOf("r", "https://show.test/e297")), now), a))
        assertTrue(!announces(Event.sign(k, 1, "Bitcoin am Abend", emptyList(), now), a))
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

    /** Device feedback 2026-10-16: loaded once, the comments are kept and show at once.
     *  2026-10-17: and asked for in full again — one a relay held back is found next time. */
    @Test
    fun aSecondLoadShowsTheKeptOnesAndFindsWhatWasMissed() = test {
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
        // Older than the last load, missing then (a slow relay): still found.
        val late = post(KeyPair.generate(), 1, "Written before, arrived late", listOf(listOf("i", item)), now - 4000)
        sent.clear()
        assertEquals(listOf(old.id), r.cached(anchor)?.comments?.map { it.id }, "shown at once")
        val thread = r.load(anchor)
        assertEquals(listOf(fresh.id, late.id, old.id), thread.comments.map { it.id }, "the old one, the new one and the late one")
        assertEquals(listOf(answer.id), thread.comments.first { it.id == old.id }.replies.map { it.id })
        assertTrue(sent.none { "\"since\"" in it }, "asked in full")
    }

    /** Device feedback 2026-10-16: kept in the database, the comments outlive a restart,
     *  and go with the show's subscription. */
    @Test
    fun theDatabaseKeepsCommentsNamesAndSatsUntilTheShowGoes() = test {
        relay.publish(feedNote)
        val c = post(alice, 1, "Kept", listOf(listOf("i", item)), now - 100)
        post(alice, 0, """{"name":"Alice"}""", emptyList(), now - 100)
        val id = episode()
        val anchor = comments.anchor(id)!!
        val first = CommentReader(publisher, { now }, DbCommentStore(db))
        val loaded = first.load(anchor)
        first.authors(loaded.people, loaded.relays)
        first.boosts(loaded)

        // A new run: nobody asked, everything there.
        val silent = space.sovereigncast.hopp.nostr.RelayConnector { error("asked a relay") }
        val again = CommentReader(RelayPublisher(silent), { now + 60 }, DbCommentStore(db))
        val kept = again.cached(anchor)!!
        assertEquals(listOf(c.id), kept.comments.map { it.id })
        assertEquals("Alice", again.authors(kept.people, kept.relays)[alice.publicKeyHex]?.name)

        podcasts.unsubscribe(episodes.item(id)!!.podcastId)
        assertNull(again.cached(anchor), "gone with the show")
    }

    // ── Stage 3: writing ─────────────────────────────────────────────────

    private val store = MemoryCommentStore()
    private val writer = CommentWriter(publisher, identities, store, { now })
    private val writeReader = CommentReader(publisher, { now }, store)

    @Test
    fun aCommentStandsOnItsOwnQuotesTheShowsNoteAndIsKeptAtOnce() = test {
        relay.publish(feedNote)
        identities.create("Me")
        val anchor = comments.anchor(episode())!!
        val posted = writer.comment(anchor, "  Great show!  ", listOf("wss://relay.damus.io"))
        assertEquals(1, posted.kind)
        assertEquals("Great show!", commentBody(posted.content))
        val mention = noteRef(posted.content.substringAfterLast("\n\n"))!!
        assertEquals(postNote.id, mention.id, "the announcement mentioned in the text, for other clients")
        assertEquals(listOf("wss://post.test"), mention.relays)
        // Device feedback 2026-10-17: as Fountain's own — no reply, so Fountain shows it.
        assertTrue(posted.tags.none { it[0] == "e" }, "not a reply")
        assertEquals(listOf("q", postNote.id, "wss://post.test"), posted.tags.single { it[0] == "q" }.take(3), "the announcement quoted, with its relay")
        assertTrue(listOf("p", show.publicKeyHex) in posted.tags, "the podcaster told")
        assertTrue(listOf("i", item) in posted.tags && listOf("i", "podcast:guid:$guid") in posted.tags, "NIP-73: episode and show")
        assertEquals(listOf(posted.id), writeReader.cached(anchor)?.comments?.map { it.id }, "shown without asking the relays again")
        assertEquals(listOf(posted.id), writeReader.load(anchor).comments.map { it.id }, "and on the relays")
    }

    @Test
    fun withoutAShowNoteItNamesOnlyTheEpisode() = test {
        identities.create("Me")
        val anchor = comments.anchor(episode())!!.copy(notes = emptyList(), answerTo = null, showKeys = emptySet())
        val posted = writer.comment(anchor, "No note here", emptyList())
        assertEquals(1, posted.kind)
        assertEquals(listOf(listOf("i", item), listOf("k", "podcast:item:guid"), listOf("i", "podcast:guid:$guid"), listOf("k", "podcast:guid")), posted.tags)
        assertEquals(listOf(posted.id), writeReader.load(anchor).comments.map { it.id })
    }

    @Test
    fun anAnswerFollowsTheKindOfWhatItAnswers() = test {
        relay.publish(feedNote)
        identities.create("Me")
        val anchor = comments.anchor(episode())!!
        val fountain = post(bob, 1, "From Fountain", listOf(listOf("i", item)), now - 100)
        val nip22 = post(alice, 1111, "A 1111", listOf(listOf("I", item), listOf("K", "podcast:item:guid"), listOf("i", item), listOf("k", "podcast:item:guid")), now - 90)
        val thread = writeReader.load(anchor)
        val toFountain = writer.comment(anchor, "Agreed", thread.relays, thread.comments.first { it.id == fountain.id })
        assertEquals(1, toFountain.kind)
        assertTrue(listOf("e", fountain.id, thread.relays.first(), "root") in toFountain.tags && listOf("e", fountain.id, thread.relays.first(), "reply") in toFountain.tags)
        val toNip22 = writer.comment(anchor, "Me too", thread.relays, thread.comments.first { it.id == nip22.id })
        assertEquals(1111, toNip22.kind)
        assertTrue(listOf("e", nip22.id, thread.relays.first(), alice.publicKeyHex) in toNip22.tags && listOf("k", "1111") in toNip22.tags)
        // An answer to the answer keeps the thread's root (NIP-10).
        val again = writeReader.load(anchor)
        val mine = again.comments.first { it.id == fountain.id }.replies.single()
        val deeper = writer.comment(anchor, "Deeper", again.relays, mine)
        assertTrue(listOf("e", fountain.id, again.relays.first(), "root") in deeper.tags && listOf("e", mine.id, again.relays.first(), "reply") in deeper.tags)
        val final = writeReader.load(anchor)
        assertEquals(setOf(toFountain.id, deeper.id), final.comments.first { it.id == fountain.id }.replies.map { it.id }.toSet())
        assertEquals(listOf(toNip22.id), final.comments.first { it.id == nip22.id }.replies.map { it.id })
    }

    @Test
    fun likesAreCountedAndOwnOnesKnown() = test {
        relay.publish(feedNote)
        identities.create("Me")
        val anchor = comments.anchor(episode())!!
        val c = post(alice, 1, "Like me", listOf(listOf("i", item)), now - 100)
        post(bob, 7, "🔥", listOf(listOf("e", c.id), listOf("p", alice.publicKeyHex)), now - 50)
        post(troll, 7, "-", listOf(listOf("e", c.id)), now - 40)
        val thread = writeReader.load(anchor)
        assertEquals(setOf(bob.publicKeyHex), thread.comments.single().likes, "a dislike is no like")
        val like = writer.like(anchor, thread.comments.single(), thread.relays)
        assertEquals(listOf(listOf("e", c.id, thread.relays.first(), alice.publicKeyHex), listOf("p", alice.publicKeyHex), listOf("k", "1")), like.tags)
        val me = identities.identity.value!!.pubkey
        assertEquals(setOf(bob.publicKeyHex, me), writeReader.cached(anchor)!!.comments.single().likes)
    }

    @Test
    fun mutingKeepsWhatTheListAlreadyHeld() = test {
        identities.create("Me")
        val me = identities.signer()!!
        // A list from another app: a public entry and an encrypted private part Hopp cannot read.
        relay.publish(me.sign(space.sovereigncast.hopp.nostr.UnsignedEvent(10000, "encrypted-private-part", listOf(listOf("p", alice.publicKeyHex), listOf("t", "nsfw")), now - 100)))
        writer.mute(troll.publicKeyHex)
        val list = publisher.latest(10000, me.pubkey, listOf("wss://relay.damus.io"))!!
        assertEquals("encrypted-private-part", list.content)
        assertEquals(listOf(listOf("p", alice.publicKeyHex), listOf("t", "nsfw"), listOf("p", troll.publicKeyHex)), list.tags)
        // And the next load hides the muted person.
        relay.publish(feedNote)
        post(troll, 1, "spam", listOf(listOf("i", item)), now - 10)
        val listening = CommentReader(publisher, { now }, listener = { me.pubkey })
        assertEquals(emptyList(), listening.load(comments.anchor(episode())!!).comments)
    }

    /** Device feedback 2026-10-16: boosts as comments, as Fountain does. */
    @Test
    fun aPublicBoostIsACommentWithItsAmount() = test {
        relay.publish(feedNote)
        identities.create("Me")
        val anchor = comments.anchor(episode())!!
        val posted = writer.boost(anchor, 2100, "  Danke! ", emptyList())
        assertTrue(posted.content.startsWith("Danke!\n\n⚡ 2100 sats\n\nnostr:nevent1"))
        assertTrue(posted.tags.any { it[0] == "q" && it[1] == postNote.id } && listOf("i", item) in posted.tags)
        assertEquals("⚡ 21 sats", commentBody(writer.boost(anchor, 21, "", emptyList()).content))
        assertEquals(2, writeReader.cached(anchor)!!.comments.size)
    }

    @Test
    fun aReportNamesTheCommentAndTheReason() = test {
        identities.create("Me")
        val published = mutableListOf<Event>()
        relay.listeners += { published += it }
        writer.report(Comment("ab".repeat(32), troll.publicKeyHex, now, "spam"), ReportType.Spam, listOf("wss://relay.damus.io"))
        val report = published.first { it.kind == 1984 }
        assertEquals(listOf(listOf("e", "ab".repeat(32), "spam"), listOf("p", troll.publicKeyHex, "spam")), report.tags)
    }

    @Test
    fun withoutAnIdentityNothingIsPublished() = test {
        val anchor = comments.anchor(episode())!!
        kotlin.test.assertFailsWith<NoIdentity> { writer.comment(anchor, "Hi", emptyList()) }
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
