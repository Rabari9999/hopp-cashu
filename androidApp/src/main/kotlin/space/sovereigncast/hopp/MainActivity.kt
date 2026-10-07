package space.sovereigncast.hopp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import space.sovereigncast.hopp.playback.AndroidPlaybackEngine
import space.sovereigncast.hopp.ui.App
import space.sovereigncast.hopp.ui.AppDependencies

class MainActivity : ComponentActivity() {
    private lateinit var engine: AndroidPlaybackEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The media notification needs this on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        engine = AndroidPlaybackEngine(applicationContext)
        val deps = AppDependencies(appInfo(this), engine, PrefsPositionStore(applicationContext))
        setContent { App(deps) }
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}
