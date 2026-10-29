package space.sovereigncast.hopp.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.RelayAnswer
import space.sovereigncast.hopp.nostr.RelayConnector
import space.sovereigncast.hopp.nostr.RelayLog
import space.sovereigncast.hopp.nostr.RelayOp
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.RelaySession
import space.sovereigncast.hopp.nostr.Refusal
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What each relay answered, for the sync page (maintainer 2026-10-29: errors without saying
 * which relay or why) — read and send, per document, in the relay's own words.
 */
class RelayReportTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)
    private val limited = IdentityRepository.DEFAULT_RELAYS[0]
    private val fine = IdentityRepository.DEFAULT_RELAYS[1]
    private val gone = IdentityRepository.DEFAULT_RELAYS[2]

    /** While [failing]: [limited] reads but refuses every event as rate-limited; [gone] takes no connection. */
    private var failing = true
    private val connector = RelayConnector { url ->
        if (failing && url == gone) error("connection refused")
        val s = relay.connect(url)
        if (!failing || url != limited) s else object : RelaySession {
            override val incoming = s.incoming.map { it.replace(""",true,""]""", """,false,"rate-limited: slow down"]""") }
            override suspend fun send(message: String) = s.send(message)
            override suspend fun close() = s.close()
        }
    }

    @Test
    fun aRelaysRefusalIsKeptInItsOwnWords() = runBlocking {
        val log = RelayLog()
        val event = Event.sign(KeyPair.generate(), 1, "hi", emptyList(), 1_790_000_000)
        val took = withContext(log) { RelayPublisher(connector, timeout = 2.seconds).publish(event, listOf(limited, gone, fine)) }
        assertEquals(setOf(fine), took)
        val byUrl = log.notes().associate { it.url to it.answer }
        assertEquals(RelayAnswer.Refused("rate-limited: slow down"), byUrl[limited])
        assertEquals(Refusal.RateLimited, Refusal.of((byUrl[limited] as RelayAnswer.Refused).reason))
        assertIs<RelayAnswer.Unreachable>(byUrl[gone])
        assertIs<RelayAnswer.Ok>(byUrl[fine])
        assertTrue(log.notes().all { it.op == RelayOp.Send })
    }

    @Test
    fun aFailedRoundSaysPerRelayAndDocumentWhatWentWrong() = runBlocking {
        val device = SyncDevice(connector, scope, nsec)
        device.settings.update { it.copy(autoDownloads = !it.autoDownloads) }
        val status = device.sync.enable()
        assertNotNull(status.problem, "one of three relays took it: no majority")
        val rounds = status.relayRounds.associateBy { it.url }
        assertEquals(setOf(limited, gone, fine), rounds.keys)
        assertTrue(rounds.getValue(fine).ok)
        // Read, then refused when sending — here the first keyring, which needs a majority before anything else.
        val refused = rounds.getValue(limited)
        assertIs<RelayAnswer.Ok>(refused.reads[SyncDocs.KEYRING])
        assertEquals(RelayAnswer.Refused("rate-limited: slow down"), refused.sends[SyncDocs.KEYRING])
        assertEquals(RelayAnswer.Refused("rate-limited: slow down"), refused.worst)
        assertIs<RelayAnswer.Unreachable>(rounds.getValue(gone).worst)
        assertEquals(listOf(false), status.relayHistory[limited])
        assertEquals(listOf(true), status.relayHistory[fine])
    }

    @Test
    fun aRefusedChangeStandsUnderItsDocument() = runBlocking {
        failing = false
        val device = SyncDevice(connector, scope, nsec)
        val first = device.sync.enable()
        assertEquals(null, first.problem)
        assertTrue(first.relayRounds.all { it.ok })
        failing = true
        device.ms += 3_600_000
        device.settings.update { it.copy(autoDownloads = !it.autoDownloads) }
        val status = device.sync.syncNow()
        val refused = status.relayRounds.first { it.url == limited }
        assertEquals(RelayAnswer.Refused("rate-limited: slow down"), refused.sends[SyncDocs.SETTINGS])
        assertIs<RelayAnswer.Ok>(refused.reads[SyncDocs.SETTINGS])
        assertEquals(listOf(true, false), status.relayHistory[limited])
    }

    @Test
    fun theWorstAnswerIsTheOneThatSaysWhy() {
        val r = RelayRound("wss://r.test", mapOf("a" to RelayAnswer.Silent), mapOf("a" to RelayAnswer.Refused("blocked: no"), "b" to RelayAnswer.Ok(5)))
        assertEquals(RelayAnswer.Refused("blocked: no"), r.worst)
        assertEquals(Refusal.Other, Refusal.of("slow down"))
        assertEquals(Refusal.AuthRequired, Refusal.of("auth-required: sign in first"))
    }
}
