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
        // At most Limits.OPML: any file can be picked, and it is read whole (security review 2026-10-10, M-02).
        val bytes = contentResolver.openInputStream(uri)?.use { readAtMost(it, space.sovereigncast.hopp.net.Limits.OPML + 1) } ?: return@registerForActivityResult
        if (bytes.size > space.sovereigncast.hopp.net.Limits.OPML) { callback(""); return@registerForActivityResult }
        val text = bytes.decodeToString()
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

    // Public: the link tests hand the running activity a new intent, as Android does.
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeLink(intent)
    }

    private fun takeLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme?.startsWith("nostr") == true) IncomingLinks.walletUri.value = uri.toString()
        // "Open in Hopp": anything else without a feed opens Hopp as it is.
        else IncomingLinks.linkFrom(uri.toString())?.let { IncomingLinks.show.value = it }
    }

    /** Up to [max] bytes of [input] (InputStream.readNBytes needs Android 13). */
    private fun readAtMost(input: java.io.InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), max - out.size()).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    override fun pickOpml(onText: (String) -> Unit) {
        onOpml = onText
        openOpml.launch(arrayOf("text/x-opml", "text/xml", "application/xml", "application/octet-stream", "*/*"))
    }

    override fun exportOpml(text: String) {
        pendingExport = text
        saveOpml.launch("hopp-subscriptions.opml")
    }

    override fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
        runCatching { startActivity(intent) }
    }

    override val canPickLanguage: Boolean get() = Build.VERSION.SDK_INT >= 33

    override fun openLanguageSettings() {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, android.net.Uri.fromParts("package", packageName, null))) }
    }

    override fun secureScreen(on: Boolean) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (on) window.addFlags(flag) else window.clearFlags(flag)
    }

    override fun openVpnSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }
    }

    override fun openNetworkSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
    }

    override fun copyText(text: String, label: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        // Android 13+ confirms a copy itself; before, say so.
        if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun shareText(text: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, subject))
    }

}
