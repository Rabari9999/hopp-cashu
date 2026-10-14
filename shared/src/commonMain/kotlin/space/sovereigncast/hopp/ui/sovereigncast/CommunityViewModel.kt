package space.sovereigncast.hopp.ui.sovereigncast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.sovereigncast.Boost2UnlockEpisode
import space.sovereigncast.hopp.sovereigncast.Invoice
import space.sovereigncast.hopp.sovereigncast.InvoiceMismatch
import space.sovereigncast.hopp.sovereigncast.Leaderboard
import space.sovereigncast.hopp.sovereigncast.ListenerApiException
import space.sovereigncast.hopp.sovereigncast.ReleaseBoostingRound
import space.sovereigncast.hopp.sovereigncast.ScLayer
import space.sovereigncast.hopp.sovereigncast.ScTarget
import space.sovereigncast.hopp.sovereigncast.SovereigncastRepository
import space.sovereigncast.hopp.sovereigncast.Suggestion
import space.sovereigncast.hopp.sovereigncast.TopEpisode
import space.sovereigncast.hopp.value.WalletRepository

/** A payment to the instance, step by step — the listener sees where it is. */
sealed interface ScPay {
    data object Idle : ScPay
    data object Requesting : ScPay
    data object Paying : ScPay
    /** No wallet connected: the invoice goes to a wallet app; Hopp waits for the instance. */
    data class External(val invoice: Invoice) : ScPay
    data object Confirming : ScPay
    data class Done(val sats: Long) : ScPay
    /** [code]: an API error code, "mismatch", "expired" or "wallet". */
    data class Failed(val code: String, val detail: String? = null) : ScPay
}

/**
 * The Sovereigncast side of a show page (ADR 0018): verify the show, then the
 * running Release Boosting round, Boost2Unlock episodes, the open suggestions
 * with free votes, the leaderboard, the top episodes, and paying the
 * instance's invoices — boosts, unlocks, new suggestions and their boosts.
 */
class CommunityViewModel(
    private val podcastId: Long,
    private val sc: SovereigncastRepository,
    private val settings: SettingsRepository,
    wallet: WalletRepository,
    private val episodes: EpisodeRepository,
) : ViewModel() {
    val layer: StateFlow<ScLayer?> = sc.layer(podcastId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val hasWallet: StateFlow<Boolean> = wallet.wallet.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val votes: StateFlow<Set<String>> = settings.settings.map { it.votes }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val _round = MutableStateFlow<ReleaseBoostingRound?>(null)
    val round: StateFlow<ReleaseBoostingRound?> = _round.asStateFlow()
    private val _unlocks = MutableStateFlow<List<Boost2UnlockEpisode>>(emptyList())
    val unlocks: StateFlow<List<Boost2UnlockEpisode>> = _unlocks.asStateFlow()
    private val _suggestions = MutableStateFlow<List<Suggestion>?>(null)
    val suggestions: StateFlow<List<Suggestion>?> = _suggestions.asStateFlow()
    private val _leaderboard = MutableStateFlow<Leaderboard?>(null)
    /** This month's top boosts, the latest ones and the all-time boosters. */
    val leaderboard: StateFlow<Leaderboard?> = _leaderboard.asStateFlow()
    private val _top = MutableStateFlow<List<TopEpisode>?>(null)
    /** The show's best-ranked episodes (phase 2); null until loaded or when the instance has none. */
    val topEpisodes: StateFlow<List<TopEpisode>?> = _top.asStateFlow()
    private val _pay = MutableStateFlow<ScPay>(ScPay.Idle)
    val pay: StateFlow<ScPay> = _pay.asStateFlow()

    init {
        viewModelScope.launch {
            sc.verify(podcastId)
            load()
            // The round moves while the page is open (others boost too), and the
            // player shows the latest boosts: those every five minutes.
            var minutes = 0
            while (true) {
                delay(60_000)
                minutes++
                layer.value?.let { l ->
                    if (l.show.capabilities.releaseBoosting) runCatching { _round.value = sc.releaseBoosting(l).round }
                    if (l.show.capabilities.leaderboard && minutes % 5 == 0) runCatching { _leaderboard.value = sc.leaderboard(l) }
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        val l = layer.first { it != null } ?: return@launch
        val c = l.show.capabilities
        val rb = async { if (c.releaseBoosting) runCatching { sc.releaseBoosting(l).round }.getOrNull() else null }
        val b2u = async { if (c.boost2unlock) runCatching { sc.boost2unlock(l).episodes }.getOrDefault(emptyList()) else emptyList() }
        val sug = async { if (c.suggestions) runCatching { sc.suggestions(l).suggestions }.getOrNull() else null }
        val board = async { if (c.leaderboard) runCatching { sc.leaderboard(l) }.getOrNull() else null }
        val top = async { if (c.topEpisodes) runCatching { sc.topEpisodes(l).episodes }.getOrNull() else null }
        _round.value = rb.await()
        _unlocks.value = b2u.await()
        _suggestions.value = sug.await()
        board.await()?.let { _leaderboard.value = it }
        top.await()?.let { _top.value = it }
    }

    /** A top episode as Hopp has it from the feed, to open in the app; null if the feed
     *  lacks it. [then] runs on the main thread — it navigates. */
    fun episodeFor(top: TopEpisode, then: (EpisodeItem?) -> Unit) = viewModelScope.launch {
        then(runCatching { episodes.byGuid(podcastId, top.episodeGuid) }.getOrNull())
    }

    /** The least a new suggestion costs at this show (its spam filter). */
    val suggestionMinSats: Long get() = (layer.value?.show?.amounts?.suggestionMinSats ?: 1).toLong().coerceAtLeast(1)

    fun vote(s: Suggestion) {
        val l = layer.value ?: return
        if (sc.voted(l, s.id, votes.value)) return
        // Shown at once; the count comes back with the next load.
        _suggestions.value = _suggestions.value?.map { if (it.id == s.id) it.copy(voteCount = it.voteCount + 1) else it }
        viewModelScope.launch { runCatching { sc.vote(l, s.id) } }
    }

    fun pay(target: ScTarget, amountSats: Long, message: String, name: String, episodeTitle: String?) {
        val l = layer.value ?: return
        _pay.value = ScPay.Requesting
        viewModelScope.launch {
            _pay.value = try {
                settings.update { it.copy(senderName = name.trim()) }
                val invoice = sc.invoice(l, target, amountSats, message, name)
                if (hasWallet.value) {
                    _pay.value = ScPay.Paying
                    val fees = sc.payWithWallet(invoice)
                    _pay.value = ScPay.Confirming
                    if (sc.awaitPaid(l, invoice, timeoutMinutes = 2)) {
                        sc.log(podcastId, l, target, episodeTitle, amountSats, message, fees); load(); ScPay.Done(amountSats)
                    } else ScPay.Failed("expired")
                } else {
                    _pay.value = ScPay.External(invoice)
                    if (sc.awaitPaid(l, invoice)) {
                        sc.log(podcastId, l, target, episodeTitle, amountSats, message, null); load(); ScPay.Done(amountSats)
                    } else ScPay.Failed("expired")
                }
            } catch (e: InvoiceMismatch) {
                ScPay.Failed("mismatch", e.message)
            } catch (e: ListenerApiException) {
                ScPay.Failed(e.code, e.message)
            } catch (e: Exception) {
                ScPay.Failed("wallet", e.message)
            }
        }
    }

    fun resetPay() { _pay.value = ScPay.Idle }
}
