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
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeXML
import platform.darwin.NSObject

/**
 * What the app asks of iOS (device feedback 2026-10-13: on the iPhone and the
 * iPad, the settings' "notifications" button did nothing — iOS ran without
 * any of these, so copying and sharing did nothing either).
 *
 * Not here, because iOS has no public way: VPN and network settings (the
 * privacy page shows no button for them on iOS) and a per-app language (iOS
 * sets it in Hopp's own page of the Settings app).
 */
class IosPlatformActions : PlatformActions {
    /** The picker holds its delegate weakly: kept here while it is open. */
    private var pickerDelegate: NSObject? = null

    /** The Files picker, for an OPML export another app saved (maintainer, 2026-10-21: the
     *  moving-over page's button did nothing on iOS). Read whole only up to Limits.OPML. */
    override fun pickOpml(onText: (String) -> Unit) {
        val top = topController() ?: return
        val types = listOfNotNull(UTType.typeWithIdentifier("org.opml.opml"), UTTypeXML, UTTypeData)
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                pickerDelegate = null
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                val data = NSData.dataWithContentsOfURL(url) ?: return
                if (data.length.toLong() > space.sovereigncast.hopp.net.Limits.OPML) { onText(""); return }
                onText(NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString().orEmpty())
            }
            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { pickerDelegate = null }
        }
        pickerDelegate = delegate
        picker.delegate = delegate
        top.presentViewController(picker, animated = true, completion = null)
    }

    /** As a file in the share sheet: "Save to Files", or straight to another podcast app. */
    @OptIn(ExperimentalForeignApi::class)
    override fun exportOpml(text: String) {
        val top = topController() ?: return
        val path = NSTemporaryDirectory() + "hopp-subscriptions.opml"
        @Suppress("CAST_NEVER_SUCCEEDS")
        if (!(text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)) return
        val sheet = UIActivityViewController(listOf(NSURL.fileURLWithPath(path)), null)
        sheet.popoverPresentationController?.let { pop ->
            pop.sourceView = top.view
            top.view.bounds.useContents { pop.sourceRect = CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
            pop.permittedArrowDirections = 0u
        }
        top.presentViewController(sheet, animated = true, completion = null)
    }

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
