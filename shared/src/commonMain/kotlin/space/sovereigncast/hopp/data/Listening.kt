package space.sovereigncast.hopp.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.platform.SecretStore
import space.sovereigncast.hopp.platform.ioDispatcher
import space.sovereigncast.hopp.sync.SyncDocs
import space.sovereigncast.hopp.sync.SyncJournal
import space.sovereigncast.hopp.sync.StatsEntries
import space.sovereigncast.hopp.sync.StatsRetention
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Which stretch the statistics page shows. */
enum class StatsRange { Month, Year, All }

/** One bar of the page's chart: a day of the month, a month of the year, or a year. */
data class StatsBar(val label: String, val heardMs: Long)

data class ShowStats(val show: String, val title: String, val heardMs: Long, val givenMsat: Long)

data class EpisodeStats(val title: String, val showTitle: String, val heardMs: Long)

/** What the statistics page shows for one [range], every device together. */
data class ListeningStats(
    val range: StatsRange,
    val heardMs: Long,
    /** By speed and skipped silence: the episodes' own time beyond the time it took. */
    val savedBySpeedMs: Long,
    /** By the shows' skipped starts and ends. */
    val savedBySkipsMs: Long,
    val episodes: Int,
    val bars: List<StatsBar>,
    val topShows: List<ShowStats>,
    val topEpisodes: List<EpisodeStats>,
    val boostMsat: Long,
    val streamMsat: Long,
    val zapMsat: Long,
    /** The shows heard in [range], and of those the ones given to. */
    val showsHeard: Int,
    val showsSupported: Int,
    /** The longest run of days with listening, as far as days are kept (400). */
    val longestStreakDays: Int,
    val chapters: Long,
    val transcripts: Long,
    val comments: Long,
    /** The first day counted; null before anything was. */
    val since: LocalDate?,
    /** [StatsRange.All] only: the length of the episodes played before [since] — an estimate. */
    val estimatedBeforeMs: Long?,
) {
    val givenMsat: Long get() = boostMsat + streamMsat + zapMsat
}

/**
 * Listening statistics (maintainer 2026-10-26): what this device heard, counted as it plays —
 * per day, per month and show, per episode — and with sync on (hopp/stats) the other devices'
 * counts beside it, each device writing only its own ([device]), so nothing is ever merged over.
 * Nothing of it leaves the device but encrypted to the listener's own key.
 */
