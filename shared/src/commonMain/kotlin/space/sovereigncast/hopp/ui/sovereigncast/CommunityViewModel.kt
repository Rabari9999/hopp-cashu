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
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.sovereigncast.Boost2UnlockEpisode
import space.sovereigncast.hopp.sovereigncast.Invoice
import space.sovereigncast.hopp.sovereigncast.InvoiceMismatch
import space.sovereigncast.hopp.sovereigncast.ListenerApiException
import space.sovereigncast.hopp.sovereigncast.ReleaseBoostingRound
import space.sovereigncast.hopp.sovereigncast.ScLayer
import space.sovereigncast.hopp.sovereigncast.ScTarget
import space.sovereigncast.hopp.sovereigncast.SovereigncastRepository
import space.sovereigncast.hopp.sovereigncast.Suggestion
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
 * The Sovereigncast side of a show page (ADR 0018, phase 1): verify the show,
 * then the running Release Boosting round, Boost2Unlock episodes, the open
 * suggestions with free votes, and paying the instance's invoices.
 */
class CommunityViewModel(
    private val podcastId: Long,
    private val sc: SovereigncastRepository,
    private val settings: SettingsRepository,
    wallet: WalletRepository,
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
    private val _pay = MutableStateFlow<ScPay>(ScPay.Idle)
    val pay: StateFlow<ScPay> = _pay.asStateFlow()

    init {
        viewModelScope.launch {
            sc.verify(podcastId)
            load()
            // The round moves while the page is open (others boost too).
            while (true) {
                delay(60_000)
                layer.value?.let { l -> if (l.show.capabilities.releaseBoosting) runCatching { _round.value = sc.releaseBoosting(l).round } }
            }
        }
    }

    fun load() = viewModelScope.launch {
        val l = layer.first { it != null } ?: return@launch
        val c = l.show.capabilities
        val rb = async { if (c.releaseBoosting) runCatching { sc.releaseBoosting(l).round }.getOrNull() else null }
        val b2u = async { if (c.boost2unlock) runCatching { sc.boost2unlock(l).episodes }.getOrDefault(emptyList()) else emptyList() }
        val sug = async { if (c.suggestions) runCatching { sc.suggestions(l).suggestions }.getOrNull() else null }
        _round.value = rb.await()
        _unlocks.value = b2u.await()
        _suggestions.value = sug.await()
    }

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
