package space.sovereigncast.hopp.value

import kotlinx.coroutines.CoroutineScope
import space.sovereigncast.hopp.data.SettingsRepository

/** Shows a boost's or a zap's result outside Hopp's screens — on Android a notification. */
fun interface BoostNotifier {
    fun show(result: QuickBoost)

    fun zapped(result: ZapResult) = Unit

    companion object {
        val None = BoostNotifier { }
    }
}

/** Every recipient got their full share. */
val QuickBoost.ok: Boolean get() = this is QuickBoost.Sent && paidSats == amountSats

/** A zap on a comment: [sats] to [recipient] (a name, if known) — paid, or not for [failure] or [overBudget]. */
data class ZapResult(val sats: Long, val recipient: String?, val failure: String? = null, val overBudget: Boolean = false) {
    val ok: Boolean get() = failure == null && !overBudget
}

/**
 * Every boost's and zap's result, reported as the listener chose — each on its
 * own (device feedback 2026-10-19): the lock screen's quick boost
 * ([space.sovereigncast.hopp.data.Settings.quickBoostFeedback], device feedback
 * 2026-10-10), the boost sheet of the player, an episode or a show
 * ([space.sovereigncast.hopp.data.Settings.boostFeedback], 2026-10-11) and a
 * zap on a comment ([space.sovereigncast.hopp.data.Settings.zapFeedback]).
 * Boosts from a sheet run in [scope], the app's, so one in flight is
 * finished, logged and reported even when its screen closes.
 */
class BoostReports(private val settings: SettingsRepository, private val notifier: BoostNotifier, val scope: CoroutineScope) {
    suspend fun report(result: QuickBoost, quick: Boolean = false) {
        if (result == QuickBoost.Off) return
        if (settings.current().reportsBoost(result.ok, quick)) notifier.show(result)
    }

    suspend fun report(result: ZapResult) {
        if (settings.current().reportsZap(result.ok)) notifier.zapped(result)
    }
}
