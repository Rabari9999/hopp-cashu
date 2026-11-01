package space.sovereigncast.hopp

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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

    private var onQr: ((String) -> Unit)? = null
    private val scanQr = registerForActivityResult(ScanQrActivity.Scan()) { text ->
        val callback = onQr ?: return@registerForActivityResult
        onQr = null
        text?.let(callback)
    }

    /** The Files app at Hopp's episodes ([EpisodesProvider]). */
    private fun filesIntent() = Intent(Intent.ACTION_VIEW)
        .setDataAndType(android.provider.DocumentsContract.buildRootUri(EpisodesProvider.authority(this), EpisodesProvider.ROOT), android.provider.DocumentsContract.Root.MIME_TYPE_ITEM)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override val canBrowseDownloads: Boolean get() = filesIntent().resolveActivity(packageManager) != null

    override fun browseDownloads() {
        runCatching { startActivity(filesIntent()) }
    }

    override val canScanQr: Boolean get() = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    override fun scanQr(onText: (String) -> Unit) {
        onQr = onText
        scanQr.launch(Unit)
    }

    /** enableEdgeToEdge's own styles, told the listener's choice instead of only the system's. */
    override fun themeChosen(dark: Boolean?) {
        val detect: (Resources) -> Boolean = { res ->
            dark ?: ((res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        }
        enableEdgeToEdge(SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT, detect), SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM, detect))
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
        if (intent != null && takeOpml(intent)) return
        val uri = intent?.data ?: return
        if (uri.scheme?.startsWith("nostr") == true) IncomingLinks.walletUri.value = uri.toString()
        // "Open in Hopp": anything else without a feed opens Hopp as it is.
        else IncomingLinks.linkFrom(uri.toString())?.let { IncomingLinks.show.value = it }
    }

    /**
     * An OPML file from another app — opened with Hopp or shared to it, as a
     * file or as text. Read whole only up to Limits.OPML, as a picked one is.
     */
    private fun takeOpml(intent: Intent): Boolean {
        val stream: android.net.Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" }
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        val text = when {
            stream != null -> runCatching {
                contentResolver.openInputStream(stream)?.use { readAtMost(it, space.sovereigncast.hopp.net.Limits.OPML + 1) }
            }.getOrNull()?.takeIf { it.size <= space.sovereigncast.hopp.net.Limits.OPML }?.decodeToString()
            intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (text == null && stream == null) return false
        // Unreadable, too large or no OPML (any XML can come this way): the page says there are no shows in it.
        IncomingLinks.opml.value = text?.takeIf(IncomingLinks::isOpml).orEmpty()
        return true
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

    /** Whatever app takes `nostr:` (Amethyst, Primal …) — the system asks which when there are several. */
    override fun openNostr(entity: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("nostr:$entity"))) }
            .onFailure { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://njump.me/$entity"))) } }
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

    override val keyVault: space.sovereigncast.hopp.platform.KeyVault? by lazy {
        if (space.sovereigncast.hopp.platform.PasswordManagerVault.available) space.sovereigncast.hopp.platform.PasswordManagerVault(this) else null
    }

    /** Marked sensitive (Android 13+ hides it in the copy preview and from keyboards' suggestions),
     *  and gone after a minute if it is still what the clipboard holds. */
    override fun copySecret(text: String, label: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val clip = android.content.ClipData.newPlainText(label, text)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) android.content.ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
        android.os.Handler(mainLooper).postDelayed({
            val now = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (now == text) {
                if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
        }, 60_000)
    }

    override fun copyText(text: String, label: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        // Android 13+ confirms a copy itself; before, say so.
        if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    override val canShareFiles: Boolean get() = true

    /** Through the Files app's provider: the receiver reads the file, named [name] there, and nothing else. */
    override fun shareFile(path: String, name: String) {
        val file = java.io.File(path)
        val uri = android.provider.DocumentsContract.buildDocumentUri(EpisodesProvider.authority(this), file.name)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "audio/*"
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(name, uri)
        runCatching { startActivity(Intent.createChooser(send, name)) }
    }

    override fun shareText(text: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, subject))
    }
}

// enableEdgeToEdge's defaults for the navigation bar's scrim where the system draws one (before Android 10).
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
