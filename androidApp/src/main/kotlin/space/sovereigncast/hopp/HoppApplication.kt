package space.sovereigncast.hopp

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.di.sharedModule
import java.util.concurrent.TimeUnit

class HoppApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@HoppApplication)
            modules(sharedModule(AndroidPlatform(this@HoppApplication)))
        }
        scheduleRefresh(this)
    }
}

/** Hourly feed refresh in the background (ADR 0013), only with a network.
 *  Paced by podcast:updateFrequency in a later step. */
fun scheduleRefresh(context: Context) {
    val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("feed-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val podcasts = KoinPlatform.getKoin().get<PodcastRepository>()
        val summary = podcasts.refreshAll()
        return if (summary.failed == summary.checked && summary.checked > 0) Result.retry() else Result.success()
    }
}
