package space.sovereigncast.hopp.contacts

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.Nip44
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0024: the contacts note and the listener's Nostr contacts, against one in-memory relay. */
class ContactsTest {
    private val relay = FakeRelay().apply { replaces = true }
    private val listener = KeyPair.generate()
    private var now = 1_790_000_000L

    /** One of the listener's devices. */
    private inner class Device(owner: KeyPair = listener) {
        val secrets = MemorySecretStore()
        val identity = IdentityRepository(secrets, RelayPublisher(relay), { now }).also {
            runBlocking { it.importKey(Bech32.encode("nsec", owner.secretKey)) }
        }
        val keys = ContactKeys(identity, RelayPublisher(relay), secrets) { now }
        val graph = ContactGraph(identity, RelayPublisher(relay)) { now }
        val contacts = ContactsRepository(identity, keys, graph, { now })
    }

    private fun follows(who: KeyPair, vararg whom: String) =
        relay.publish(Event.sign(who, 3, "", whom.map { listOf("p", it) }, now - 100))

    private fun notes(): List<Event> = relay.stored().filter { it.kind == Nip78.KIND && it.pubkey == listener.publicKeyHex }

    @Test
    fun theNoteNamesThePublicKeysAndSealsThePrivateOnes(): Unit = runBlocking {
        val phone = Device()
        val note = assertNotNull(phone.keys.set(share = true, see = false))
        assertTrue(note.sharing && !note.seeing)
        val published = notes().maxBy { it.created_at }
        assertEquals(listOf(note.share), published.tags.filter { it[0] == "share" }.map { it[1] })
        assertTrue(published.tags.none { it[0] == "inbox" })
        // The private half is in no clear text of the note.
        assertTrue(phone.keys.shareKey()!!.secretKey.toHex() !in published.content)
    }

    /** Every device shows the same switches; the keys come over with one question to the signer. */
    @Test
    fun anotherDeviceSeesTheSwitchesAndTakesTheKeysOver(): Unit = runBlocking {
        val phone = Device()
        phone.keys.set(share = true, see = true)
        val laptop = Device()
        val seen = assertNotNull(laptop.keys.note(interactive = false))
        assertTrue(seen.sharing && seen.seeing && !seen.opened)
        assertNull(laptop.keys.shareKey())
        assertTrue(assertNotNull(laptop.keys.note(interactive = true)).opened)
        assertEquals(phone.keys.shareKey()!!.publicKeyHex, laptop.keys.shareKey()!!.publicKeyHex)
        assertEquals(phone.keys.inboxKey()!!.publicKeyHex, laptop.keys.inboxKey()!!.publicKeyHex)
    }

    /** A device left behind that flips a switch keeps the keys the note on the relays names — none of its own over them. */
    @Test
    fun aDeviceLeftBehindKeepsTheNewestKeys(): Unit = runBlocking {
        val phone = Device()
        phone.keys.set(share = false, see = true)
        val laptop = Device()
        laptop.keys.note(interactive = true)
        now += 10
        phone.keys.set(share = true, see = true)
        val share = phone.keys.shareKey()!!.publicKeyHex
        val inbox = phone.keys.inboxKey()!!.publicKeyHex
        now += 10
        // The laptop never heard of the share key; it is the note that says so.
        assertEquals(share, laptop.keys.set(share = true, see = true)!!.share)
        assertEquals(share, laptop.keys.shareKey()!!.publicKeyHex)
        assertEquals(inbox, laptop.keys.inboxKey()!!.publicKeyHex)
    }

    /** Both turned off elsewhere since: what this device still held is not taken up again. */
    @Test
    fun aDeviceLeftBehindDoesNotBringOldKeysBack(): Unit = runBlocking {
        val phone = Device()
        phone.keys.set(share = false, see = true)
        val inbox = phone.keys.inboxKey()!!.publicKeyHex
        val laptop = Device()
        laptop.keys.note(interactive = true)
        now += 10
        phone.keys.set(share = false, see = false)
        now += 10
        laptop.keys.set(share = false, see = true)
        assertNotEquals(inbox, laptop.keys.inboxKey()!!.publicKeyHex)
    }

