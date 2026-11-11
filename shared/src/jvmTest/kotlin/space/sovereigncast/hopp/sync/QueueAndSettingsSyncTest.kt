package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.nostr.Bech32
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.value.FakeRelay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Up next" and listening habits in step over Nostr (package 5 of docs/tasks/nostr-sync.md). */
class QueueAndSettingsSyncTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)
    private val feed = "https://pod.test/nodesignal.xml"

    @AfterTest fun stop() = scope.cancel()

    private suspend fun SyncDevice.id(guid: String) = episode(feed, guid).id
    private suspend fun guids(d: SyncDevice) = d.db.episodeQueries.selectByPodcast(d.row(feed).id).awaitAsList().map { it.guid }

    /** Maintainer 2026-11-06: the episode started on the phone was nowhere on the tablet. */
    @Test
    fun whatPlaysNowReachesTheOtherDevice() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val (a, b) = guids(phone).take(2)
        phone.queue.playLast(phone.id(a))
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        assertTrue(tablet.nowAdopted.isEmpty(), "nothing started yet")

        // Taken out of "Up next" and started on the phone.
        phone.ms += 60_000
        phone.queue.remove(phone.id(a))
        phone.queue.noteNow(phone.id(a))
        phone.sync.syncNow()
        tablet.ms += 61_000
        tablet.sync.syncNow()
        assertEquals(listOf(tablet.id(a)), tablet.nowAdopted)
        assertTrue(tablet.queueGuids().isEmpty(), "out of the playlist there too")

        // Nothing new, nothing again: going on after a pause sends nothing, and no second round brings it back.
        val noted = phone.store.entry(SyncDocs.QUEUE, SyncDocs.NOW_KEY)
        phone.ms += 60_000
        phone.queue.noteNow(phone.id(a))
        assertEquals(noted, phone.store.entry(SyncDocs.QUEUE, SyncDocs.NOW_KEY))
        tablet.ms += 60_000
        tablet.sync.syncNow()
        assertEquals(1, tablet.nowAdopted.size)

        // The tablet starts another: the newest start wins on the phone.
        tablet.ms += 60_000
        tablet.queue.noteNow(tablet.id(b))
        tablet.sync.syncNow()
        phone.ms += 120_000
        phone.sync.syncNow()
        assertEquals(listOf(phone.id(b)), phone.nowAdopted)
    }

    /** A Hopp from before this passes over the entry: without `show` and `guid` it is no place in "Up next". */
    @Test
    fun whatPlaysNowIsNoPlaceInUpNextForAnOlderHopp() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        phone.sync.enable()
        phone.queue.noteNow(phone.id(guids(phone).first()))
        val entry = assertNotNull(phone.store.entry(SyncDocs.QUEUE, SyncDocs.NOW_KEY))
        assertNull(entry.d["show"]); assertNull(entry.d["guid"])
        // And a device without the episode counts nothing as waiting for it.
        val doc = QueueDocument(phone.db, phone.queue)
        assertEquals(setOf(SyncDocs.NOW_KEY), doc.beyondReach(mapOf(SyncDocs.NOW_KEY to entry)))
    }

    /**
     * Maintainer 2026-11-09: an episode out since the tablet last read its feed was nowhere there — what
     * plays now and where waited for the next look at the feed, hours away. Its feed is read at once.
     */
    @Test
    fun anEpisodeNotHereYetIsFetchedFromItsFeedAtOnce() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val newest = guids(phone).first()
        phone.sync.enable()
        // The tablet read the feed before the episode was out.
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.feedText = { it.replace(Regex("<item>(?:(?!</item>).)*?${Regex.escape(newest)}.*?</item>", RegexOption.DOT_MATCHES_ALL), "") }
        tablet.ms += 1_000
        tablet.sync.enable()
        assertTrue(newest !in guids(tablet), "not out yet when the tablet read it")
        tablet.feedText = { it }

        // Started on the phone, twelve minutes in.
        phone.ms += 60_000
        phone.episodes.savePosition(phone.id(newest), 720_000, paused = true)
        phone.queue.noteNow(phone.id(newest))
        phone.sync.syncNow()
        val reads = tablet.feedReads.size
        tablet.ms += 61_000
        tablet.sync.syncNow()
        assertEquals(1, tablet.feedReads.size - reads, "its feed, once")
        assertEquals(listOf(tablet.id(newest)), tablet.nowAdopted)
        assertEquals(720_000, tablet.episode(feed, newest).position_ms, "where the phone left it")

        // Read now: not asked again each round.
        tablet.ms += 60_000
        tablet.sync.syncNow()
        assertEquals(1, tablet.feedReads.size - reads)
    }

    /** A feed that no longer carries the episode is read once for it, not each round. */
    @Test
    fun aFeedWithoutTheEpisodeIsReadOnceForIt() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val oldest = guids(phone).last()
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        val without: (String) -> String = { it.replace(Regex("<item>(?:(?!</item>).)*?${Regex.escape(oldest)}.*?</item>", RegexOption.DOT_MATCHES_ALL), "") }
        tablet.feedText = without
        tablet.ms += 1_000
        tablet.sync.enable()

        phone.ms += 60_000
        phone.queue.noteNow(phone.id(oldest))
        phone.sync.syncNow()
        val reads = tablet.feedReads.size
        tablet.ms += 61_000
        tablet.sync.syncNow()
        tablet.ms += 60_000
        tablet.sync.syncNow()
        assertEquals(1, tablet.feedReads.size - reads, "once, then it is known the feed has it not")
        assertTrue(tablet.nowAdopted.isEmpty())
    }

    /** Coming back to the app within the quarter of an hour: only what plays now and where, at most every two minutes. */
    @Test
    fun comingBackReadsWhatPlaysNowAndNotEverything() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val a = guids(phone).first()
        phone.sync.enable()
        phone.sync.syncNow()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        tablet.sync.syncNow()
        phone.ms += 60_000
        phone.queue.noteNow(phone.id(a))
        phone.sync.syncNow()

        // A moment after the round: nothing asked.
        tablet.ms += 60_000
        var before = relay.reads.size
        tablet.sync.onForeground()
        kotlinx.coroutines.delay(300)
        assertEquals(before, relay.reads.size, "within two minutes")
        assertTrue(tablet.nowAdopted.isEmpty())

        // Later, still within the quarter of an hour: it is there.
        tablet.ms += 3 * 60_000
        tablet.sync.onForeground()
        kotlinx.coroutines.withTimeout(5_000) { while (tablet.nowAdopted.isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(listOf(tablet.id(a)), tablet.nowAdopted)
    }

    @Test
    fun upNextFollowsAndAReorderAndAnAdditionBothSurvive() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val (a, b, c, d) = guids(phone).take(4)
        phone.queue.playLast(phone.id(a))
        phone.queue.playLast(phone.id(b))
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        assertEquals(listOf(a, b), tablet.queueGuids())

        // The phone moves b to the front, the tablet adds c at the end — before either syncs.
        phone.ms += 60_000
        phone.queue.reorder(listOf(phone.id(b), phone.id(a)))
        tablet.ms += 61_000
        tablet.queue.playLast(tablet.id(c))
        phone.sync.syncNow(); tablet.sync.syncNow(); phone.sync.syncNow()
        assertEquals(listOf(b, a, c), phone.queueGuids())
        assertEquals(listOf(b, a, c), tablet.queueGuids())

        // Played on the phone: out of the queue there, and on the tablet.
        phone.ms += 60_000
        phone.queue.pop()
        phone.queue.playNext(phone.id(d))
        phone.sync.syncNow()
        tablet.ms += 120_000
        tablet.sync.syncNow()
        assertEquals(listOf(d, a, c), tablet.queueGuids())
    }

    /** Device feedback 2026-10-25: marked heard on one device, it leaves "Up next" on both — also
     *  where a finished download put it there, which is not shared. */
    @Test
    fun aHeardEpisodeLeavesUpNextOnEveryDevice() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val (a, b, c) = guids(phone).take(3)
        phone.queue.playLast(phone.id(a))
        phone.queue.playLast(phone.id(b))
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        // Put there by its download, on the tablet only.
        tablet.db.queueQueries.addLast(tablet.id(c))
        assertEquals(listOf(a, b, c), tablet.queueGuids())

        phone.ms += 60_000
        phone.episodes.setPlayed(phone.id(a), true)
        phone.episodes.setPlayed(phone.id(c), true)
        assertEquals(listOf(b), phone.queueGuids())
        phone.sync.syncNow()
        tablet.ms += 120_000
        tablet.sync.syncNow()
        assertEquals(listOf(b), tablet.queueGuids())
    }

    /**
     * Maintainer 2026-11-03: two new episodes went to "Up next" on the phone overnight and never showed in the
     * browser. A new episode now goes there wherever a refresh finds it, in step — and one taken out on the
     * phone does not come back when the browser, not synced since, finds it next morning.
     */
    @Test
    fun aNewEpisodeInUpNextFollowsAndOneTakenOutStaysOut() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        phone.sync.enable()
        val browser = SyncDevice(relay, scope, nsec)
        browser.ms += 1_000
        browser.sync.enable()
        val (x, y) = guids(phone).take(2)
        suspend fun SyncDevice.new(guid: String) = queue.addNew(listOf(episode(feed, guid).let { it.id to it.published_at }))
        // A day after the newest came out: the test feed's episodes lie ahead of the devices' clocks.
        val day = (phone.episode(feed, x).published_at!! + 86_400) * 1000
        phone.ms = day; browser.ms = day + 1_000

        phone.ms += 60_000
        phone.new(x)
        phone.sync.syncNow()
        browser.ms += 120_000
        browser.sync.syncNow()
        assertEquals(listOf(x), browser.queueGuids(), "the phone's new episode in the browser too")

        // Overnight on the phone: y comes, and is taken out again.
        phone.ms += 600_000
        phone.new(y)
        phone.ms += 60_000
        phone.queue.remove(phone.id(y))
        phone.sync.syncNow()
        // Next morning the browser finds y itself, before its round.
        browser.ms += 3_600_000
        browser.new(y)
        browser.sync.syncNow()
        assertEquals(listOf(x), browser.queueGuids(), "taken out on the phone, it stays out")
        phone.ms += 3_700_000
        phone.sync.syncNow()
        assertEquals(listOf(x), phone.queueGuids())
    }

    /** What a finished download put in "Up next" before 0.4.0 was never synced: noted at the next start. */
    @Test
    fun whatADownloadPutInUpNextBeforeGoesOutAtTheNextStart() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val (a, b) = guids(phone).take(2)
        val day = (phone.episode(feed, a).published_at!! + 86_400) * 1000
        phone.ms = day
        phone.queue.playLast(phone.id(a))
        phone.sync.enable()
        val browser = SyncDevice(relay, scope, nsec)
        browser.ms = day + 1_000
        browser.sync.enable()
        // As a download did it until now: in the queue here only.
        phone.db.queueQueries.addLast(phone.id(b))
        phone.ms += 60_000
        phone.sync.syncNow()
        browser.ms += 120_000
        browser.sync.syncNow()
        assertEquals(listOf(a), browser.queueGuids(), "the same start: still unknown")

        val again = SyncDevice(relay, scope, nsec, db = phone.db, secrets = phone.secrets)
        again.ms = phone.ms + 600_000
        again.sync.syncNow()
        browser.ms += 1_200_000
        browser.sync.syncNow()
        assertEquals(listOf(a, b), browser.queueGuids())
    }

    /** Maintainer 2026-11-10: a bot muted in the comments on the phone commented again in the browser. Who is muted and
     *  what is hidden go along, and what two devices added meanwhile adds up. */
    @Test
    fun mutedPeopleAndHiddenCommentsGoAlong() = runBlocking<Unit> {
        val (bot, troll, spam) = listOf("a", "b", "c").map { it.repeat(64) }
        val phone = SyncDevice(relay, scope, nsec)
        phone.sync.enable()
        val browser = SyncDevice(relay, scope, nsec).also { it.ms = phone.ms + 60_000 }
        browser.sync.enable()

        phone.ms = browser.ms + 60_000
        phone.settings.update { it.copy(mutedLocally = it.mutedLocally + bot, hiddenComments = it.hiddenComments + spam) }
        browser.settings.update { it.copy(mutedLocally = it.mutedLocally + troll) }
        phone.sync.syncNow()
        browser.ms = phone.ms + 60_000
        browser.sync.syncNow()
        phone.ms = browser.ms + 60_000
        phone.sync.syncNow()
        assertEquals(setOf(bot, troll), browser.settings.current().mutedLocally)
        assertEquals(setOf(bot, troll), phone.settings.current().mutedLocally)
        assertEquals(setOf(spam), browser.settings.current().hiddenComments)

        // A new device gets them with the rest.
        val tablet = SyncDevice(relay, scope, nsec).also { it.ms = phone.ms + 60_000 }
        tablet.sync.enable()
        assertEquals(setOf(bot, troll), tablet.settings.current().mutedLocally)

        // Only a key or an event's id.
        SettingsDocument(tablet.settings).apply(mapOf(Settings.MUTED + "nope" to SyncEntry(1, d = SyncDocs.setting("1"))))
        assertEquals(setOf(bot, troll), tablet.settings.current().mutedLocally)
    }

    @Test
    fun listeningHabitsFollowTheWalletAndBudgetStay() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.settings.update { it.copy(skipForwardSeconds = 45, defaultSpeed = 1.3f, senderName = "tg", dailyBudgetSats = 21_000, storageLimitMb = 0) }
        phone.sync.enable()

        // A tablet already in use: its own habits give way to the phone's, its budget stays its own.
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.settings.update { it.copy(skipForwardSeconds = 15, dailyBudgetSats = 1_000) }
        tablet.ms += 1_000
        tablet.sync.enable()
        val t = tablet.settings.current()
        assertEquals(45, t.skipForwardSeconds)
        assertEquals(1.3f, t.defaultSpeed)
        assertEquals("tg", t.senderName)
        assertEquals(1_000, t.dailyBudgetSats)
        assertEquals(2_048, t.storageLimitMb)

        // Changed later on the tablet: the phone follows.
        tablet.ms += 60_000
        tablet.settings.update { it.copy(skipBackSeconds = 30) }
        tablet.sync.syncNow()
        phone.ms += 120_000
        phone.sync.syncNow()
        assertEquals(30, phone.settings.current().skipBackSeconds)
        assertEquals(21_000, phone.settings.current().dailyBudgetSats)
    }

    /**
     * Delta audit 2026-10-22, HN-01 (seen live: one relay answering 503): the first round fails, the app is
     * started again, sync goes through later — "Up next" and the settings still arrive on the next device,
     * and that device's defaults do not come back over them.
     */
    @Test
    fun aFirstSyncThatFailsKeepsUpNextAndSettings() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val e296 = guids(phone)[1]
        phone.queue.playLast(phone.id(e296))
        phone.settings.update { it.copy(skipBackSeconds = 5, skipForwardSeconds = 45, defaultSpeed = 1.3f) }
        relay.readsFail = true
        assertTrue(phone.sync.enable().problem != null)
        relay.readsFail = false

        val restarted = SyncDevice(relay, scope, nsec, db = phone.db, secrets = phone.secrets).also { it.ms = phone.ms + 60_000 }
        assertEquals(null, restarted.sync.syncNow(interactive = true).problem)

        val tablet = SyncDevice(relay, scope, nsec).also { it.ms = restarted.ms + 60_000 }
        tablet.sync.enable()
        assertEquals(listOf(e296), tablet.queueGuids())
        val t = tablet.settings.current()
        assertEquals(Triple(5, 45, 1.3f), Triple(t.skipBackSeconds, t.skipForwardSeconds, t.defaultSpeed))

        restarted.ms = tablet.ms + 60_000
        restarted.sync.syncNow()
        val p = restarted.settings.current()
        assertEquals(Triple(5, 45, 1.3f), Triple(p.skipBackSeconds, p.skipForwardSeconds, p.defaultSpeed))
    }

    /** Delta audit 2026-10-22, MN-06: a place at the very end of a Long from elsewhere does not send the next
     *  "play last" to the front. */
    @Test
    fun aPlaceAtTheEndOfALongDoesNotWrap() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val (first, second) = guids(phone).take(2)
        val show = SyncDocs.showKey(phone.row(feed).podcast_guid, feed)
        QueueDocument(phone.db, phone.queue).apply(mapOf(SyncDocs.queueKey(show, first) to SyncEntry(phone.ms, d = SyncDocs.queued(show, first, Long.MAX_VALUE))))
        phone.queue.playLast(phone.id(second))
        assertEquals(listOf(first, second), phone.queueGuids())
    }

    /** Delta audit 2, X04: at that edge, two "play last" in a row stay in the order they were added — and
     *  "play next" goes first. */
    @Test
    fun atTheEdgeTheLastOneAddedIsLast() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.podcasts.subscribe(feed)
        val (first, second, third, fourth) = guids(phone).take(4)
        val show = SyncDocs.showKey(phone.row(feed).podcast_guid, feed)
        QueueDocument(phone.db, phone.queue).apply(mapOf(SyncDocs.queueKey(show, first) to SyncEntry(phone.ms, d = SyncDocs.queued(show, first, Long.MAX_VALUE))))
        // Either way round by name: the order must not fall to it.
        for ((a, b) in listOf(second to third, third to second)) {
            phone.queue.remove(phone.id(second)); phone.queue.remove(phone.id(third))
            phone.queue.playLast(phone.id(a))
            phone.queue.playLast(phone.id(b))
            assertEquals(listOf(first, a, b), phone.queueGuids())
        }
        phone.queue.playNext(phone.id(fourth))
        assertEquals(fourth, phone.queueGuids().first())
    }

    /** What was hidden under Home's "Take part" stays hidden on the other device (device feedback 2026-10-22). */
    @Test
    fun whatWasHiddenUnderTakePartFollows() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.settings.update { it.copy(takePartHidden = listOf("suggestions:g1", "rb:g2/e7")) }
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        assertEquals(listOf("suggestions:g1", "rb:g2/e7"), tablet.settings.current().takePartHidden)
    }

    /** Device feedback 2026-10-21: a phone that never had a boost name, and a new device that
     *  took the name from the Nostr profile — the restore wiped it, and Home greeted no one. */
    @Test
    fun aBoostNameNobodyGaveDoesNotWipeTheProfileName() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.settings.update { it.copy(skipForwardSeconds = 45) }
        phone.sync.enable()

        val tablet = SyncDevice(relay, scope, nsec)
        tablet.settings.update { it.copy(senderName = "Thorsten") }
        tablet.ms += 1_000
        tablet.sync.enable()
        assertEquals("Thorsten", tablet.settings.current().senderName)
        assertEquals(45, tablet.settings.current().skipForwardSeconds)

        // Relays from a build before this fix hold the blank name as a seed: still not taken.
        SettingsDocument(tablet.settings).apply(mapOf("sender_name" to SyncEntry(0, d = SyncDocs.setting(""))))
        assertEquals("Thorsten", tablet.settings.current().senderName)

        // Cleared on purpose on the phone: that one counts.
        phone.ms += 60_000
        phone.settings.update { it.copy(senderName = "x") }
        phone.settings.update { it.copy(senderName = "") }
        phone.sync.syncNow()
        tablet.ms += 120_000
        tablet.sync.syncNow()
        assertEquals("", tablet.settings.current().senderName)
    }

    /** Maintainer 2026-10-23: downloads, the background refresh and what boosts and zaps report travel too;
     *  automatic downloads turned off elsewhere drop what waits here for Wi-Fi unasked, as by hand. */
    @Test
    fun downloadsRefreshAndReportsFollow() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.settings.update {
            it.copy(newToQueue = false, refreshHours = 6, refreshWifiOnly = true,
                boostFeedback = Settings.FEEDBACK_ALWAYS, quickBoostFeedback = Settings.FEEDBACK_OFF, zapFeedback = Settings.FEEDBACK_ALWAYS)
        }
        phone.sync.enable()

        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        val t = tablet.settings.current()
        assertEquals(false, t.newToQueue)
        assertEquals(6, t.refreshHours)
        assertEquals(true, t.refreshWifiOnly)
        assertEquals(Settings.FEEDBACK_ALWAYS, t.boostFeedback)
        assertEquals(Settings.FEEDBACK_OFF, t.quickBoostFeedback)
        assertEquals(Settings.FEEDBACK_ALWAYS, t.zapFeedback)
        assertEquals(true, t.autoDownloads)
        assertEquals(0, tablet.autoDownloadsDropped)

        phone.ms += 60_000
        phone.settings.update { it.copy(autoDownloads = false) }
        phone.sync.syncNow()
        tablet.ms += 120_000
        tablet.sync.syncNow()
        assertEquals(false, tablet.settings.current().autoDownloads)
        assertEquals(1, tablet.autoDownloadsDropped)

        // Only what this Hopp could set: an hour not offered, a report it does not know.
        SettingsDocument(tablet.settings).apply(mapOf(
            "refresh_hours" to SyncEntry(1, d = SyncDocs.setting("5")),
            "zap_feedback" to SyncEntry(1, d = SyncDocs.setting("loud")),
        ))
        assertEquals(6, tablet.settings.current().refreshHours)
        assertEquals(Settings.FEEDBACK_ALWAYS, tablet.settings.current().zapFeedback)
    }

    /** Device feedback 2026-10-24: restored on a new device, a show's newest episode was downloaded although
     *  automatic downloads were off — the shows came before the settings that said so. */
    @Test
    fun aRestoreWithAutomaticDownloadsOffQueuesNone() = runBlocking<Unit> {
        val phone = SyncDevice(relay, scope, nsec)
        phone.settings.update { it.copy(autoDownloads = false) }
        phone.podcasts.subscribe(feed)
        phone.sync.enable()

        val tablet = SyncDevice(relay, scope, nsec)
        tablet.ms += 1_000
        tablet.sync.enable()
        assertEquals(setOf(feed), tablet.urls())
        assertEquals(false, tablet.settings.current().autoDownloads)
        val queued = tablet.db.episodeQueries.selectByPodcast(tablet.row(feed).id).awaitAsList().filter { it.download_state != 0L }
        assertEquals(emptyList(), queued.map { it.guid })
    }

    /** A setting synced only since an update, on two devices that synced before it: the first one's goes out
     *  at its next round, and the other takes it — its own default does not win over it. */
    @Test
    fun aSettingSyncedSinceAnUpdateJoinsOnDevicesAlreadyInStep() = runBlocking<Unit> {
        // Both in step under a build that did not sync the background refresh: each keeps its own.
        val phone = SyncDevice(relay, scope, nsec).apply { notSyncedYet = setOf("refresh_hours") }
        phone.settings.update { it.copy(refreshHours = 12) }
        phone.sync.enable()
        val tablet = SyncDevice(relay, scope, nsec).apply { notSyncedYet = setOf("refresh_hours") }
        tablet.ms += 1_000
        tablet.sync.enable()
        assertEquals(1, tablet.settings.current().refreshHours)

        // Updated: the phone's goes out at its next round, the tablet takes it.
        phone.notSyncedYet = emptySet(); tablet.notSyncedYet = emptySet()
        phone.ms += 60_000
        phone.sync.syncNow()
        tablet.ms += 120_000
        tablet.sync.syncNow()
        assertEquals(12, tablet.settings.current().refreshHours)
        assertEquals(12, phone.settings.current().refreshHours)
    }
}
