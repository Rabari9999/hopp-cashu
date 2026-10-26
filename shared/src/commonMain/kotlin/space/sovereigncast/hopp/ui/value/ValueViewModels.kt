package space.sovereigncast.hopp.ui.value

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.value.BalanceAnswer
import space.sovereigncast.hopp.value.BoostPlan
import space.sovereigncast.hopp.value.ConnectedWallet
import space.sovereigncast.hopp.value.Payment
import space.sovereigncast.hopp.value.ShareResult
import space.sovereigncast.hopp.value.ValueService
import space.sovereigncast.hopp.value.WalletRepository

private fun <T> ViewModel.state(flow: Flow<T>, initial: T): StateFlow<T> =
    flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

/** A wallet connection being made: the string is checked against the wallet. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Working : ConnectState
    /** [code]: the wallet's NwcException code, if it was one — [WalletRepository.UNSUPPORTED_ENCRYPTION] has a text of its own. */
    data class Failed(val reason: String, val code: String? = null) : ConnectState
}

class WalletViewModel(
    private val wallet: WalletRepository,
    private val value: ValueService,
    private val settings: SettingsRepository,
) : ViewModel() {
    val connected: StateFlow<ConnectedWallet?> = wallet.wallet
    /** A stored connection the key store could not give up — shown, not replaced (security review 2026-10-20). */
    val unreadable: StateFlow<Boolean> = wallet.unreadable
    val current: StateFlow<Settings?> = state(settings.settings, null)
    val spentToday: StateFlow<Long> = state(value.spentTodayMsat, 0L)
    private val _balance = MutableStateFlow<Long?>(null)
    val balanceMsat: StateFlow<Long?> = _balance.asStateFlow()
    private val _silent = MutableStateFlow(false)
    /** The wallet gave no answer to the balance question — it would not answer a payment either. */
    val silent: StateFlow<Boolean> = _silent.asStateFlow()
    private val _reach = MutableStateFlow<ShowsReach?>(null)
    /** The subscribed shows this wallet does not reach in full; null until looked at. */
    val reach: StateFlow<ShowsReach?> = _reach.asStateFlow()
    private val _connect = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connect: StateFlow<ConnectState> = _connect.asStateFlow()

    init { refreshBalance(); refreshReach() }

    fun refreshBalance() {
        if (wallet.wallet.value == null) return
        viewModelScope.launch {
            val answer = wallet.balance()
            _balance.value = (answer as? BalanceAnswer.Known)?.msat
            _silent.value = answer == BalanceAnswer.Silent
        }
    }

    private fun refreshReach() {
        if (wallet.wallet.value == null) { _reach.value = null; return }
        viewModelScope.launch { _reach.value = runCatching { ShowsReach(value.showsNotReached(), value.showsWithValue()) }.getOrNull() }
    }

    fun connect(uri: String) {
        _connect.value = ConnectState.Working
        viewModelScope.launch {
            _connect.value = try {
                wallet.connect(uri); refreshBalance(); refreshReach(); ConnectState.Idle
            } catch (e: Exception) {
                ConnectState.Failed(e.message ?: e::class.simpleName ?: "error", (e as? space.sovereigncast.hopp.value.nwc.NwcException)?.code)
            }
        }
    }

    fun disconnect() {
        wallet.disconnect()
        _balance.value = null
        _silent.value = false
        _reach.value = null
        // Without a wallet nothing streams (ADR 0005).
        update { it.copy(streaming = false) }
    }

    fun update(change: (Settings) -> Settings) = viewModelScope.launch { settings.update(change) }
}

/** [shows]: those not reached in full, least first; [withValue]: how many have a split at all. */
data class ShowsReach(val shows: List<space.sovereigncast.hopp.value.ShowReach>, val withValue: Int)

class HistoryViewModel(value: ValueService) : ViewModel() {
    val payments: StateFlow<List<Payment>?> = state(value.payments, null)
}

data class StreamDetail(
    val episodeId: Long,
    val shares: List<space.sovereigncast.hopp.value.PendingShare>,
    val nextSettleSeconds: Long?,
    /** The other episodes with sats still to send. */
    val others: List<space.sovereigncast.hopp.value.PendingEpisode> = emptyList(),
)

sealed interface BoostState {
    data object Editing : BoostState
    data object Sending : BoostState
    /** [nostr]: the boost as a Nostr comment — null when not asked for. */
    data class Done(val results: List<ShareResult>, val nostr: NostrPost? = null) : BoostState
    enum class NostrPost { Posting, Posted, Failed }
    data class Failed(val reason: String) : BoostState
}