    /** A part turned off loses its key; turned on again it gets a new one. The other part keeps its own. */
    @Test
    fun sharingOffAndOnMakesANewShareKey(): Unit = runBlocking {
        val phone = Device()
        phone.keys.set(share = true, see = true)
        val first = phone.keys.shareKey()!!.publicKeyHex
        val inbox = phone.keys.inboxKey()!!.publicKeyHex
        now += 10
        assertTrue(!phone.keys.set(share = false, see = true)!!.sharing)
        assertNull(phone.keys.shareKey())
        now += 10
        phone.keys.set(share = true, see = true)
        assertNotEquals(first, phone.keys.shareKey()!!.publicKeyHex)
        assertEquals(inbox, phone.keys.inboxKey()!!.publicKeyHex)
    }

    /** Both off: the note emptied and deleted; another device then holds nothing either. */
    @Test
    fun bothOffEmptiesTheNote(): Unit = runBlocking {
        val phone = Device()
        phone.keys.set(share = true, see = false)
        val laptop = Device()
        laptop.keys.note(interactive = true)
        now += 10
        assertNull(phone.keys.set(share = false, see = false))
        assertTrue(relay.stored().any { it.kind == 5 && it.pubkey == listener.publicKeyHex })
        assertNull(laptop.keys.note())
        assertNull(laptop.keys.shareKey())
    }

    /** Mutual: their newest contact list names the listener. With Hopp: their contacts note names a key. */
    @Test
    fun mutualContactsAndThoseWithHoppAreToldApart(): Unit = runBlocking {
        val anna = KeyPair.generate(); val ben = KeyPair.generate(); val cara = KeyPair.generate()
        follows(listener, anna.publicKeyHex, ben.publicKeyHex, cara.publicKeyHex)
        follows(anna, listener.publicKeyHex)
        follows(ben, KeyPair.generate().publicKeyHex)
        follows(cara, listener.publicKeyHex, anna.publicKeyHex)
        relay.publish(Event.sign(cara, Nip78.KIND, "sealed", listOf(listOf("d", ContactKeys.DTAG), listOf("inbox", KeyPair.generate().publicKeyHex)), now - 50))
        val phone = Device()
        phone.keys.set(share = true, see = false)
        phone.contacts.refresh(force = true)
        val read = assertNotNull(phone.contacts.state.value.read)
        assertEquals(setOf(anna.publicKeyHex, ben.publicKeyHex, cara.publicKeyHex), read.following)
        assertEquals(setOf(anna.publicKeyHex, cara.publicKeyHex), read.mutual.map { it.pubkey }.toSet())
        assertEquals(listOf(cara.publicKeyHex), read.withHopp.map { it.pubkey })
    }

    /** Ben followed the listener once, then no more: relays keep only his newest list. */
    @Test
    fun aContactWhoUnfollowedIsNoLongerMutual(): Unit = runBlocking {
        val ben = KeyPair.generate()
        follows(listener, ben.publicKeyHex)
        relay.publish(Event.sign(ben, 3, "", listOf(listOf("p", listener.publicKeyHex)), now - 500))
        relay.publish(Event.sign(ben, 3, "", emptyList(), now - 100))
        val phone = Device()
        phone.keys.set(share = true, see = false)
        phone.contacts.refresh(force = true)
        assertTrue(phone.contacts.state.value.read!!.mutual.isEmpty())
    }

    // Stage 2: sharing (ADR 0024, 4).

    private var shows = listOf(SharedShow("guid-a", "https://a.test/feed", "Show A", 100), SharedShow(null, "https://b.test/feed", "Show B", 200))

    private fun share(device: Device) = ContactShare(device.identity, RelayPublisher(relay), device.keys, device.secrets, { shows }) { now }

    private fun byShareKey(share: KeyPair) = relay.stored().filter { it.pubkey == share.publicKeyHex && it.kind == Nip78.KIND }.distinctBy { it.id }

