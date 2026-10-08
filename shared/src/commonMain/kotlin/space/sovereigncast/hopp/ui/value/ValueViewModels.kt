package space.sovereigncast.hopp.ui.value

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.SettingsRepository
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
    data class Failed(val reason: String) : ConnectState
}

class WalletViewModel(
    private val wallet: WalletRepository,
    private val value: ValueService,
    private val settings: SettingsRepository,
) : ViewModel() {
    val connected: StateFlow<ConnectedWallet?> = wallet.wallet
    val current: StateFlow<Settings?> = state(settings.settings, null)
    val spentToday: StateFlow<Long> = state(value.spentTodayMsat, 0L)
    private val _balance = MutableStateFlow<Long?>(null)
    val balanceMsat: StateFlow<Long?> = _balance.asStateFlow()
    private val _connect = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connect: StateFlow<ConnectState> = _connect.asStateFlow()

    init { refreshBalance() }

    fun refreshBalance() { if (wallet.wallet.value != null) viewModelScope.launch { _balance.value = wallet.balanceMsat() } }

    fun connect(uri: String) {
        _connect.value = ConnectState.Working
        viewModelScope.launch {
            _connect.value = try {
                wallet.connect(uri); refreshBalance(); ConnectState.Idle
            } catch (e: Exception) {
                ConnectState.Failed(e.message ?: e::class.simpleName ?: "error")
            }
        }
    }

    fun disconnect() {
        wallet.disconnect()
        _balance.value = null
        // Without a wallet nothing streams (ADR 0005).
        update { it.copy(streaming = false) }
    }

    fun update(change: (Settings) -> Settings) = viewModelScope.launch { settings.update(change) }
}

class HistoryViewModel(value: ValueService) : ViewModel() {
    val payments: StateFlow<List<Payment>?> = state(value.payments, null)
}

sealed interface BoostState {
    data object Editing : BoostState
    data object Sending : BoostState
    data class Done(val results: List<ShareResult>) : BoostState
    data class Failed(val reason: String) : BoostState
}

/** The value bar and the boost dialog of now playing (ADR 0019). */
class ValueBarViewModel(
    private val value: ValueService,
    wallet: WalletRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val connected: StateFlow<ConnectedWallet?> = wallet.wallet
    val current: StateFlow<Settings?> = state(settings.settings, null)
    val streamedToday: StateFlow<Long> = state(value.streamedTodayMsat, 0L)
    val budgetLeft: StateFlow<Long> = state(value.budgetLeftMsat, 0L)
    val overBudget: StateFlow<Boolean> = value.overBudget
    private val _plan = MutableStateFlow<BoostPlan?>(null)
    val plan: StateFlow<BoostPlan?> = _plan.asStateFlow()
    private val _boost = MutableStateFlow<BoostState>(BoostState.Editing)
    val boost: StateFlow<BoostState> = _boost.asStateFlow()
    private val _rate = MutableStateFlow<Int?>(null)
    /** Sats per minute for the episode playing: the listener's or the show's. */
    val rate: StateFlow<Int?> = _rate.asStateFlow()

    fun forEpisode(item: EpisodeItem) = viewModelScope.launch {
        val own = settings.current().streamRateSats
        _rate.value = own.takeIf { it > 0 } ?: value.suggestedSatsPerMinute(value.blockFor(item)) ?: ValueService.DEFAULT_RATE
    }

    fun plan(item: EpisodeItem, positionSeconds: Long, amountSats: Long) = viewModelScope.launch {
        _plan.value = value.plan(item, positionSeconds, amountSats * 1000)
    }

    fun send(item: EpisodeItem, positionSeconds: Long, amountSats: Long, message: String, name: String) {
        _boost.value = BoostState.Sending
        viewModelScope.launch {
            settings.update { it.copy(senderName = name.trim()) }
            _boost.value = try {
                BoostState.Done(value.boost(item, positionSeconds, amountSats * 1000, message))
            } catch (e: Exception) {
                BoostState.Failed(e.message ?: "error")
            }
        }
    }

    fun resetBoost() { _boost.value = BoostState.Editing; _plan.value = null }

    fun setStreaming(on: Boolean) = viewModelScope.launch {
        settings.update { it.copy(streaming = on) }
        // Switching off pays what has accrued so far.
        if (!on) runCatching { value.settle() }
    }
}
