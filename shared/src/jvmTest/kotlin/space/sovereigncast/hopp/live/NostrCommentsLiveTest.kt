package space.sovereigncast.hopp.live

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.comments.CommentAnchor
import space.sovereigncast.hopp.comments.CommentReader
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.nostr.KtorRelayConnector
import space.sovereigncast.hopp.nostr.RelayPublisher
import space.sovereigncast.hopp.nostr.noteRef
import space.sovereigncast.hopp.nostr.profileKey
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.sovereigncast.ListenerApi
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Nodesignal's newest episode on the real relays: its notes from the
 * instance, then whatever the relays hold about it. `HOPP_LIVE_NOSTR=1`
 * (and optionally `HOPP_LIVE_EPISODE=<guid>`) to run.
 */
class NostrCommentsLiveTest {
    @Test
    fun nodesignalsCommentsLoad() = runBlocking<Unit> {
        if (System.getenv("HOPP_LIVE_NOSTR") != "1") return@runBlocking
        val http = createHttpClient(AppInfo("0.0.1", "JVM", "Test"))
        val guid = "e8df0b13-47de-544a-99b7-ec7cbd960a16"
        val episode = System.getenv("HOPP_LIVE_EPISODE") ?: "88b5b89b-6750-4dd7-ad87-314c90246843"
        val notes = ListenerApi(http).episodeNotes("https://pm.nodesignal.space", guid, episode).notes
        val anchor = CommentAnchor(episode, guid, notes.mapNotNull { noteRef(it.uri) }, notes.mapNotNull { it.authorNpub?.let(::profileKey) }.toSet())
        println("live anchor: $anchor")
        val publisher = RelayPublisher(KtorRelayConnector(http))
        val repo = CommentReader(publisher)
        val started = System.nanoTime()
        val thread = repo.load(anchor)
        val loaded = (System.nanoTime() - started) / 1e9
        val names = repo.authors(thread.people, thread.relays)
        println("live comments: ${thread.count} from ${thread.relays} in %.1f s, names after %.1f s".format(loaded, (System.nanoTime() - started) / 1e9))
        thread.comments.forEach { c ->
            println("- ${names[c.pubkey]?.name ?: c.pubkey.take(8)}: ${c.content.take(80).replace('\n', ' ')}")
            c.replies.forEach { r -> println("    ↪ ${names[r.pubkey]?.name ?: r.pubkey.take(8)}: ${r.content.take(70).replace('\n', ' ')}") }
        }
        println("live unnamed: ${(thread.people - names.keys)}")
        assertTrue(anchor.notes.isNotEmpty())
    }
}