    /** ADR 0024, 4a: a device not known to be up to date sends no list — and one up to date sends what it knows then. */
    @Test
    fun aListGoesOutOnlyFromADeviceUpToDate(): Unit = runBlocking {
        val phone = Device()
        phone.keys.set(share = true, see = false)
        val leftOutElsewhere = shows.first()
        var list = shows
        var upToDate = false
        var asked = 0
        val sharer = ContactShare(phone.identity, RelayPublisher(relay), phone.keys, phone.secrets, { list }, { asked++; upToDate.also { if (it) list = shows - leftOutElsewhere } }) { now }
        val inbox = KeyPair.generate()
        sharer.publish(setOf(inbox.publicKeyHex))
        val key = phone.keys.shareKey()!!
        assertEquals(1, asked)
        assertTrue(byShareKey(key).none { it.tag("d") == ContactShare.SHELF }, "no list yet")
        assertTrue(byShareKey(key).any { it.tag("d") == ContactShare.keyTag(inbox.publicKeyHex) }, "the key says nothing of the shows")
        upToDate = true
        sharer.publish(setOf(inbox.publicKeyHex))
        val shelf = byShareKey(key).single { it.tag("d") == ContactShare.SHELF }
        val opened = Nip44.decrypt(shelf.content, Nip44.conversationKey(key.secretKey, key.publicKeyHex.hexToBytes()))
        assertFalse(leftOutElsewhere.url in opened, "what the round brought counts")
        // Nothing changed: no round asked for it.
        asked = 0
        sharer.publish(setOf(inbox.publicKeyHex))
        assertEquals(0, asked)
    }

    /** Device feedback 2026-11-05: a read takes a minute, and leaving the page threw away what it had found. */
    @Test
    fun aReadGoesOnWhenThePageCloses(): Unit = runBlocking {
        val anna = KeyPair.generate()
        follows(listener, anna.publicKeyHex)
        val slow = object : space.sovereigncast.hopp.nostr.RelayConnector {
            override suspend fun connect(url: String): space.sovereigncast.hopp.nostr.RelaySession {
                val inner = relay.connect(url)
                return object : space.sovereigncast.hopp.nostr.RelaySession by inner {
                    override suspend fun send(message: String) { kotlinx.coroutines.delay(20); inner.send(message) }
                }
            }
        }
        val me = Device()
        me.keys.set(share = false, see = true)
        val contacts = ContactsRepository(me.identity, me.keys, ContactGraph(me.identity, RelayPublisher(slow)) { now }, { now })
        val page = launch(kotlinx.coroutines.Dispatchers.Default) { contacts.readToTheEnd(maxAge = 300) }
        kotlinx.coroutines.delay(5)
        page.cancel()
        page.join()
        assertEquals(setOf(anna.publicKeyHex), contacts.state.value.read?.following)
    }

    /** The list opens with the share key alone; a mutual contact's inbox opens the share key. */
    @Test
    fun theSharedListAndTheKeysForMutualContactsOpenAsTheyShould(): Unit = runBlocking {
        val phone = Device()
        phone.keys.set(share = true, see = false)
        val inbox = KeyPair.generate()
        share(phone).publish(setOf(inbox.publicKeyHex))
        val key = phone.keys.shareKey()!!
        val events = byShareKey(key)
        val shelf = events.single { it.tag("d") == ContactShare.SHELF }
        val opened = kotlinx.serialization.json.Json.decodeFromString(Shelf.serializer(),
            space.sovereigncast.hopp.nostr.Nip44.decrypt(shelf.content, space.sovereigncast.hopp.nostr.Nip44.conversationKey(key.secretKey, key.publicKeyHex.hexToBytes())))
        assertEquals(shows, opened.shows)
        val sealedKey = events.single { it.tag("d") == ContactShare.keyTag(inbox.publicKeyHex) }
        val secret = space.sovereigncast.hopp.nostr.Nip44.decrypt(sealedKey.content, space.sovereigncast.hopp.nostr.Nip44.conversationKey(inbox.secretKey, key.publicKeyHex.hexToBytes()))
        assertEquals(key.secretKey.toHex(), secret)
        assertTrue(events.none { it.content.contains("Show A") }, "nothing of the list in clear text")
    }

