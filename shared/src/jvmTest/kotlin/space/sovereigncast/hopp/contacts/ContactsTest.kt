package space.sovereigncast.hopp.contacts

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.Nip78
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.Test
import kotlin.test.assertEquals
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
        phone.contacts.refresh(force = true)
        assertTrue(phone.contacts.state.value.read!!.mutual.isEmpty())
    }

    // Stage 2: sharing (ADR 0024, 4).

    private var shows = listOf(SharedShow("guid-a", "https://a.test/feed", "Show A", 100), SharedShow(null, "https://b.test/feed", "Show B", 200))

    private fun share(device: Device) = ContactShare(device.identity, RelayPublisher(relay), device.keys, device.secrets, { shows }) { now }

    private fun byShareKey(share: KeyPair) = relay.stored().filter { it.pubkey == share.publicKeyHex && it.kind == Nip78.KIND }.distinctBy { it.id }

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
}
