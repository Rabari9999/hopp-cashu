package space.sovereigncast.hopp.live

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.contacts.ContactGraph
import space.sovereigncast.hopp.contacts.ContactKeys
import space.sovereigncast.hopp.contacts.ContactShare
import space.sovereigncast.hopp.contacts.ContactsRepository
import space.sovereigncast.hopp.contacts.SharedShow
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.KtorRelayConnector
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0024 on the real relays, with two throwaway keys that follow each other: one shares, the
 * other looks — `HOPP_LIVE_CONTACTS=1`. Publishes a contact list, a relay list, a contacts note and
 * a shared list for each throwaway key; nothing of a real person's.
 */
class ContactsLiveTest {
    @Test
    fun twoThrowawayKeysShareAndSee() = runBlocking<Unit> {
        if (System.getenv("HOPP_LIVE_CONTACTS") != "1") return@runBlocking
        val http = createHttpClient(AppInfo("0.0.1", "JVM", "Test"))
        val publisher = RelayPublisher(KtorRelayConnector(http))
        val now = { System.currentTimeMillis() / 1000 }
        val anna = KeyPair.generate(); val ben = KeyPair.generate()
        println("anna ${anna.publicKeyHex}\nben  ${ben.publicKeyHex}")
        val relays = IdentityRepository.DEFAULT_RELAYS + IdentityRepository.INDEX_RELAYS
        for ((who, whom) in listOf(anna to ben, ben to anna)) {
            println("contact list: " + publisher.publish(Event.sign(who, 3, "", listOf(listOf("p", whom.publicKeyHex)), now()), relays))
            println("relay list: " + publisher.publish(Event.sign(who, 10002, "", IdentityRepository.DEFAULT_RELAYS.map { listOf("r", it) }, now()), relays))
        }
        fun device(k: KeyPair): Triple<IdentityRepository, ContactKeys, MemorySecretStore> {
            val secrets = MemorySecretStore()
            val identity = IdentityRepository(secrets, publisher, now)
            runBlocking { identity.importKey(Bech32.encode("nsec", k.secretKey)) }
            return Triple(identity, ContactKeys(identity, publisher, secrets, now), secrets)
        }
        val (annaId, annaKeys, _) = device(anna)
        annaKeys.set(share = false, see = true)
        val (benId, benKeys, benSecrets) = device(ben)
        benKeys.set(share = true, see = false)
        val shows = listOf(SharedShow("917393e3-1b1e-5cef-ace4-edaa54e1f810", "https://feeds.podcastindex.org/pc20.xml", "Podcasting 2.0", now()))
        val benContacts = ContactsRepository(benId, benKeys, ContactGraph(benId, publisher, now), now,
            ContactShare(benId, publisher, benKeys, benSecrets, { shows }, now))
        benContacts.refresh(force = true)
        println("ben: ${benContacts.state.value}")
        val annaContacts = ContactsRepository(annaId, annaKeys, ContactGraph(annaId, publisher, now), now)
        annaContacts.refresh(force = true)
        val read = annaContacts.state.value.read!!
        println("anna sees: mutual=${read.mutual.map { it.pubkey.take(8) }} withHopp=${read.withHopp.map { it.pubkey.take(8) }} shows=${read.listening().map { it.show.title }}")
        assertTrue(read.mutual.any { it.pubkey == ben.publicKeyHex }, "Ben follows Anna back")
        assertEquals(listOf("Podcasting 2.0"), read.listening().map { it.show.title })
    }
}