    /** Only what changed goes out again: the same list and the same inboxes send nothing. */
    @Test
    fun unchangedNothingIsSentAgain(): Unit = runBlocking {
        val phone = Device()
        phone.keys.set(share = true, see = false)
        val inbox = KeyPair.generate().publicKeyHex
        val sharing = share(phone)
        sharing.publish(setOf(inbox))
        val key = phone.keys.shareKey()!!
        val first = byShareKey(key).size
        sharing.publish(setOf(inbox))
        assertEquals(first, byShareKey(key).size)
        shows = shows.drop(1)
        now += 60
        sharing.publish(setOf(inbox))
        assertEquals(first + 1, byShareKey(key).size, "the changed list, and no key again")
    }

    /** Sharing off: what the share key signed is replaced by empty events and deleted. */
    @Test
    fun sharingOffTakesTheListBack(): Unit = runBlocking {
        val phone = Device()
        phone.keys.set(share = true, see = false)
        val sharing = share(phone)
        sharing.publish(setOf(KeyPair.generate().publicKeyHex))
        val key = phone.keys.shareKey()!!
        now += 60
        assertTrue(sharing.retire(key))
        val newest = byShareKey(key).groupBy { it.tag("d") }.mapValues { (_, e) -> e.maxBy { it.created_at } }
        assertTrue(newest.values.all { it.content.isEmpty() })
        assertTrue(relay.stored().any { it.kind == 5 && it.pubkey == key.publicKeyHex })
    }

    // Stage 3: seeing what mutual contacts share (ADR 0024, 5, 7).

    /** Anna shares with the listener; the listener, looking, sees her shows with her name — Ben, who
     *  shares too but does not follow the listener back, is not opened. */
    @Test
    fun whatAMutualContactSharesIsSeen(): Unit = runBlocking {
        val anna = KeyPair.generate(); val ben = KeyPair.generate()
        follows(listener, anna.publicKeyHex, ben.publicKeyHex)
        follows(anna, listener.publicKeyHex)
        follows(ben, KeyPair.generate().publicKeyHex)
        relay.publish(Event.sign(anna, 0, """{"name":"Anna","picture":"https://a.test/anna.png"}""", emptyList(), now - 100))
        val me = Device()
        me.keys.set(share = false, see = true)
        val myInbox = me.keys.inboxKey()!!.publicKeyHex
        for (who in listOf(anna, ben)) {
            val device = Device(who)
            device.keys.set(share = true, see = false)
            ContactShare(device.identity, RelayPublisher(relay), device.keys, device.secrets, { shows }) { now }.publish(setOf(myInbox))
        }
        me.contacts.refresh(force = true)
        val read = assertNotNull(me.contacts.state.value.read)
        assertEquals(shows.toSet(), read.contacts.getValue(anna.publicKeyHex).shows.toSet())
        assertTrue(read.contacts.getValue(ben.publicKeyHex).shows.isEmpty())
        assertEquals(listOf("Anna"), read.listeners("guid-a", "https://a.test/feed").map { it.name })
        assertEquals(listOf("https://b.test/feed", "https://a.test/feed"), read.listening().map { it.show.url })
        assertEquals("https://a.test/anna.png", read.contacts.getValue(anna.publicKeyHex).picture)
    }

    /** Without looking at it, nothing is opened, even where a contact shares. */
    @Test
    fun notLookingOpensNothing(): Unit = runBlocking {
        val anna = KeyPair.generate()
        follows(listener, anna.publicKeyHex)
        follows(anna, listener.publicKeyHex)
        val me = Device()
        me.keys.set(share = true, see = false)
        val annas = Device(anna)
        annas.keys.set(share = true, see = false)
        ContactShare(annas.identity, RelayPublisher(relay), annas.keys, annas.secrets, { shows }) { now }.publish(setOf(KeyPair.generate().publicKeyHex))
        me.contacts.refresh(force = true)
        assertTrue(me.contacts.state.value.read!!.listening().isEmpty())
    }

    // Stage 4: public signals (ADR 0024, 6).