/** The value bar and the boost dialog of now playing (ADR 0019). */
class ValueBarViewModel(
    private val value: ValueService,
    wallet: WalletRepository,
    private val settings: SettingsRepository,
    private val reports: space.sovereigncast.hopp.value.BoostReports,
    identities: space.sovereigncast.hopp.identity.IdentityRepository? = null,
    private val comments: space.sovereigncast.hopp.comments.CommentsRepository? = null,
    private val writer: space.sovereigncast.hopp.comments.CommentWriter? = null,
) : ViewModel() {
    /** With an identity, a boost to an episode can also go out as a Nostr comment. */
    val identity: StateFlow<space.sovereigncast.hopp.identity.NostrIdentity?> = identities?.identity ?: MutableStateFlow(null)
    val connected: StateFlow<ConnectedWallet?> = wallet.wallet
    val current: StateFlow<Settings?> = state(settings.settings, null)
    val streamedToday: StateFlow<Long> = state(value.streamedTodayMsat, 0L)
    /** Heard, not yet sent — over all episodes, in msat. */
    val pending: StateFlow<Long> = state(value.pendingMsat, 0L)
    private val _detail = MutableStateFlow<StreamDetail?>(null)
    /** The streaming sheet: what waits for whom in this episode, and when it goes. */
    val detail: StateFlow<StreamDetail?> = _detail.asStateFlow()

    fun refreshDetail(episodeId: Long) = viewModelScope.launch {
        _detail.value = StreamDetail(episodeId, value.pendingFor(episodeId), value.secondsToNextSettle(), value.pendingByEpisode().filter { it.episodeId != episodeId })
    }

    /** Pays every whole sat now, instead of at the next batch — in the app's scope, like a boost. */
    fun settleNow(episodeId: Long) {
        reports.scope.launch { runCatching { value.settle() }; refreshDetail(episodeId) }
    }
    val budgetLeft: StateFlow<Long> = state(value.budgetLeftMsat, 0L)
    val overBudget: StateFlow<Boolean> = value.overBudget
    /** The last streaming batch found the wallet not answering. */
    val streamSilent: StateFlow<Boolean> = value.walletSilent
    private val _walletBalance = MutableStateFlow<Long?>(null)
    /** What the wallet holds, in msat, for the boost sheet — null while it is asked, or when it does not tell. */
    val walletBalance: StateFlow<Long?> = _walletBalance.asStateFlow()
    private val _walletSilent = MutableStateFlow(false)
    /** The wallet did not answer the boost sheet's balance question: a boost would go unanswered. */
    val walletSilent: StateFlow<Boolean> = _walletSilent.asStateFlow()
    private var balanceAsked: kotlinx.coroutines.Deferred<BalanceAnswer>? = null

    /** Asks the wallet what it holds — when the boost sheet opens and after a boost (maintainer
     *  2026-10-21: 1,000 sats with 500 in the wallet should not be possible at all). */
    fun checkWalletBalance() {
        balanceAsked = viewModelScope.async {
            value.walletBalance().also {
                _walletBalance.value = (it as? BalanceAnswer.Known)?.msat
                _walletSilent.value = it == BalanceAnswer.Silent
            }
        }
    }

    /** Whether [amountSats] and its fees fit [balanceMsat]; true when that is unknown. */
    fun covers(balanceMsat: Long?, amountSats: Long): Boolean = value.covers(balanceMsat, amountSats * 1000)
    private val _plan = MutableStateFlow<BoostPlan?>(null)
    val plan: StateFlow<BoostPlan?> = _plan.asStateFlow()
    private val _boost = MutableStateFlow<BoostState>(BoostState.Editing)
    val boost: StateFlow<BoostState> = _boost.asStateFlow()
    private val _rate = MutableStateFlow<Int?>(null)
    /** Sats per minute for the episode playing: the listener's or the show's. */
    val rate: StateFlow<Int?> = _rate.asStateFlow()

    private val _reach = MutableStateFlow<space.sovereigncast.hopp.value.Reach?>(null)
    /** What of the episode playing the connected wallet reaches; null without a wallet or split. */
    val reach: StateFlow<space.sovereigncast.hopp.value.Reach?> = _reach.asStateFlow()

    private val _hasValue = MutableStateFlow<Boolean?>(null)
    /** The episode or its show names recipients (a value block); null until known. */
    val hasValue: StateFlow<Boolean?> = _hasValue.asStateFlow()

    fun forEpisode(item: EpisodeItem) = viewModelScope.launch {
        _hasValue.value = runCatching { value.blockFor(item) != null }.getOrDefault(true)
        _reach.value = runCatching { value.reach(item) }.getOrNull()
        val own = settings.current().streamRateSats
        _rate.value = own.takeIf { it > 0 } ?: value.suggestedSatsPerMinute(value.blockFor(item)) ?: ValueService.DEFAULT_RATE
    }

    fun plan(item: EpisodeItem, positionSeconds: Long, amountSats: Long) = viewModelScope.launch {
        _plan.value = value.plan(item, positionSeconds, amountSats * 1000)
    }

    fun send(item: EpisodeItem, positionSeconds: Long, amountSats: Long, message: String, name: String, onNostr: Boolean = false) =
        run(item.podcastId, amountSats, name, onNostr = onNostr.takeIf { it }?.let { { sats -> postBoost(item, sats, message) } }) {
            value.boost(item, positionSeconds, amountSats * 1000, message)
        }

    /** The paid part of a boost as a comment on the episode; false when it could not go out. */
    private suspend fun postBoost(item: EpisodeItem, sats: Long, message: String): Boolean = runCatching {
        settings.update { it.copy(nostrPublicNoted = true) }
        val anchor = comments?.anchor(item.id) ?: return false
        // Where its comments came from, if they were loaded; the writer adds the listener's relays.
        val relays = comments.reader.cached(anchor)?.relays.orEmpty()
        (writer ?: return false).boost(anchor, sats, message, relays)
    }.isSuccess

    fun planShow(podcastId: Long, amountSats: Long) = viewModelScope.launch {
        _plan.value = value.planShow(podcastId, amountSats * 1000)
    }

    fun sendShow(podcastId: Long, amountSats: Long, message: String, name: String) =
        run(podcastId, amountSats, name) { value.boostShow(podcastId, amountSats * 1000, message) }

    fun planDonation(address: String, amountSats: Long) = viewModelScope.launch {
        _plan.value = value.planDonation(address, amountSats * 1000)
    }

    fun sendDonation(podcastId: Long, address: String, amountSats: Long, message: String, name: String) =
        run(podcastId, amountSats, name) { value.donate(podcastId, address, amountSats * 1000, message) }

    /** In the app's scope, not the screen's: a boost on its way is finished
     *  and reported even when the sheet or the player closes (BoostReports). */
    private fun run(
        podcastId: Long, amountSats: Long, name: String,
        onNostr: (suspend (Long) -> Boolean)? = null,
        pay: suspend () -> List<space.sovereigncast.hopp.value.ShareResult>,
    ) {
        _boost.value = BoostState.Sending
        reports.scope.launch {
            // Sent before the wallet answered: once it has, a boost it cannot carry goes back
            // to the sheet, which then says why.
            val answer = runCatching { balanceAsked?.await() }.getOrNull()
            // A wallet that gave no answer would leave the boost "outcome unknown": not sent.
            if (answer == BalanceAnswer.Silent) { _boost.value = BoostState.Editing; return@launch }
            val balance = (answer as? BalanceAnswer.Known)?.msat
            if (!covers(balance, amountSats)) { _boost.value = BoostState.Editing; return@launch }
            settings.update { it.copy(senderName = name.trim()) }
            val outcome = try {
                val results = pay()
                // Public only for what was paid, and only when something was.
                val paidSats = results.filterIsInstance<space.sovereigncast.hopp.value.ShareResult.Paid>().sumOf { it.share.amountMsat } / 1000
                if (onNostr != null && paidSats > 0) {
                    _boost.value = BoostState.Done(results, BoostState.NostrPost.Posting)
                    _boost.value = BoostState.Done(results, if (onNostr(paidSats)) BoostState.NostrPost.Posted else BoostState.NostrPost.Failed)
                } else _boost.value = BoostState.Done(results)
                value.outcome(podcastId, amountSats, results)
            } catch (e: Exception) {
                val reason = e.message ?: "error"
                _boost.value = BoostState.Failed(reason)
                space.sovereigncast.hopp.value.QuickBoost.Failed(reason)
            }
            reports.report(outcome)
            checkWalletBalance()
        }
    }

    fun resetBoost() { _boost.value = BoostState.Editing; _plan.value = null }

    fun setStreaming(on: Boolean) = viewModelScope.launch {
        settings.update { it.copy(streaming = on) }
        // Switching off pays what has accrued so far.
        if (!on) runCatching { value.settle() }
    }
}