class ListeningRepository(
    private val db: HoppDatabase,
    private val secrets: SecretStore,
    private val clock: Clock,
    private val journal: SyncJournal = SyncJournal.Off,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val lq = db.listeningQueries
    private val lock = Mutex()

    /**
     * This install's name in the statistics: random, kept with the secrets, which no backup
     * takes along — a phone restored from this one's backup counts as a device of its own,
     * not as this one, whose counts it would overwrite.
     */
    val device: String by lazy {
        runCatching { secrets.get(DEVICE_KEY) }.getOrNull()
            ?: Random.nextBytes(6).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                .also { runCatching { secrets.put(DEVICE_KEY, it) } }
    }

    private data class Key(val day: Long, val podcastId: Long, val guid: String)
    private class Heard(val title: String) { var heard = 0L; var media = 0L; var skipped = 0L }
    private val pending = mutableMapOf<Key, Heard>()
    private var pendingMs = 0L
    private val counters = LongArray(3)
    /** When this device's counts were last noted for sync; null: not yet since the start. */
    private var lastNotedMs: Long? = null

    /** [heardMs] of the clock and [mediaMs] of [item]'s own time, heard just now. */
    suspend fun heard(item: EpisodeItem, heardMs: Long, mediaMs: Long) = lock.withLock {
        if (heardMs <= 0 && mediaMs <= 0) return@withLock
        pending.getOrPut(Key(today(), item.podcastId, item.guid)) { Heard(item.title) }.let { it.heard += heardMs; it.media += mediaMs }
        pendingMs += heardMs
        if (pendingMs >= FLUSH_EVERY_MS) flushLocked(note = false)
    }

    /** [ms] of [item] left out as its show asks — its start or its end. */
    suspend fun skipped(item: EpisodeItem, ms: Long) = lock.withLock {
        pending.getOrPut(Key(today(), item.podcastId, item.guid)) { Heard(item.title) }.skipped += ms
    }

    /** A chapter jumped to, a transcript opened, a comment or boost posted on Nostr. */
    suspend fun counted(chapters: Int = 0, transcripts: Int = 0, comments: Int = 0) = lock.withLock {
        counters[0] += chapters.toLong(); counters[1] += transcripts.toLong(); counters[2] += comments.toLong()
    }

    /** What is counted into the database; [note]: listening paused — sync hears of it soon;
     *  [force]: the app goes to the background — sync hears of all of it now. */
    suspend fun flush(note: Boolean = true, force: Boolean = false) = lock.withLock { flushLocked(note, force) }

    private suspend fun flushLocked(note: Boolean, force: Boolean = false) = withContext(ioDispatcher) {
        val heard = pending.toMap()
        pending.clear(); pendingMs = 0
        val counted = counters.copyOf()
        counters.fill(0)
        if (heard.isNotEmpty() || counted.any { it != 0L }) db.transaction {
            for ((k, h) in heard) {
                val show = db.podcastQueries.selectById(k.podcastId).executeAsOneOrNull() ?: continue
                val key = SyncDocs.showKey(show.podcast_guid, show.feed_url)
                val month = monthOf(LocalDate.fromEpochDays(k.day))
                lq.ensureDay(device, k.day); lq.addDay(h.heard, h.media, device, k.day)
                lq.ensureShow(device, month, key); lq.addShow(h.heard, h.media, h.skipped, show.title.take(TITLE), device, month, key)
                lq.ensureEpisode(device, key, k.guid, month); lq.addEpisode(h.heard, h.media, month, h.title.take(TITLE), device, key, k.guid)
            }
            if (counted.any { it != 0L }) {
                val month = monthOf(LocalDate.fromEpochDays(today()))
                lq.ensureCount(device, month); lq.addCount(counted[0], counted[1], counted[2], device, month)
            }
        }
        val now = clock.now() * 1000
        val since = lastNotedMs?.let { now - it } ?: Long.MAX_VALUE
        if (journal.enabled && (force || (note && since >= NOTE_PAUSED_MS) || since >= NOTE_EVERY_MS)) {
            lastNotedMs = now
            noteChanged()
        }
    }

    /** This device's counts that differ from what sync last had of them, noted — not urgent: they
     *  ride along with the next round, never one of their own (at most every [NOTE_EVERY_MS]). */
    private fun noteChanged() {
        sats()
        val today = today()
        for ((key, data) in own()) {
            // What sync drops as too old is not noted again (review 2026-10-27): it would come back
            // as pending with every note, and every round would send and drop it once more.
            if (StatsRetention.expired(key, data, today)) continue
            if (journal.current(SyncDocs.STATS, key)?.d != data) journal.record(SyncDocs.STATS, key, data, urgent = false)
        }
    }

    /** The sats this device gave, per month and show, from the payment log — set, not added. */
    private fun sats() = db.transaction {
        val byShow = mutableMapOf<Pair<Long, String>, LongArray>()
        val titles = mutableMapOf<String, String>()
        for (p in db.paymentQueries.forStats().executeAsList()) {
            val key = p.feed_url?.let { SyncDocs.showKey(p.podcast_guid, it) } ?: p.podcast_title?.takeIf { it.isNotBlank() }?.let { "title:$it" } ?: continue
            p.podcast_title?.let { titles[key] = it }
            val sums = byShow.getOrPut(monthOf(dateOf(p.created_at)) to key) { LongArray(3) }
            sums[when (p.kind) { "stream" -> 1; "zap" -> 2; else -> 0 }] += p.amount_msat
        }
        // A show's key can change under its payments — unsubscribed, the title stands in for the
        // feed; a guid added later — and a sum left under the old key counted twice (review
        // 2026-10-27). The log has every payment: what it no longer names is nothing.
        lq.clearShowSats(device)
        for ((k, sums) in byShow) {
            lq.ensureShow(device, k.first, k.second)
            lq.setShowSats(sums[0], sums[1], sums[2], (titles[k.second] ?: "").take(TITLE), device, k.first, k.second)
        }
    }

    /** This device's counts as sync entries ([StatsEntries]). */
    internal fun own(): Map<String, JsonObject> = StatsEntries.of(
        device, lq.daysOf(device).executeAsList(), lq.showsOf(device).executeAsList(),
        lq.episodesOf(device).executeAsList(), lq.countsOf(device).executeAsList(),
    )

    /** For sync turned on: this device's counts so far, the sats included. */
    suspend fun snapshot(): Map<String, JsonObject> = lock.withLock {
        withContext(ioDispatcher) { sats(); val today = today(); own().filterNot { (k, d) -> StatsRetention.expired(k, d, today) } }
    }

    // ── the page ─────────────────────────────────────────────────────────

    suspend fun stats(range: StatsRange): ListeningStats {
        flush(note = false)
        return withContext(ioDispatcher) {
            // The sats from the payment log first: noted for sync only when it is on, they were
            // missing from the page without it (review 2026-10-27).
            sats()
            val today = LocalDate.fromEpochDays(today())
            val (fromMonth, toMonth) = when (range) {
                StatsRange.Month -> monthOf(today) to monthOf(today)
                StatsRange.Year -> today.year * 100L + 1 to today.year * 100L + 12
                StatsRange.All -> 0L to 999_912L
            }
            val shows = lq.showTotals(fromMonth, toMonth).executeAsList()
            val titles = shows.associate { it.show to (it.title ?: "") }
            val heard = shows.sumOf { it.heard ?: 0 }
            val media = shows.sumOf { it.media ?: 0 }
            val skipped = shows.sumOf { it.skipped ?: 0 }
            val bars = when (range) {
                StatsRange.Month -> {
                    val first = LocalDate(today.year, today.month, 1)
                    val byDay = lq.days(first.toEpochDays(), today.toEpochDays()).executeAsList().associate { it.day to (it.heard ?: 0) }
                    (1..today.day).map { d -> StatsBar(d.toString(), byDay[LocalDate(today.year, today.month, d).toEpochDays()] ?: 0) }
                }
                StatsRange.Year -> {
                    val byMonth = lq.months(fromMonth, toMonth).executeAsList().associate { it.month to (it.heard ?: 0) }
                    (1..today.month.number).map { m -> StatsBar(m.toString(), byMonth[today.year * 100L + m] ?: 0) }
                }
                StatsRange.All -> {
                    val byYear = lq.months(0, 999_912).executeAsList().groupBy { it.month / 100 }.mapValues { (_, l) -> l.sumOf { it.heard ?: 0 } }
                    (byYear.keys.minOrNull() ?: today.year.toLong()).rangeTo(today.year.toLong()).map { y -> StatsBar(y.toString(), byYear[y] ?: 0) }
                }
            }
            val streakFrom = when (range) {
                StatsRange.Month -> LocalDate(today.year, today.month, 1).toEpochDays()
                StatsRange.Year -> LocalDate(today.year, 1, 1).toEpochDays()
                StatsRange.All -> 0L
            }
            val days = lq.days(streakFrom, today.toEpochDays()).executeAsList().filter { (it.heard ?: 0) >= STREAK_MIN_MS }.map { it.day }
            var longest = 0; var run = 0; var prev = Long.MIN_VALUE
            for (d in days) { run = if (d == prev + 1) run + 1 else 1; longest = maxOf(longest, run); prev = d }
            val counts = lq.counts(fromMonth, toMonth).executeAsOne()
            val first = lq.firstDay().executeAsOneOrNull()?.first?.let { LocalDate.fromEpochDays(it) }
            val estimate = if (range == StatsRange.All) {
                val start = (first ?: today).atStartOfDayIn(zone()).epochSeconds
                db.episodeQueries.heardBefore(start).executeAsOne() * 1000
            } else null
            ListeningStats(
                range = range, heardMs = heard, savedBySpeedMs = (media - heard).coerceAtLeast(0), savedBySkipsMs = skipped,
                episodes = lq.episodeCount(fromMonth, toMonth).executeAsOne().toInt(),
                bars = bars,
                topShows = shows.filter { (it.heard ?: 0) > 0 || it.given > 0 }
                    .map { ShowStats(it.show, it.title ?: "", it.heard ?: 0, it.given) }.sortedByDescending { it.heardMs },
                topEpisodes = lq.episodeTotals(fromMonth, toMonth, TOP.toLong()).executeAsList()
                    .map { EpisodeStats(it.title ?: "", titles[it.show] ?: "", it.heard ?: 0) },
                boostMsat = shows.sumOf { it.boost ?: 0 }, streamMsat = shows.sumOf { it.stream ?: 0 }, zapMsat = shows.sumOf { it.zap ?: 0 },
                showsHeard = shows.count { (it.heard ?: 0) > 0 },
                showsSupported = shows.count { (it.heard ?: 0) > 0 && it.given > 0 },
                longestStreakDays = longest,
                chapters = counts.chapters, transcripts = counts.transcripts, comments = counts.comments,
                since = first, estimatedBeforeMs = estimate?.takeIf { it > 0 },
            )
        }
    }

    private val space.sovereigncast.hopp.db.ShowTotals.given: Long get() = (boost ?: 0) + (stream ?: 0) + (zap ?: 0)

    @OptIn(ExperimentalTime::class)
    private fun dateOf(epochSeconds: Long): LocalDate = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone()).date
    private fun today(): Long = dateOf(clock.now()).toEpochDays()
    /** The day [epochMs] falls on where the listener is — the days of the statistics are local days. */
    fun dayAt(epochMs: Long): Long = dateOf(epochMs / 1000).toEpochDays()

    companion object {
        private const val DEVICE_KEY = "stats_device"
        /** Into the database every half minute of listening, and when it pauses. */
        const val FLUSH_EVERY_MS = 30_000L
        /** To sync at most this often while listening… */
        const val NOTE_EVERY_MS = 30 * 60_000L
        /** …and when it pauses, unless it did a moment ago. */
        const val NOTE_PAUSED_MS = 5 * 60_000L
        /** A day counts for the streak from five minutes on. */
        const val STREAK_MIN_MS = 5 * 60_000L
        const val TOP = 10
        /** Titles kept short: the sync document carries them. */
        const val TITLE = 80

        fun monthOf(date: LocalDate): Long = date.year * 100L + date.month.number
    }
}
