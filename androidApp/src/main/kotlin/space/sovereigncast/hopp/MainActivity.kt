package space.sovereigncast.hopp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import space.sovereigncast.hopp.ui.App
import space.sovereigncast.hopp.ui.IncomingLinks
import space.sovereigncast.hopp.ui.PlatformActions

class MainActivity : ComponentActivity(), PlatformActions {
    private var onOpml: ((String) -> Unit)? = null
    private var pendingExport: String? = null

    private val openOpml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = onOpml ?: return@registerForActivityResult
        onOpml = null
        uri ?: return@registerForActivityResult
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: return@registerForActivityResult
        callback(text)
    }

    private val saveOpml = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        val text = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        uri ?: return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.use { it.write(text.encodeToByteArray()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The media notification needs this on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { App(this) }
        takeLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeLink(intent)
    }

    private fun takeLink(intent: Intent?) {
        val uri = intent?.data?.takeIf { it.scheme?.startsWith("nostr") == true } ?: return
        IncomingLinks.walletUri.value = uri.toString()
    }

    override fun pickOpml(onText: (String) -> Unit) {
        onOpml = onText
        openOpml.launch(arrayOf("text/x-opml", "text/xml", "application/xml", "application/octet-stream", "*/*"))
    }

    override fun exportOpml(text: String) {
        pendingExport = text
        saveOpml.launch("hopp-subscriptions.opml")
    }

    override fun shareText(text: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, subject))
    }

}