    /** The people the listener follows — one way — count with what they did in public: a boost (a note
     *  ending "⚡ N sats") weighs more than a comment, and both more than a shared subscription. */
    @Test
    fun publicBoostsAndCommentsOfThePeopleFollowedCount(): Unit = runBlocking {
        val anna = KeyPair.generate(); val ben = KeyPair.generate()
        follows(listener, anna.publicKeyHex, ben.publicKeyHex)
        follows(anna, listener.publicKeyHex)
        // Ben does not follow back: his public acts count all the same.
        val tags = { guid: String -> listOf(listOf("i", "podcast:item:guid:ep-1"), listOf("k", "podcast:item:guid"), listOf("i", "podcast:guid:$guid"), listOf("k", "podcast:guid")) }
        relay.publish(Event.sign(ben, 1, "Great episode!\n\n⚡ 2100 sats", tags("Boosted-Show"), now - 1000))
        relay.publish(Event.sign(ben, 1, "Not about podcasts", emptyList(), now - 900))
        relay.publish(Event.sign(anna, 1111, "Nice", listOf(listOf("I", "podcast:guid:commented-show"), listOf("K", "podcast:guid")), now - 800))
        // Older than ninety days: no longer counted.
        relay.publish(Event.sign(anna, 1, "Old\n\n⚡ 10 sats", tags("old-show"), now - 100 * 86_400))
        val me = Device()
        me.keys.set(share = false, see = true)
        val annas = Device(anna)
        annas.keys.set(share = true, see = false)
        ContactShare(annas.identity, RelayPublisher(relay), annas.keys, annas.secrets, { shows }) { now }.publish(setOf(me.keys.inboxKey()!!.publicKeyHex))
        val looked = mapOf(
            "boosted-show" to SharedShow("boosted-show", "https://boosted.test/feed", "Boosted", 0),
            "commented-show" to SharedShow("commented-show", "https://commented.test/feed", "Commented", 0),
        )
        val contacts = ContactsRepository(me.identity, me.keys, me.graph, { now }, lookup = { looked[it] })
        contacts.refresh(force = true)
        val read = contacts.state.value.read!!
        assertEquals(listOf(PublicSignal("boosted-show", boost = true, at = now - 1000)), read.contacts.getValue(ben.publicKeyHex).signals)
        val picks = read.listening()
        assertEquals("https://boosted.test/feed", picks.first().show.url)
        assertEquals(listOf("https://boosted.test/feed", "https://commented.test/feed"), picks.take(2).map { it.show.url })
        assertTrue(picks.none { it.show.guid == "old-show" })
        assertEquals(listOf(ben.publicKeyHex), read.listeners("Boosted-Show", "https://boosted.test/feed").map { it.pubkey })
    }

    // Stage 5: a mutual contact lost (ADR 0024, 4).

    /** Anna had the key; she unfollows. Lost in two reads in a row, the share key is renewed — her inbox
     *  gets no key for the new one, and what the old one signed is emptied. */
    @Test
    fun aLostMutualContactGetsNothingThatComesAfter(): Unit = runBlocking {
        val anna = KeyPair.generate()
        follows(listener, anna.publicKeyHex)
        follows(anna, listener.publicKeyHex)
        val annas = Device(anna)
        annas.keys.set(share = false, see = true)
        val annaInbox = annas.keys.inboxKey()!!.publicKeyHex
        val me = Device()
        val sharing = ContactShare(me.identity, RelayPublisher(relay), me.keys, me.secrets, { shows }) { now }
        val contacts = ContactsRepository(me.identity, me.keys, me.graph, { now }, sharing, readEvery = 0)
        contacts.setSharing(true)
        contacts.refresh(force = true)
        val old = me.keys.shareKey()!!
        assertEquals(setOf(annaInbox), sharing.sentTo())
        // Anna unfollows.
        now += 60
        relay.publish(Event.sign(anna, 3, "", emptyList(), now))
        contacts.refresh(force = true)
        assertEquals(old.publicKeyHex, me.keys.shareKey()!!.publicKeyHex, "once is not enough: a relay may have been down")
        now += 60
        contacts.refresh(force = true)
        assertNotEquals(old.publicKeyHex, me.keys.shareKey()!!.publicKeyHex)
        assertTrue(sharing.sentTo().isEmpty())
        assertTrue(byShareKey(old).groupBy { it.tag("d") }.values.all { e -> e.maxBy { it.created_at }.content.isEmpty() })
        assertTrue(byShareKey(me.keys.shareKey()!!).any { it.tag("d") == ContactShare.SHELF })
    }

