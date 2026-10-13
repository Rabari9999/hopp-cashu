package space.sovereigncast.hopp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIPasteboard
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

    /** Hopp's page in the Settings app, with "Notifications" at its top. The direct
     *  link to notifications needs iOS 16; Hopp runs from 15.3. */
    override fun openNotificationSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
    }

    /** iOS shows no confirmation of its own; the clipboard is the confirmation. */
    override fun copyText(text: String, label: String) {
        UIPasteboard.generalPasteboard.string = text
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
