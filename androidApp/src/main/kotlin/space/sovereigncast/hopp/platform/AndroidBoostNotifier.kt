package space.sovereigncast.hopp.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import space.sovereigncast.hopp.R
import space.sovereigncast.hopp.value.BoostNotifier
import space.sovereigncast.hopp.value.QuickBoost
import space.sovereigncast.hopp.value.ZapResult
import space.sovereigncast.hopp.value.ok

/** A boost's or a zap's result as a notification — from the lock screen's
 *  quick boost, the boost sheet and the zap sheet alike; one at a time, the
 *  newest replaces it. Zaps have a channel of their own, to silence apart. */
class AndroidBoostNotifier(private val context: Context) : BoostNotifier {
    override fun show(result: QuickBoost) {
        val text = when (result) {
            is QuickBoost.Sent -> if (result.ok) context.getString(R.string.quick_boost_sent, result.paidSats.toString(), result.show)
                else context.getString(R.string.quick_boost_partial, result.paidSats.toString(), result.amountSats.toString(), result.show)
            QuickBoost.OverBudget -> context.getString(R.string.quick_boost_budget)
            QuickBoost.NoValue -> context.getString(R.string.quick_boost_no_value)
            QuickBoost.NoWallet -> context.getString(R.string.quick_boost_no_wallet)
            is QuickBoost.Failed -> context.getString(R.string.quick_boost_failed, result.reason)
            QuickBoost.Off -> return
        }
        notify(CHANNEL, R.string.boosts_channel, context.getString(R.string.quick_boost), text, result.ok)
    }

    override fun zapped(result: ZapResult) {
        val to = result.recipient?.takeIf { it.isNotBlank() } ?: context.getString(R.string.zap_someone)
        val text = when {
            result.ok -> context.getString(R.string.zap_sent, result.sats.toString(), to)
            result.overBudget -> context.getString(R.string.zap_budget)
            else -> context.getString(R.string.zap_failed, to, result.failure.orEmpty())
        }
        notify(ZAP_CHANNEL, R.string.zaps_channel, context.getString(R.string.zap), text, result.ok)
    }

    private fun notify(channel: String, channelName: Int, title: String, text: String, ok: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channel, context.getString(channelName), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_boost)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            // A success needs no keeping: it goes by itself after a few seconds.
            .apply { if (ok) setTimeoutAfter(6_000) }
            .build()
        runCatching { manager.notify(NOTIFICATION, n) }
    }

    companion object {
        const val CHANNEL = "boosts"
        const val ZAP_CHANNEL = "zaps"
        const val NOTIFICATION = 3
    }
}