    /** Device feedback 2026-11-02: the listener turned looking on and saw nothing, as the contacts were read
     *  again only on the hour. Now: read at once on turning on; the sharer's device seals a key for the new
     *  inbox when its Nostr page opens; the listener's page or Discover then opens the list. */
    @Test
    fun turnedOnWhatIsSharedShowsWithoutWaitingForTheHour(): Unit = runBlocking {
        val tester = KeyPair.generate()
        follows(listener, tester.publicKeyHex)
        follows(tester, listener.publicKeyHex)
        val testers = Device(tester)
        val testerContacts = ContactsRepository(testers.identity, testers.keys, testers.graph, { now },
            ContactShare(testers.identity, RelayPublisher(relay), testers.keys, testers.secrets, { shows }) { now })
        testerContacts.setSharing(true)
        val me = Device()
        me.contacts.refresh()
        assertNull(me.contacts.state.value.read, "both switches off: nobody's lists are asked for")
        now += 30
        me.contacts.setSeeing(true)
        assertEquals(1, me.contacts.state.value.read!!.withHopp.size, "read again at once: the tester uses Hopp")
        // The tester's page opens five minutes later: a key for the new inbox.
        now += 301
        testerContacts.refresh(maxAge = 300)
        now += 301
        me.contacts.refresh(maxAge = 300)
        assertEquals(shows.toSet(), me.contacts.state.value.read!!.listening().map { it.show }.toSet())
    }

    /** Both switches off: the hourly refresh asks the relays for nobody's lists. */
    @Test
    fun withBothSwitchesOffNoListIsAskedFor(): Unit = runBlocking {
        follows(listener, KeyPair.generate().publicKeyHex)
        val asked = mutableListOf<String>()
        val watching = object : space.sovereigncast.hopp.nostr.RelayConnector {
            override suspend fun connect(url: String): space.sovereigncast.hopp.nostr.RelaySession {
                val inner = relay.connect(url)
                return object : space.sovereigncast.hopp.nostr.RelaySession by inner {
                    override suspend fun send(message: String) { asked += message; inner.send(message) }
                }
            }
        }
        val me = Device()
        val contacts = ContactsRepository(me.identity, me.keys, ContactGraph(me.identity, RelayPublisher(watching)) { now }, { now })
        contacts.refresh(force = true)
        assertNull(contacts.state.value.read)
        assertTrue(asked.none { it.contains("\"kinds\":[3]") }, "no contact list asked for")
    }

    /** Device feedback 2026-11-02: the sharer's key came minutes after the listener's read, and the show
     *  waited for the next full read. A list that did not open is looked for again on opening Discover —
     *  once a minute at most, without reading everything again. */
    @Test
    fun aListThatDidNotOpenIsLookedForAgainSoon(): Unit = runBlocking {
        val tester = KeyPair.generate()
        follows(listener, tester.publicKeyHex)
        follows(tester, listener.publicKeyHex)
        relay.publish(Event.sign(tester, 0, """{"name":"Testjupp"}""", emptyList(), now - 100))
        val testers = Device(tester)
        val testerContacts = ContactsRepository(testers.identity, testers.keys, testers.graph, { now },
            ContactShare(testers.identity, RelayPublisher(relay), testers.keys, testers.secrets, { shows }) { now })
        testerContacts.setSharing(true)
        val me = Device()
        me.contacts.setSeeing(true)
        assertTrue(me.contacts.state.value.read!!.listening().isEmpty(), "no key for this inbox yet")
        // The tester's device seals one a minute later; Discover opens on the listener's a minute after that.
        now += 60
        testerContacts.refresh(force = true)
        now += 121
        me.contacts.refresh(maxAge = 600)
        val read = me.contacts.state.value.read!!
        assertEquals(shows.toSet(), read.listening().map { it.show }.toSet())
        assertEquals("Testjupp", read.contacts.getValue(tester.publicKeyHex).name)
    }

