package space.sovereigncast.hopp.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.value.BoostReports
import space.sovereigncast.hopp.value.ValueService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The quick boost of the lock screen, the notification and the home-screen widget: one tap, the
 * set amount, no dialog (device feedback 2026-10-08) — and a notification that says what happened,
 * as the listener chose (device feedback 2026-10-10).
 */
object QuickBoost {
    /** One at a time, wherever it is pressed: a second tap while the first is paying is not a second boost. */
    private val boosting = AtomicBoolean(false)

    /** The boost on its way, or null when there is nothing to boost or one is already paying. */
    fun send(scope: CoroutineScope): Job? {
        val koin = KoinPlatform.getKoin()
        val controller = koin.get<PlayerController>()
        // During a Sovereigncast clip the episode behind it is not what is heard.
        if (controller.remoteClip.value != null) return null
        val now = controller.current.value?.item ?: return null
        val position = controller.state.value.positionMs / 1000
        if (!boosting.compareAndSet(false, true)) return null
        return scope.launch {
            try { koin.get<BoostReports>().report(koin.get<ValueService>().quickBoost(now, position), quick = true) } finally { boosting.set(false) }
        }
    }
}
