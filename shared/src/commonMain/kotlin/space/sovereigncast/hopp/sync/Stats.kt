package space.sovereigncast.hopp.sync

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import space.sovereigncast.hopp.data.ListeningRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.db.Listen_count
import space.sovereigncast.hopp.db.Listen_day
import space.sovereigncast.hopp.db.Listen_episode
import space.sovereigncast.hopp.db.Listen_show
import space.sovereigncast.hopp.platform.ioDispatcher
import space.sovereigncast.hopp.sync.SyncDocs.long
import space.sovereigncast.hopp.sync.SyncDocs.text

/**
 * The listening statistics as sync carries them (hopp/stats): per device its own counts —
 * a day's time, a month's time and sats per show, an episode's time, a month's counters — so
 * each device writes only keys with its own name and the counts add up across devices without
 * ever being merged over each other. Times in seconds, sats in msat; empty fields left out.
 */
object StatsEntries {
    fun dayKey(device: String, day: Long) = "d\n$device\n$day"
    fun showKey(device: String, month: Int, show: String) = "m\n$device\n$month\n$show"
    fun episodeKey(device: String, show: String, guid: String) = "e\n$device\n$show\n$guid"
    fun countKey(device: String, month: Int) = "c\n$device\n$month"

    internal fun of(device: String, days: List<Listen_day>, shows: List<Listen_show>, episodes: List<Listen_episode>, counts: List<Listen_count>): Map<String, JsonObject> =
        buildMap {
            days.forEach { put(dayKey(device, it.day), obj("h" to it.heard_ms / 1000, "m" to it.media_ms / 1000)) }
            shows.forEach {
                put(showKey(device, it.month.toInt(), it.show), obj(
                    "h" to it.heard_ms / 1000, "m" to it.media_ms / 1000, "k" to it.skipped_ms / 1000,
                    "b" to it.boost_msat, "r" to it.stream_msat, "z" to it.zap_msat, title = it.title,
                ))
            }
            episodes.forEach { put(episodeKey(device, it.show, it.episode), obj("h" to it.heard_ms / 1000, "m" to it.media_ms / 1000, "y" to it.last_month, title = it.title)) }
            counts.forEach { put(countKey(device, it.month.toInt()), obj("ch" to it.chapters, "tr" to it.transcripts, "co" to it.comments)) }
        }

    private fun obj(vararg fields: Pair<String, Long>, title: String = ""): JsonObject = buildJsonObject {
        if (title.isNotEmpty()) put("n", title)
        for ((k, v) in fields) if (v != 0L) put(k, v)
    }

    /** Whose key [key] is: the device's name in it. */
    fun deviceOf(key: String): String? = key.split('\n').getOrNull(1)
}

/**
 * The other devices' listening, kept here for the statistics page; this device's own goes out as
 * [ListeningRepository] notes it. What another device sends is taken only in the shape and the
 * bounds a Hopp writes — sync is encrypted to the listener's own key, and still a value from
 * elsewhere is never trusted blindly (as with the shows' speeds, audit 2026-10-22, M-04).
 */