    /** Device feedback 2026-11-05: a contact whose profile was not found stayed an npub until the next full read. */
    @Test
    fun aContactWithoutANameIsAskedForAgainSoon(): Unit = runBlocking {
        val tester = KeyPair.generate()
        follows(listener, tester.publicKeyHex)
        follows(tester, listener.publicKeyHex)
        val me = Device()
        me.keys.set(share = false, see = true)
        val testers = Device(tester)
        testers.keys.set(share = true, see = false)
        ContactShare(testers.identity, RelayPublisher(relay), testers.keys, testers.secrets, { shows }) { now }.publish(setOf(me.keys.inboxKey()!!.publicKeyHex))
        me.contacts.refresh(force = true)
        assertNull(me.contacts.state.value.read!!.contacts.getValue(tester.publicKeyHex).name)
        relay.publish(Event.sign(tester, 0, """{"name":"Testjupp","picture":"https://t.test/j.png"}""", emptyList(), now))
        now += 121
        me.contacts.refresh(maxAge = 600)
        val found = me.contacts.state.value.read!!.contacts.getValue(tester.publicKeyHex)
        assertEquals("Testjupp", found.name)
        assertEquals("https://t.test/j.png", found.picture)
        assertEquals(shows.toSet(), found.shows.toSet(), "what they share stays")
    }

    /** A look-up that finds no profile is no profile gone: the name stays through the next read. */
    @Test
    fun aNameOnceFoundStays(): Unit = runBlocking {
        val before = ContactsRead(setOf("a", "b"), mapOf(
            "a" to Contact("a", mutual = true, name = "Anna", picture = "https://a.test/a.png"),
            "b" to Contact("b", mutual = true, name = "Ben"),
        ), now)
        val read = ContactsRead(setOf("a", "b", "c"), mapOf(
            "a" to Contact("a", mutual = true),
            "b" to Contact("b", mutual = true, name = "Benjamin"),
            "c" to Contact("c", mutual = false),
        ), now + 3600).keepingNames(before)
        assertEquals("Anna", read.contacts.getValue("a").name)
        assertEquals("https://a.test/a.png", read.contacts.getValue("a").picture)
        assertEquals("Benjamin", read.contacts.getValue("b").name, "a name found now goes first")
        assertNull(read.contacts.getValue("c").name)
    }

    /** Device feedback 2026-11-02: a show subscribed by the sharer appeared only after a full read. The shared
     *  lists are looked at again every two minutes between full reads; one that does not come keeps what was there. */
    @Test
    fun aChangedSharedListShowsWithinMinutes(): Unit = runBlocking {
        val tester = KeyPair.generate()
        follows(listener, tester.publicKeyHex)
        follows(tester, listener.publicKeyHex)
        val testers = Device(tester)
        val testerContacts = ContactsRepository(testers.identity, testers.keys, testers.graph, { now },
            ContactShare(testers.identity, RelayPublisher(relay), testers.keys, testers.secrets, { shows }) { now })
        testerContacts.setSharing(true)
        val me = Device()
        me.contacts.setSeeing(true)
        now += 30
        testerContacts.refresh(force = true)
        now += 121
        me.contacts.refresh(maxAge = 600)
        assertEquals(2, me.contacts.state.value.read!!.listening().size)
        // A third show on the tester's side, sent with the next refresh there.
        shows = shows + SharedShow("guid-c", "https://c.test/feed", "Show C", 300)
        now += 30
        testerContacts.refresh()
        // Within two minutes of the last look: not yet; after: there — no full read (that waits ten minutes).
        me.contacts.refresh(maxAge = 600)
        assertEquals(2, me.contacts.state.value.read!!.listening().size)
        now += 121
        me.contacts.refresh(maxAge = 600)
        assertEquals(3, me.contacts.state.value.read!!.listening().size)
    }
}
