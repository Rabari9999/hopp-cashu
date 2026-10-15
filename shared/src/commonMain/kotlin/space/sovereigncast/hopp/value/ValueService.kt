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
import space.sovereigncast.hopp.data.Settings
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
    /** Routing fees the wallet paid on top, if it said. */
    val feesMsat: Long? = null,
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

/** What a quick boost from the lock screen did. */
sealed interface QuickBoost {
    data class Sent(val paidSats: Long, val amountSats: Long, val recipientsPaid: Int, val recipients: Int, val show: String) : QuickBoost
    data object Off : QuickBoost
    data object NoWallet : QuickBoost
    data object NoValue : QuickBoost
    data object OverBudget : QuickBoost
    data class Failed(val reason: String) : QuickBoost
}

/** Why streamed sats of an episode have not gone out yet. */
enum class PendingReason { Failed, OverBudget, NextRound, PartsOfASat }

/** Streamed sats of one episode not yet sent. */
data class PendingEpisode(val episodeId: Long, val item: EpisodeItem?, val podcastTitle: String?, val msat: Long, val reason: PendingReason)

/** Streamed sats waiting for one recipient: owed, or heard and not yet split. */
data class PendingShare(val name: String, val msat: Long)

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
    /** Boost metadata at a Sovereigncast instance, for Lightning-address shares. */
    private val metadata: space.sovereigncast.hopp.sovereigncast.PaymentMetadataSource? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lineList = ListSerializer(PaymentLine.serializer())
    private val pq = db.paymentQueries
    private val sq = db.streamingQueries
    /**
     * One payment run at a time — boost, quick boost, streaming batch — so a
     * budget check and the payment it allows are one step (security report
     * 2026-10-10, H-03: parallel quick boosts all saw the same budget left and
     * all paid). Payments are logged before the lock is released.
     */
    private val spending = Mutex()
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

    /** Home's "Recently boosted": episode id → sats boosted to it in all. */
    fun recentBoosts(limit: Long): Flow<List<Pair<Long, Long>>> =
        pq.recentBoosts(limit).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.mapNotNull { r -> r.episode_id?.let { it to (r.total_msat ?: 0L) / 1000 } } }

    // ── value blocks ──────────────────────────────────────────────────────

    /** The episode's own block, else the show's (podcast namespace) — with
     *  Hopp's share added when the listener chose to support it. */
    suspend fun blockFor(item: EpisodeItem): ValueBlock? {
        val block = if (item.id in blocks) blocks[item.id] else
            (episodes.details(item.id)?.value ?: podcasts.podcastMeta(item.podcastId)?.value).also { blocks[item.id] = it }
        return block?.let { withSupport(it) }
    }

    /**
     * "Support Hopp" (ADR 0005): opt-in, visible, off by default. A fee-style
     * share — its percent comes off the top — so it shows in the split like
     * any recipient and never adds to what the listener chose to give.
     */
    private suspend fun withSupport(block: ValueBlock): ValueBlock {
        val percent = settings.current().supportHoppPercent
        if (percent <= 0) return block
        return block.copy(recipients = block.recipients + ValueRecipient(SUPPORT_NAME, "lnaddress", Settings.SUPPORT_ADDRESS, percent, fee = true))
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
        return Splits.divideAtWholeSats(block, totalMsat, positionSeconds, remote)
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

    /** A boost to the show as a whole (device feedback 2026-10-08): its own
     *  value block, no episode, no time splits. */
    suspend fun planShow(podcastId: Long, amountMsat: Long): BoostPlan {
        val block = podcasts.podcastMeta(podcastId)?.value?.let { withSupport(it) } ?: return BoostPlan.NoValue
        val info = wallet.wallet.value?.info
        val planned = Splits.divideWholeSats(block.recipients, amountMsat).map { s ->
            PlannedShare(s.recipient, s.amountMsat, info?.let { unpayableReason(s.recipient, it) })
        }
        return BoostPlan.Ready(planned, null)
    }

    suspend fun boostShow(podcastId: Long, amountMsat: Long, message: String?): List<ShareResult> = spending.withLock {
        val client = wallet.client() ?: throw IllegalStateException("no wallet connected")
        val info = wallet.wallet.value?.info ?: client.info()
        val block = podcasts.podcastMeta(podcastId)?.value?.let { withSupport(it) } ?: throw IllegalStateException("no value block")
        val show = podcasts.show(podcastId).first()
        val ctx = BoostContext(
            action = "boost", appVersion = app.version, podcast = show?.title, episode = null, feedUrl = show?.feedUrl,
            podcastGuid = show?.podcastGuid, episodeGuid = null, positionSeconds = null,
            senderName = settings.current().senderName.takeIf { it.isNotBlank() }, message = message?.takeIf { it.isNotBlank() },
        )
        val results = BoostSender(client, lnAddress, info, metadata?.forShow(podcastId, app.version)).send(block, amountMsat, ctx)
        record(KIND_BOOST, podcastId, null, show?.title, null, results, message)
        results
    }

    /** A payment made outside the value split — an instance's invoice for
     *  Release Boosting or Boost2Unlock — kept in the same history. */
    suspend fun logInvoicePayment(
        kind: String, podcastId: Long, podcastTitle: String?, episodeTitle: String?, amountMsat: Long,
        message: String?, recipientName: String?, recipientAddress: String, feesMsat: Long?,
    ) = withContext(Dispatchers.IO) {
        val line = PaymentLine(recipientName, recipientAddress, amountMsat, "paid", feesMsat = feesMsat, route = "invoice")
        pq.insert(clock.now(), kind, podcastId, null, podcastTitle, episodeTitle, amountMsat, message, feesMsat ?: 0, json.encodeToString(lineList, listOf(line)))
    }

    /** Sends a boost and logs it; returns each recipient's outcome. */
    suspend fun boost(item: EpisodeItem, positionSeconds: Long?, amountMsat: Long, message: String?): List<ShareResult> =
        spending.withLock { boostLocked(item, positionSeconds, amountMsat, message) }

    private suspend fun boostLocked(item: EpisodeItem, positionSeconds: Long?, amountMsat: Long, message: String?): List<ShareResult> {
        val client = wallet.client() ?: throw IllegalStateException("no wallet connected")
        val info = wallet.wallet.value?.info ?: client.info()
        val block = blockFor(item) ?: throw IllegalStateException("no value block")
        val ctx = context(item, "boost", positionSeconds, message)
        val remote = Splits.activeSplit(block, positionSeconds?.toDouble())?.takeIf { it.recipients.isEmpty() }?.remoteItem?.let { remoteBlock(it) }
        val results = BoostSender(client, lnAddress, info, metadata?.forShow(item.podcastId, app.version)).send(block, amountMsat, ctx, remote)
        log(KIND_BOOST, item, results, message)
        return results
    }

    /**
     * The boost button outside the app — lock screen, notification (device
     * feedback 2026-10-08): the configured amount at once, no dialog, no
     * message; never past today's budget.
     */
    suspend fun quickBoost(item: EpisodeItem, positionSeconds: Long?): QuickBoost = spending.withLock {
        val s = settings.current()
        if (s.quickBoostSats <= 0) return@withLock QuickBoost.Off
        if (wallet.wallet.value == null) return@withLock QuickBoost.NoWallet
        val amount = s.quickBoostSats * 1000L
        if (amount > budgetLeftMsat.first()) return@withLock QuickBoost.OverBudget
        if (blockFor(item) == null) return@withLock QuickBoost.NoValue
        try {
            outcome(item.podcastId, s.quickBoostSats.toLong(), boostLocked(item, positionSeconds, amount, null), item.podcastTitle)
        } catch (e: Exception) {
            QuickBoost.Failed(e.message ?: "error")
        }
    }

    /** What a boost came to, to report it (BoostReports). */
    suspend fun outcome(podcastId: Long, amountSats: Long, results: List<ShareResult>, fallbackTitle: String? = null): QuickBoost.Sent {
        val paid = results.filterIsInstance<ShareResult.Paid>()
        return QuickBoost.Sent(paid.sumOf { it.share.amountMsat } / 1000, amountSats, paid.size, results.size,
            podcasts.show(podcastId).first()?.title ?: fallbackTitle.orEmpty())
    }

    private suspend fun context(item: EpisodeItem, action: String, positionSeconds: Long?, message: String?): BoostContext {
        val show = podcasts.show(item.podcastId).first()
        return BoostContext(
            action = action, appVersion = app.version, podcast = show?.title ?: item.podcastTitle, episode = item.title,
            feedUrl = show?.feedUrl, podcastGuid = show?.podcastGuid, episodeGuid = item.guid, positionSeconds = positionSeconds,
            senderName = settings.current().senderName.takeIf { it.isNotBlank() }, message = message?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun log(kind: String, item: EpisodeItem, results: List<ShareResult>, message: String?) {
        val show = podcasts.show(item.podcastId).first()
        record(kind, item.podcastId, item.id, show?.title ?: item.podcastTitle, item.title, results, message)
    }

    private suspend fun record(
        kind: String, podcastId: Long, episodeId: Long?, podcastTitle: String?, episodeTitle: String?,
        results: List<ShareResult>, message: String?,
    ) = withContext(Dispatchers.IO) {
        val lines = results.map { r ->
            val rec = r.share.recipient
            when (r) {
                is ShareResult.Paid -> PaymentLine(rec.name, rec.address, r.share.amountMsat, "paid", feesMsat = r.feesMsat, route = r.route)
                is ShareResult.Skipped -> PaymentLine(rec.name, rec.address, r.share.amountMsat, "skipped", reason = r.reason)
                is ShareResult.Failed -> PaymentLine(rec.name, rec.address, r.share.amountMsat, if (r.outcomeUnknown) "unknown" else "failed", route = r.route, reason = r.reason)
            }
        }
        val paid = results.filterIsInstance<ShareResult.Paid>()
        pq.insert(clock.now(), kind, podcastId, episodeId, podcastTitle, episodeTitle, paid.sumOf { it.share.amountMsat }, message,
            paid.sumOf { it.feesMsat ?: 0 }, json.encodeToString(lineList, lines))
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
    suspend fun settle(roundUp: Set<Long> = emptySet()) = spending.withLock {
        distribute()
        val client = wallet.client() ?: return@withLock
        val info = wallet.wallet.value?.info ?: return@withLock
        val budget = settings.current().dailyBudgetSats * 1000L
        var left = budget - withContext(Dispatchers.IO) { pq.spentSince(startOfToday()).executeAsOne() }
        var over = false
        val sender = BoostSender(client, lnAddress, info)
        // Whole sats, and — once an episode is done (heard to its end, or not for
        // 24 h) — its parts of a sat rounded up, at most one sat more per
        // recipient (user decision 2026-10-11): otherwise they would wait for good.
        val stale = clock.now() - ROUND_UP_AFTER_SECONDS
        val rows = withContext(Dispatchers.IO) { sq.owedWithHeard(RAIL_LIGHTNING).executeAsList() }
            .map { r -> r to (r.episode_id in roundUp || (r.last_played_at ?: Long.MAX_VALUE) < stale) }
            .filter { (r, done) -> r.owed_msat >= 1000 || done }
        var caughtUp = 0L
        val caughtUpEpisodes = mutableSetOf<Long>()
        for ((episodeId, group) in rows.groupBy { it.first.episode_id }) {
            val item = episodes.item(episodeId)
            val results = mutableListOf<ShareResult>()
            for ((row, done) in group) {
                val amount = if (done) (row.owed_msat + 999) / 1000 * 1000 else row.owed_msat / 1000 * 1000
                if (amount > left) { over = true; continue }
                val recipient = json.decodeFromString(ValueRecipient.serializer(), row.recipient_json)
                val share = Share(recipient, amount)
                val ctx = item?.let { context(it, "stream", null, null) } ?: BoostContext("stream", app.version, null, null, null, null, null, null, null, null)
                val result = sender.payShare(share, amount, ctx)
                results += result
                withContext(Dispatchers.IO) {
                    when (result) {
                        is ShareResult.Paid -> {
                            // Rounded up: what was owed is settled, the extra is a gift.
                            sq.paid(minOf(amount, row.owed_msat), episodeId, row.recipient_key, RAIL_LIGHTNING); left -= amount + (result.feesMsat ?: 0)
                            if (row.attempts > 0) { caughtUp += amount; caughtUpEpisodes += episodeId }
                        }
                        is ShareResult.Skipped -> sq.paid(row.owed_msat, episodeId, row.recipient_key, RAIL_LIGHTNING)
                        // May have been paid: settled as it is, never tried again, and in the history as "unknown".
                        is ShareResult.Failed -> if (result.outcomeUnknown) sq.paid(row.owed_msat, episodeId, row.recipient_key, RAIL_LIGHTNING)
                            else sq.failed(result.reason, episodeId, row.recipient_key, RAIL_LIGHTNING)
                    }
                }
            }
            val logged = results.filterNot { it is ShareResult.Failed && !it.outcomeUnknown }
            if (item != null && logged.isNotEmpty()) log(KIND_STREAM, item, logged, null)
        }
        _overBudget.value = over
        if (caughtUp > 0) _notices.tryEmit(StreamNotice(caughtUp / 1000, caughtUpEpisodes.size))
    }

    /** An accrual split to the msat among its recipients — settlement pays whole sats and keeps the rest owed. */
    private suspend fun parts(a: space.sovereigncast.hopp.db.Accrual): List<Share> {
        val block = episodes.item(a.episode_id)?.let { blockFor(it) } ?: return emptyList()
        val remote = Splits.activeSplit(block, a.position_seconds)?.takeIf { it.recipients.isEmpty() }?.remoteItem?.let { remoteBlock(it) }
        return Splits.divideAt(block, a.accrued_msat, a.position_seconds, remote)
    }

    private var nextSettleAt: Long? = null
    /** The player says when it pays the next batch; null = none until listening resumes. */
    fun settleDueIn(seconds: Long?) { nextSettleAt = seconds?.let { clock.now() + it } }
    /** Seconds until the next batch is paid; null before the loop runs. */
    fun secondsToNextSettle(): Long? = nextSettleAt?.let { (it - clock.now()).coerceAtLeast(0) }

    /**
     * What is set aside for [episodeId] and not yet sent, per recipient — owed
     * and not yet split alike (device feedback 2026-10-11: the value bar
     * showed what was sent, not what waits, nor why).
     */
    suspend fun pendingFor(episodeId: Long): List<PendingShare> {
        val owed = withContext(Dispatchers.IO) { sq.owedOf(episodeId, RAIL_LIGHTNING).executeAsList() }
        val accrued = withContext(Dispatchers.IO) { sq.accrualsOf(episodeId).executeAsList() }
        val byKey = linkedMapOf<String, PendingShare>()
        fun add(key: String, name: String?, msat: Long) {
            val p = byKey[key]
            byKey[key] = PendingShare(p?.name ?: name ?: key.substringAfter(':').take(12), (p?.msat ?: 0) + msat)
        }
        for (o in owed) add(o.recipient_key, json.decodeFromString(ValueRecipient.serializer(), o.recipient_json).name, o.owed_msat)
        for (a in accrued) for (p in parts(a)) add(settlementKey(p.recipient), p.recipient.name, p.amountMsat)
        return byKey.values.filter { it.msat > 0 }.sortedByDescending { it.msat }
    }

    /**
     * Every episode with streamed sats not yet sent, most first, and why they
     * wait (device feedback 2026-10-12: "from other episodes" said how much,
     * not from which).
     */
    suspend fun pendingByEpisode(): List<PendingEpisode> {
        val owed = withContext(Dispatchers.IO) { sq.owedByEpisode(RAIL_LIGHTNING).executeAsList() }.associateBy { it.episode_id }
        val accrued = withContext(Dispatchers.IO) { sq.accruedByEpisode().executeAsList() }.associate { it.episode_id to it.accrued }
        return (owed.keys + accrued.keys).map { id ->
            val o = owed[id]
            val reason = when {
                (o?.attempts ?: 0) > 0 -> PendingReason.Failed
                _overBudget.value && (o?.largest ?: 0) >= 1000 -> PendingReason.OverBudget
                (accrued[id] ?: 0) > 0 || (o?.largest ?: 0) >= 1000 -> PendingReason.NextRound
                else -> PendingReason.PartsOfASat
            }
            val item = episodes.item(id)
            val show = item?.let { podcasts.show(it.podcastId).first()?.title ?: it.podcastTitle }
            PendingEpisode(id, item, show, (o?.owed ?: 0) + (accrued[id] ?: 0), reason)
        }.filter { it.msat > 0 }.sortedByDescending { it.msat }
    }

    /**
     * Who a settlement row pays: type and address — and the custom record,
     * which tells sub-wallets on one node apart (security review 2026-10-10,
     * M-05: two of them were merged into one row and paid to whichever custom
     * record was written last). Rows from before keep their old key and are
     * paid out to the recipient they stored.
     */
    private fun settlementKey(r: ValueRecipient): String =
        "${r.type}:${r.address}" + (if (r.customKey != null || r.customValue != null) "#${r.customKey.orEmpty()}=${r.customValue.orEmpty()}" else "")

    private suspend fun distribute() {
        val pending = withContext(Dispatchers.IO) { sq.pendingAccruals().executeAsList() }
        for (a in pending) {
            val parts = parts(a)
            withContext(Dispatchers.IO) {
                db.transaction {
                    for (p in parts) {
                        val key = settlementKey(p.recipient)
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
        const val KIND_RELEASE_BOOSTING = "release_boosting"
        const val KIND_BOOST2UNLOCK = "boost2unlock"
        /** A new topic suggestion or a boost for one (phase 2). */
        const val KIND_SUGGESTION = "suggestion"
        const val RAIL_LIGHTNING = "lightning"
        /** When neither the listener nor the show sets a rate. */
        const val DEFAULT_RATE = 10
        /** Not heard for this long, an episode's parts of a sat are rounded up and paid. */
        const val ROUND_UP_AFTER_SECONDS = 24 * 3600L
        const val SUPPORT_NAME = "Hopp"
    }
}
