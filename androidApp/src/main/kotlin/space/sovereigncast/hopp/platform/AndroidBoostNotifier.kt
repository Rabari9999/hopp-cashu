package space.sovereigncast.hopp.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import space.sovereigncast.hopp.R
import space.sovereigncast.hopp.value.BoostNotifier
import space.sovereigncast.hopp.value.QuickBoost
import space.sovereigncast.hopp.value.ok

/** A boost's result as a notification — from the lock screen's quick boost
 *  and from the boost sheet alike; one at a time, the newest replaces it. */
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
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.boosts_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_boost)
            .setContentTitle(context.getString(R.string.quick_boost))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            // A success needs no keeping: it goes by itself after a few seconds.
            .apply { if (result.ok) setTimeoutAfter(6_000) }
            .build()
        runCatching { manager.notify(NOTIFICATION, n) }
    }

    companion object {
        const val CHANNEL = "boosts"
        const val NOTIFICATION = 3
    }
}