class StatsDocument(
    private val db: HoppDatabase,
    private val listening: ListeningRepository,
    private val nowMs: () -> Long,
) : SyncedDocument {
    override val name = SyncDocs.STATS

    override suspend fun snapshot(): Map<String, SyncEntry> = listening.snapshot().mapValues { (_, d) -> SyncEntry(nowMs(), d = d) }

    override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> = withContext(ioDispatcher) {
        val q = db.listeningQueries
        val mine = listening.device
        // This device's own counts come back only where it has none (a reinstall that kept its name).
        val ownEmpty = q.daysOf(mine).executeAsList().isEmpty() && q.showsOf(mine).executeAsList().isEmpty()
        db.transaction {
            for ((key, e) in entries) {
                if (e.del) continue
                val parts = key.split('\n')
                val device = parts.getOrNull(1)?.takeIf { DEVICE.matches(it) } ?: continue
                if (device == mine && !ownEmpty) continue
                val d = e.d
                fun ms(field: String, max: Long) = d.long(field)?.takeIf { it in 0..max }?.times(1000)
                fun msat(field: String) = d.long(field)?.takeIf { it in 0..MAX_MSAT } ?: 0
                val title = d.text("n")?.take(ListeningRepository.TITLE) ?: ""
                when (parts[0]) {
                    "d" -> {
                        val day = parts.getOrNull(2)?.toLongOrNull()?.takeIf { it in 0..MAX_DAY } ?: continue
                        q.putDay(device, day, ms("h", DAY_S) ?: continue, ms("m", DAY_S * MAX_SPEED) ?: 0)
                    }
                    "m" -> {
                        val month = parts.getOrNull(2)?.toIntOrNull()?.takeIf(::isMonth) ?: continue
                        val show = parts.getOrNull(3)?.takeIf { it.isNotEmpty() && it.length <= MAX_KEY } ?: continue
                        q.putShow(device, month.toLong(), show, title, ms("h", MONTH_S) ?: 0, ms("m", MONTH_S * MAX_SPEED) ?: 0, ms("k", MONTH_S) ?: 0,
                            msat("b"), msat("r"), msat("z"))
                    }
                    "e" -> {
                        val show = parts.getOrNull(2)?.takeIf { it.isNotEmpty() && it.length <= MAX_KEY } ?: continue
                        val guid = parts.getOrNull(3)?.takeIf { it.isNotEmpty() && it.length <= MAX_KEY } ?: continue
                        val last = d.long("y")?.toInt()?.takeIf(::isMonth) ?: continue
                        q.putEpisode(device, show, guid, title, ms("h", EPISODE_S) ?: 0, ms("m", EPISODE_S * MAX_SPEED) ?: 0, last.toLong())
                    }
                    "c" -> {
                        val month = parts.getOrNull(2)?.toIntOrNull()?.takeIf(::isMonth) ?: continue
                        fun n(field: String) = d.long(field)?.takeIf { it in 0..MAX_COUNT } ?: 0
                        q.putCount(device, month.toLong(), n("ch"), n("tr"), n("co"))
                    }
                }
            }
        }
        entries.keys
    }

    /** Days kept 400 days, episodes a year after they were last heard: the months stay. Counted
     *  in local days, as the days are noted (Zetti's beta test 2026-10-27: UTC moved the edge). */
    override fun expired(key: String, entry: SyncEntry, nowMs: Long): Boolean =
        StatsRetention.expired(key, entry.d, listening.dayAt(nowMs))

    private fun isMonth(m: Int) = m / 100 in 2000..2999 && m % 100 in 1..12

    private companion object {
        val DEVICE = Regex("[0-9a-f]{4,32}")
        const val DAY_S = 26 * 3600L
        const val MONTH_S = 32 * 26 * 3600L
        const val EPISODE_S = 400 * 24 * 3600L
        const val MAX_SPEED = 5
        const val MAX_DAY = 200_000L
        const val MAX_KEY = 600
        const val MAX_COUNT = 10_000_000L
        /** 10 BTC: far beyond any listener's month for one show. */
        const val MAX_MSAT = 1_000_000_000_000_000L
    }
}

/** How long the synced statistics keep a day and an episode — for the document and for what is noted. */
internal object StatsRetention {
    const val DAYS_KEPT = 400
    const val EPISODES_KEPT_DAYS = 365L

    /** [today] as an epoch day, local. */
    fun expired(key: String, d: JsonObject, today: Long): Boolean = when {
        key.startsWith("d\n") -> key.split('\n').getOrNull(2)?.toLongOrNull()?.let { it < today - DAYS_KEPT } ?: false
        key.startsWith("e\n") -> d.long("y")?.let { it < monthOfDay(today - EPISODES_KEPT_DAYS) } ?: false
        else -> false
    }

    private fun monthOfDay(epochDay: Long): Long =
        kotlinx.datetime.LocalDate.fromEpochDays(epochDay).let { (it.year * 100 + it.month.ordinal + 1).toLong() }
}
