package space.sovereigncast.hopp.contacts

import kotlinx.coroutines.runBlocking
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
    private inner class Device {
        val secrets = MemorySecretStore()
        val identity = IdentityRepository(secrets, RelayPublisher(relay), { now }).also {
            runBlocking { it.importKey(Bech32.encode("nsec", listener.secretKey)) }
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
}
