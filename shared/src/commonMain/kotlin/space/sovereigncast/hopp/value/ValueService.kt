package space.sovereigncast.hopp.value

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import space.sovereigncast.hopp.data.Clock
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.directory.DirectoryRepository
import space.sovereigncast.hopp.feed.FeedParser
import space.sovereigncast.hopp.model.RemoteItem
import space.sovereigncast.hopp.model.ValueBlock
import space.sovereigncast.hopp.model.ValueRecipient
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.net.FetchResult
import space.sovereigncast.hopp.platform.AppInfo
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One recipient's line in the payment log. */
@Serializable
data class PaymentLine(
    val name: String? = null,
    val address: String,
    val msat: Long,
    /** paid | skipped | failed */
    val status: String,
    val route: String? = null,
    val reason: String? = null,
)

/** A payment as the history lists it. */
data class Payment(
    val id: Long,
    val createdAt: Long,
    val kind: String,
    val podcastTitle: String?,
    val episodeTitle: String?,
    val amountMsat: Long,
    val message: String?,
    val lines: List<PaymentLine>,
)

/** A share as the boost dialog shows it, with why it cannot be paid (null = it can). */
data class PlannedShare(val recipient: ValueRecipient, val amountMsat: Long, val unpayable: String?)

sealed interface BoostPlan {
    /** The episode and its show carry no `podcast:value`. */
    data object NoValue : BoostPlan
    data class Ready(val shares: List<PlannedShare>, val positionSeconds: Long?) : BoostPlan
}

/** A streaming catch-up after time offline: paid within the budget (ADR 0014). */
data class StreamNotice(val paidSats: Long, val episodes: Int)

/**
 * The value layer of phase 1 (ADR 0005, 0014): boosts with a message, and
 * streaming sats — accrued on media time whatever the network does, settled
 * per recipient over NWC once a share is a whole sat, within the daily budget.
 */
