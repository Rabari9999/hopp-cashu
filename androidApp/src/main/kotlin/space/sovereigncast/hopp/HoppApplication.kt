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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.data.DownloadRepository
import space.sovereigncast.hopp.data.DownloadScheduler
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.di.sharedModule
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.CrashReports
import android.os.Build
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import space.sovereigncast.hopp.sovereigncast.SovereigncastRepository
import java.util.concurrent.TimeUnit

class HoppApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@HoppApplication)
            modules(sharedModule(AndroidPlatform(this@HoppApplication)))
        }
        val koin = KoinPlatform.getKoin()
        catchCrashes(koin.get(), koin.get())
        // The background refresh as the listener set it — rescheduled when they change it.
        MainScope().launch {
            koin.get<SettingsRepository>().settings.map { it.refreshHours to it.refreshWifiOnly }.distinctUntilChanged()
                .collect { (hours, wifiOnly) -> scheduleRefresh(this@HoppApplication, hours, wifiOnly) }
        }
        // Whatever was queued when the app last stopped, and yesterday's played files.
        MainScope().launch {
            koin.get<DownloadRepository>().cleanup()
            koin.get<DownloadScheduler>().run { schedule(manual = true); schedule(manual = false) }
        }
    }
}

/** The last words of a crashing process, kept for the listener to share
 *  (ADR 0015) — then Android's own handler ends it as before. */
private fun catchCrashes(reports: CrashReports, app: AppInfo) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            reports.write(CrashReports.format(app, "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", java.time.Instant.now().toString(), thread.name, error))
        }
        previous?.uncaughtException(thread, error)
    }
}

/** The feed refresh in the background (ADR 0013), every [hours] as set in the
 *  settings (device feedback 2026-10-09); 0 = none. Only with a network —
 *  an unmetered one if [wifiOnly]. */
fun scheduleRefresh(context: Context, hours: Int, wifiOnly: Boolean) {
    val work = WorkManager.getInstance(context)
    if (hours <= 0) { work.cancelUniqueWork("feed-refresh"); return }
    val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
        .build()
    work.enqueueUniquePeriodicWork("feed-refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val podcasts = KoinPlatform.getKoin().get<PodcastRepository>()
        val summary = podcasts.refreshAll()
        // The Sovereigncast instances in the same go (device feedback 2026-10-17).
        runCatching { KoinPlatform.getKoin().get<SovereigncastRepository>().refresh(force = true) }
        KoinPlatform.getKoin().get<DownloadRepository>().cleanup()
        return if (summary.failed == summary.checked && summary.checked > 0) Result.retry() else Result.success()
    }
}
