package space.sovereigncast.hopp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import kotlinx.cinterop.cValue
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIPasteboard
import platform.Foundation.dateByAddingTimeInterval
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import space.sovereigncast.hopp.ui.PlatformActions

/**
 * What the app asks of iOS (device feedback 2026-10-13: on the iPhone and the
 * iPad, the settings' "notifications" button did nothing — iOS ran without
 * any of these, so copying and sharing did nothing either).
 *
 * Not here, because iOS has no public way: VPN and network settings (the
 * privacy page shows no button for them on iOS), a per-app language (iOS sets
 * it in Hopp's own page of the Settings app), OPML files (with the iOS work
 * after phase 2, ADR 0018).
 */
class IosPlatformActions : PlatformActions {
    override fun pickOpml(onText: (String) -> Unit) = Unit
    override fun exportOpml(text: String) = Unit

    /**
     * Hopp's notification settings, as on Android (device feedback 2026-10-14: Hopp's
     * page in the Settings app was one step short). iOS 16 has a link for them,
     * UIApplication.openNotificationSettingsURLString — "app-settings:notifications",
     * read from the SDK. Spelled out rather than linked, since Hopp runs from 15.3,
     * where the symbol does not exist; there Hopp's own page, Notifications at its top.
     */
    @OptIn(ExperimentalForeignApi::class)
    override fun openNotificationSettings() {
        val sixteen = NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
            cValue<NSOperatingSystemVersion> { majorVersion = 16; minorVersion = 0; patchVersion = 0 },
        )
        val url = NSURL.URLWithString(if (sixteen) NOTIFICATION_SETTINGS else UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
    }

    /** Damus, Primal and others take `nostr:`; none installed, njump.me in Safari. */
    override fun openNostr(entity: String) {
        val app = NSURL.URLWithString("nostr:$entity") ?: return
        UIApplication.sharedApplication.openURL(app, emptyMap<Any?, Any?>()) { opened ->
            if (!opened) NSURL.URLWithString("https://njump.me/$entity")?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null) }
        }
    }

    /** iOS shows no confirmation of its own; the clipboard is the confirmation. */
    override fun copyText(text: String, label: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override val keyVault: space.sovereigncast.hopp.platform.KeyVault = space.sovereigncast.hopp.platform.ICloudKeychainVault()

    /** Gone from the clipboard after a minute, and kept off the Universal Clipboard of the listener's other devices. */
    override fun copySecret(text: String, label: String) {
        UIPasteboard.generalPasteboard.setItems(
            listOf(mapOf("public.utf8-plain-text" to text)),
            mapOf(
                platform.UIKit.UIPasteboardOptionLocalOnly to true,
                platform.UIKit.UIPasteboardOptionExpirationDate to platform.Foundation.NSDate().dateByAddingTimeInterval(60.0),
            ),
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun shareText(text: String, subject: String) {
        val top = topController() ?: return
        val sheet = UIActivityViewController(listOf(text), null)
        // On the iPad the sheet is a popover and needs a place to point from.
        sheet.popoverPresentationController?.let { pop ->
            pop.sourceView = top.view
            top.view.bounds.useContents { pop.sourceRect = CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
            pop.permittedArrowDirections = 0u
        }
        top.presentViewController(sheet, animated = true, completion = null)
    }

    private fun topController(): UIViewController? {
        @Suppress("DEPRECATION")
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (true) top = top.presentedViewController ?: return top
    }
}

/** UIApplication.openNotificationSettingsURLString's value (iOS 16 and later). */
private const val NOTIFICATION_SETTINGS = "app-settings:notifications"
