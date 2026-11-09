package space.sovereigncast.hopp.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** What only the platform can do for the shared UI — file pickers, sharing. */
interface PlatformActions {
    /** Lets the listener pick an OPML file; calls back with its text. */
    fun pickOpml(onText: (String) -> Unit)
    /** Saves or shares an exported OPML file. */
    fun exportOpml(text: String)
    /** Whether a picture can be picked here for the Nostr profile (maintainer 2026-11-03). */
    val canPickImage: Boolean get() = false
    /** Lets the listener pick a picture; calls back with it made square, [PROFILE_PICTURE_SIZE] px, as a JPEG
     *  without the original's metadata (no place it was taken) — nothing when closed or unreadable. */
    fun pickProfileImage(onImage: (ByteArray) -> Unit) = Unit
    /** Hands text to the system's share sheet (mail, messenger, …). */
    fun shareText(text: String, subject: String) = Unit
    /** Whether episodes can be downloaded at all — in the browser too since 2026-11-08, not in a tab that keeps nothing. */
    val canDownload: Boolean get() = true
    /** Whether new episodes download by themselves — a show's switch, the setting; not in the browser. */
    val canAutoDownload: Boolean get() = canDownload
    /** Whether Hopp pays here — boosts, streaming sats, zaps, Sovereigncast's payments. */
    val canPay: Boolean get() = true
    /** Whether it pays with the browser's own wallet (WebLN, ADR 0022, 4) instead of one connected over NWC. */
    val paysWithBrowser: Boolean get() = false
    /** A new build of Hopp Web waiting to take over (its version) — the service worker's (ADR 0022, 14); null in the apps. */
    val updateReady: kotlinx.coroutines.flow.StateFlow<String?>? get() = null
    /** Lets the build waiting take over: the page reloads into it. */
    fun applyUpdate() = Unit
    /** Whether the system shows Hopp's notifications — a payment's outcome among them; not the browser's tab. */
    val canNotify: Boolean get() = true
    /** Whether an episode's audio file can be saved instead — in the browser, where nothing is downloaded or a server
     *  does not let a web page download its audio. */
    val canSaveAudio: Boolean get() = false
    /** Saves the audio file at [url] as [name], as the platform can (maintainer 2026-10-28). */
    fun saveAudio(url: String, name: String) = Unit
    /** Whether a downloaded episode can be handed on as a file (maintainer 2026-10-26). */
    val canShareFiles: Boolean get() = false
    /** Hands the downloaded file at [path] to the system's share sheet as [name] ("Show – Episode.mp3"). */
    fun shareFile(path: String, name: String) = Unit
    /** Puts text on the clipboard (device feedback 2026-10-12: nothing could be copied). */
    fun copyText(text: String, label: String) = Unit
    /** A browser on a phone or tablet, where no extension can be installed — its sign-in offers none (maintainer 2026-11-03). */
    val mobileBrowser: Boolean get() = false
    /** Opens [entity] (`npub1…`, NIP-19) as a NIP-21 `nostr:` link in the listener's Nostr app;
     *  without one, on njump.me in the browser. */
    fun openNostr(entity: String) = Unit
    /** Opens the system's notification settings for Hopp. */
    fun openNotificationSettings() = Unit
    /** Whether the system lets Hopp's language differ from its own (Android 13+). */
    val canPickLanguage: Boolean get() = false
    /** Opens the system's language choice for Hopp. */
    fun openLanguageSettings() = Unit
    /** Where the system offers no language choice for Hopp that always works (the browser, iOS): the
     *  languages Hopp offers itself, by code, each in its own name; null where the system's page is the way. */
    val appLanguages: Map<String, String>? get() = null
    /** The language chosen in [appLanguages]; null: the system's (the browser's). */
    val appLanguage: String? get() = null
    /** Chooses [code] from [appLanguages], null for the system's; Hopp starts again in it. */
    fun setAppLanguage(code: String?) = Unit
    /** Whether the system's language, in [appLanguages], is the browser's rather than the device's. */
    val languageFromBrowser: Boolean get() = false
    /** Whether the lists' refresh gets a button in the top bar: a mouse cannot pull a list down
     *  (the browser on a desktop; maintainer 2026-11-01). */
    val refreshButton: Boolean get() = false
    /** Keeps the screen out of screenshots and the recent-apps preview while on. */
    fun secureScreen(on: Boolean) = Unit
    /** Opens the system's VPN settings. */
    fun openVpnSettings() = Unit
    /** Opens the system's network settings, where private DNS is. */
    fun openNetworkSettings() = Unit
    /** Whether a file manager can show Hopp's downloaded episodes (Android). */
    val canBrowseDownloads: Boolean get() = false
    /** Opens the downloaded episodes in the system's file manager. */
    fun browseDownloads() = Unit
    /** Whether a QR code can be read with the camera (Android, with a camera). */
    val canScanQr: Boolean get() = false
    /** Reads a QR code with the camera; calls back with its text, not when closed. */
    fun scanQr(onText: (String) -> Unit) = Unit
    /** Where the Nostr key can be kept beyond this device; null where there is none. */
    val keyVault: space.sovereigncast.hopp.platform.KeyVault? get() = null
    /** "This is not my device" at sign-in (ADR 0022, 2); null where Hopp keeps its data anyway (the apps). */
    val tabOnly: space.sovereigncast.hopp.platform.TabOnly? get() = null
    /** Copies a secret: marked sensitive, gone from the clipboard after a minute, not sent to other devices. */
    fun copySecret(text: String, label: String) = copyText(text, label)
    /** The listener's choice: light (false), dark (true) or the system's (null) — for what the system draws
     *  around Hopp, the status bar's icons (review 2026-10-31: they followed the system's, unreadable on a
     *  theme chosen against it). */
    fun themeChosen(dark: Boolean?) = Unit
    /** The design's ground, for what the browser colours around the page. */
    fun groundChosen(argb: Int) = Unit
}

object NoPlatformActions : PlatformActions {
    override fun pickOpml(onText: (String) -> Unit) = Unit
    override fun exportOpml(text: String) = Unit
}

/** A profile picture's side, in pixels: sharp in any client's circle, small to upload. */
const val PROFILE_PICTURE_SIZE = 512

val LocalPlatformActions = staticCompositionLocalOf<PlatformActions> { NoPlatformActions }
