package space.sovereigncast.hopp.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** What only the platform can do for the shared UI — file pickers, sharing. */
interface PlatformActions {
    /** Lets the listener pick an OPML file; calls back with its text. */
    fun pickOpml(onText: (String) -> Unit)
    /** Saves or shares an exported OPML file. */
    fun exportOpml(text: String)
    /** Hands text to the system's share sheet (mail, messenger, …). */
    fun shareText(text: String, subject: String) = Unit
    /** Puts text on the clipboard (device feedback 2026-10-12: nothing could be copied). */
    fun copyText(text: String, label: String) = Unit
    /** Opens the system's notification settings for Hopp. */
    fun openNotificationSettings() = Unit
    /** Whether the system lets Hopp's language differ from its own (Android 13+). */
    val canPickLanguage: Boolean get() = false
    /** Opens the system's language choice for Hopp. */
    fun openLanguageSettings() = Unit
    /** Keeps the screen out of screenshots and the recent-apps preview while on. */
    fun secureScreen(on: Boolean) = Unit
    /** Opens the system's VPN settings. */
    fun openVpnSettings() = Unit
    /** Opens the system's network settings, where private DNS is. */
    fun openNetworkSettings() = Unit
    /** Whether a QR code can be read with the camera (Android, with a camera). */
    val canScanQr: Boolean get() = false
    /** Reads a QR code with the camera; calls back with its text, not when closed. */
    fun scanQr(onText: (String) -> Unit) = Unit
}

object NoPlatformActions : PlatformActions {
    override fun pickOpml(onText: (String) -> Unit) = Unit
    override fun exportOpml(text: String) = Unit
}

val LocalPlatformActions = staticCompositionLocalOf<PlatformActions> { NoPlatformActions }
