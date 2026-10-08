package space.sovereigncast.hopp.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.mp.KoinPlatform
import space.sovereigncast.hopp.R
import space.sovereigncast.hopp.data.DownloadRepository
import space.sovereigncast.hopp.data.DownloadScheduler
import java.util.concurrent.TimeUnit

/** Requested downloads wait for any network, automatic ones for an unmetered
 *  one (ADR 0012). One chain each; a new request queues behind a running one. */
class WorkDownloadScheduler(private val context: Context) : DownloadScheduler {
    override fun schedule(manual: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (manual) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(if (manual) "downloads-manual" else "downloads-auto", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val downloads = KoinPlatform.getKoin().get<DownloadRepository>()
        // Whichever chain started it: automatic downloads only off mobile data.
        val metered = applicationContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        // Long episodes outlast WorkManager's ten minutes without a foreground
        // notification. Android 12+ may refuse that from the background — the
        // .part file then resumes on the next run.
        runCatching { setForeground(foregroundInfo()) }
        return if (downloads.runPending(includeAuto = !metered)) Result.success() else Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.downloads_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.downloads_running))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 2
    }
}
