package space.sovereigncast.hopp.value

import kotlinx.coroutines.CoroutineScope
import space.sovereigncast.hopp.data.SettingsRepository

/** Shows a boost's result outside Hopp's screens — on Android a notification. */
fun interface BoostNotifier {
    fun show(result: QuickBoost)

    companion object {
        val None = BoostNotifier { }
    }
}

/** Every recipient got their full share. */
val QuickBoost.ok: Boolean get() = this is QuickBoost.Sent && paidSats == amountSats

/**
 * Every boost's result, reported as the listener chose ([space.sovereigncast.hopp.data.Settings.boostFeedback]):
 * the lock screen's quick boost (device feedback 2026-10-10) and the boost
 * sheet of the player, an episode or a show alike (device feedback
 * 2026-10-11). Boosts from a sheet run in [scope], the app's, so one in
 * flight is finished, logged and reported even when its screen closes.
 */
class BoostReports(private val settings: SettingsRepository, private val notifier: BoostNotifier, val scope: CoroutineScope) {
    suspend fun report(result: QuickBoost) {
        if (result == QuickBoost.Off) return
        if (settings.current().reportsBoost(result.ok)) notifier.show(result)
    }
}