class ValueService(
    private val db: HoppDatabase,
    private val wallet: WalletRepository,
    private val episodes: EpisodeRepository,
    private val podcasts: PodcastRepository,
    private val settings: SettingsRepository,
    private val lnAddress: LnAddressClient,
    private val fetcher: FeedFetcher,
    private val directory: DirectoryRepository?,
    private val clock: Clock,
    private val app: AppInfo,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lineList = ListSerializer(PaymentLine.serializer())
    private val pq = db.paymentQueries
    private val sq = db.streamingQueries
    private val settling = Mutex()
    private val blocks = mutableMapOf<Long, ValueBlock?>()
    private val remotes = mutableMapOf<String, ValueBlock?>()

    private val _notices = MutableSharedFlow<StreamNotice>(extraBufferCapacity = 4)
    val notices: SharedFlow<StreamNotice> = _notices.asSharedFlow()
    private val _overBudget = MutableStateFlow(false)
    /** Streaming owes more than today's budget allows — shown, not paid. */
    val overBudget: StateFlow<Boolean> = _overBudget.asStateFlow()

    @OptIn(ExperimentalTime::class)
    fun startOfToday(): Long {
        val tz = TimeZone.currentSystemDefault()
        return Instant.fromEpochSeconds(clock.now()).toLocalDateTime(tz).date.atStartOfDayIn(tz).epochSeconds
    }

    val spentTodayMsat: Flow<Long> get() = pq.spentSince(startOfToday()).asFlow().mapToOne(Dispatchers.IO)
    val streamedTodayMsat: Flow<Long> get() = pq.spentSinceOfKind(startOfToday(), KIND_STREAM).asFlow().mapToOne(Dispatchers.IO)

    /** What is left of today's budget, in msat. */
    val budgetLeftMsat: Flow<Long> get() = combine(settings.settings, spentTodayMsat) { s, spent -> (s.dailyBudgetSats * 1000L - spent).coerceAtLeast(0) }

    val payments: Flow<List<Payment>> = pq.recent().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { r ->
            Payment(r.id, r.created_at, r.kind, r.podcast_title, r.episode_title, r.amount_msat, r.message,
                runCatching { json.decodeFromString(lineList, r.results_json) }.getOrDefault(emptyList()))
        }
    }

    // ── value blocks ──────────────────────────────────────────────────────

    /** The episode's own block, else the show's (podcast namespace). */
    suspend fun blockFor(item: EpisodeItem): ValueBlock? {
        if (item.id in blocks) return blocks[item.id]
        val block = episodes.details(item.id)?.value ?: podcasts.podcastMeta(item.podcastId)?.value
        blocks[item.id] = block
        return block
    }

    /** Another feed's block, for a time split pointing there: the item's own,
     *  else that feed's. Found by the feed address, or the GUID via the index. */
    suspend fun remoteBlock(item: RemoteItem): ValueBlock? {
        val key = item.feedGuid + "/" + item.itemGuid
        if (key in remotes) return remotes[key]
        val block = runCatching {
            val url = item.feedUrl ?: directory?.feedUrlForGuid(item.feedGuid) ?: return@runCatching null
            val fetched = fetcher.fetch(url) as? FetchResult.Fetched ?: return@runCatching null
            val feed = withContext(Dispatchers.Default) { FeedParser.parse(fetched.bytes) }
            item.itemGuid?.let { guid -> feed.episodes.firstOrNull { it.guid == guid }?.value } ?: feed.podcast.value
        }.getOrNull()
        remotes[key] = block
        return block
    }

    private suspend fun shares(block: ValueBlock, totalMsat: Long, positionSeconds: Double?): List<Share> {
        val remote = Splits.activeSplit(block, positionSeconds)?.takeIf { it.recipients.isEmpty() }?.remoteItem?.let { remoteBlock(it) }
        return Splits.divideAt(block, totalMsat, positionSeconds, remote)
    }

    /** The show's suggested rate, in sats per minute (`suggested` is BTC). */
    fun suggestedSatsPerMinute(block: ValueBlock?): Int? =
        block?.suggested?.toDoubleOrNull()?.let { (it * 100_000_000).toInt() }?.takeIf { it > 0 }

    // ── boosts ────────────────────────────────────────────────────────────

    suspend fun plan(item: EpisodeItem, positionSeconds: Long?, amountMsat: Long): BoostPlan {
        val block = blockFor(item) ?: return BoostPlan.NoValue
        val info = wallet.wallet.value?.info
        val planned = shares(block, amountMsat, positionSeconds?.toDouble()).map { s ->
            PlannedShare(s.recipient, s.amountMsat, info?.let { unpayableReason(s.recipient, it) } ?: if (s.recipient.type == Splits.REMOTE) "remote" else null)
        }
        return BoostPlan.Ready(planned, positionSeconds)
    }

    /** Sends a boost and logs it; returns each recipient's outcome. */
    suspend fun boost(item: EpisodeItem, positionSeconds: Long?, amountMsat: Long, message: String?): List<ShareResult> {
        val client = wallet.client() ?: throw IllegalStateException("no wallet connected")
        wallet.ensureNodeInfo()
        val info = wallet.wallet.value?.info ?: client.info()
        val block = blockFor(item) ?: throw IllegalStateException("no value block")
        val ctx = context(item, "boost", positionSeconds, message)
        val remote = Splits.activeSplit(block, positionSeconds?.toDouble())?.takeIf { it.recipients.isEmpty() }?.remoteItem?.let { remoteBlock(it) }
        val results = BoostSender(client, lnAddress, info).send(block, amountMsat, ctx, remote)
        log(KIND_BOOST, item, results, message)
        return results
    }

    private suspend fun context(item: EpisodeItem, action: String, positionSeconds: Long?, message: String?): BoostContext {
        val show = podcasts.show(item.podcastId).first()
        return BoostContext(
            action = action, appVersion = app.version, podcast = show?.title ?: item.podcastTitle, episode = item.title,
            feedUrl = show?.feedUrl, podcastGuid = show?.podcastGuid, episodeGuid = item.guid, positionSeconds = positionSeconds,
            senderName = settings.current().senderName.takeIf { it.isNotBlank() }, message = message?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun log(kind: String, item: EpisodeItem, results: List<ShareResult>, message: String?) = withContext(Dispatchers.IO) {
        val lines = results.map { r ->
            val rec = r.share.recipient
            when (r) {
                is ShareResult.Paid -> PaymentLine(rec.name, rec.address, r.share.amountMsat, "paid", r.route)
                is ShareResult.Skipped -> PaymentLine(rec.name, rec.address, r.share.amountMsat, "skipped", reason = r.reason)
                is ShareResult.Failed -> PaymentLine(rec.name, rec.address, r.share.amountMsat, "failed", r.route, r.reason)
            }
        }
        val paid = results.filterIsInstance<ShareResult.Paid>().sumOf { it.share.amountMsat }
        val show = podcasts.show(item.podcastId).first()
        pq.insert(clock.now(), kind, item.podcastId, item.id, show?.title ?: item.podcastTitle, item.title, paid, message,
            json.encodeToString(lineList, lines))
    }

    // ── streaming ─────────────────────────────────────────────────────────

    /**
     * [mediaMs] of [item] were heard, ending at [positionMs] — media time, so
     * 2× speed accrues twice as much and a seek nothing (the caller leaves
     * jumps out). Works offline; nothing is paid here.
     */
    suspend fun accrue(item: EpisodeItem, positionMs: Long, mediaMs: Long) {
        if (mediaMs <= 0) return
        val s = settings.current()
        if (!s.streaming || wallet.wallet.value == null) return
        val block = blockFor(item) ?: return
        val rate = s.streamRateSats.takeIf { it > 0 } ?: suggestedSatsPerMinute(block) ?: DEFAULT_RATE
        val msat = mediaMs * rate / 60  // rate × 1000 msat per 60 000 ms
        val position = positionMs / 1000.0
        val bucket = Splits.activeSplit(block, position)?.let { "ts:${it.startTime}" } ?: ""
        withContext(Dispatchers.IO) {
            db.transaction {
                sq.ensureAccrual(item.id, bucket, item.podcastId, position)
                sq.addAccrual(mediaMs / 1000.0, msat, position, item.id, bucket)
            }
        }
    }

    /** What is accrued or owed but not yet paid, in msat. */
    val pendingMsat: Flow<Long> get() = combine(sq.accruedTotal().asFlow().mapToOne(Dispatchers.IO), sq.owedTotal().asFlow().mapToOne(Dispatchers.IO)) { a, b -> a + b }

    /**
     * Hands accruals to settlement (split per recipient, the time split at the
     * position heard), then pays every share that is a whole sat — oldest debt
     * first, as long as today's budget lasts. Failed payments stay owed for the
     * next run; a share no wallet could pay is dropped and logged as skipped.
     */
    suspend fun settle() = settling.withLock {
        distribute()
        val client = wallet.client() ?: return@withLock
        wallet.ensureNodeInfo()
        val info = wallet.wallet.value?.info ?: return@withLock
        val budget = settings.current().dailyBudgetSats * 1000L
        var left = budget - withContext(Dispatchers.IO) { pq.spentSince(startOfToday()).executeAsOne() }
        var over = false
        val sender = BoostSender(client, lnAddress, info)
        val rows = withContext(Dispatchers.IO) { sq.payable(RAIL_LIGHTNING, 1000).executeAsList() }
        var caughtUp = 0L
        val caughtUpEpisodes = mutableSetOf<Long>()
        for ((episodeId, group) in rows.groupBy { it.episode_id }) {
            val item = episodes.item(episodeId)
            val results = mutableListOf<ShareResult>()
            for (row in group) {
                val amount = row.owed_msat / 1000 * 1000
                if (amount > left) { over = true; continue }
                val recipient = json.decodeFromString(ValueRecipient.serializer(), row.recipient_json)
                val share = Share(recipient, amount)
                val ctx = item?.let { context(it, "stream", null, null) } ?: BoostContext("stream", app.version, null, null, null, null, null, null, null, null)
                val result = sender.payShare(share, amount, ctx)
                results += result
                withContext(Dispatchers.IO) {
                    when (result) {
                        is ShareResult.Paid -> {
                            sq.paid(amount, episodeId, row.recipient_key, RAIL_LIGHTNING); left -= amount
                            if (row.attempts > 0) { caughtUp += amount; caughtUpEpisodes += episodeId }
                        }
                        is ShareResult.Skipped -> sq.paid(row.owed_msat, episodeId, row.recipient_key, RAIL_LIGHTNING)
                        is ShareResult.Failed -> sq.failed(result.reason, episodeId, row.recipient_key, RAIL_LIGHTNING)
                    }
                }
            }
            if (item != null && results.any { it !is ShareResult.Failed }) log(KIND_STREAM, item, results.filterNot { it is ShareResult.Failed }, null)
        }
        _overBudget.value = over
        if (caughtUp > 0) _notices.tryEmit(StreamNotice(caughtUp / 1000, caughtUpEpisodes.size))
    }

    private suspend fun distribute() {
        val pending = withContext(Dispatchers.IO) { sq.pendingAccruals().executeAsList() }
        for (a in pending) {
            val item = episodes.item(a.episode_id)
            val block = item?.let { blockFor(it) }
            val parts = if (block != null) shares(block, a.accrued_msat, a.position_seconds) else emptyList()
            withContext(Dispatchers.IO) {
                db.transaction {
                    for (p in parts) {
                        val key = "${p.recipient.type}:${p.recipient.address}"
                        val recipient = json.encodeToString(ValueRecipient.serializer(), p.recipient)
                        sq.ensureSettlement(a.episode_id, key, RAIL_LIGHTNING, a.podcast_id, recipient)
                        sq.owe(p.amountMsat, recipient, a.episode_id, key, RAIL_LIGHTNING)
                    }
                    // The episode is gone or lost its block: nothing to pay it to.
                    sq.takeAccrual(a.accrued_msat, a.episode_id, a.bucket)
                }
            }
        }
    }

    companion object {
        const val KIND_BOOST = "boost"
        const val KIND_STREAM = "stream"
        const val RAIL_LIGHTNING = "lightning"
        /** When neither the listener nor the show sets a rate. */
        const val DEFAULT_RATE = 10
    }
}
