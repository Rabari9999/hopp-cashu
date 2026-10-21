package space.sovereigncast.hopp.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** [SecretStore] on iOS: the Keychain, this device only — not in iCloud
 *  Keychain and not in backups (ADR 0015). Readable after the first unlock,
 *  so streaming payments work while the screen is locked. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainSecretStore(private val service: String = "space.sovereigncast.hopp") : SecretStore {
    private fun query(name: String): CFMutableDictionaryRef? {
        val q = CFDictionaryCreateMutable(null, 6, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(q, kSecAttrService, CFBridgingRetain(service))
        CFDictionaryAddValue(q, kSecAttrAccount, CFBridgingRetain(name))
        return q
    }

    /** Not there: null. There and not readable — the Keychain refusing, or
     *  data that is no text: [SecretUnreadable] (security review 2026-10-20). */
    override fun get(name: String): String? = memScoped {
        val q = query(name)
        CFDictionaryAddValue(q, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        when (val status = SecItemCopyMatching(q, result.ptr)) {
            errSecSuccess -> Unit
            errSecItemNotFound -> return null
            else -> throw SecretUnreadable(name, IllegalStateException("SecItemCopyMatching: $status"))
        }
        val data = CFBridgingRelease(result.value) as? NSData ?: throw SecretUnreadable(name)
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString() ?: throw SecretUnreadable(name)
    }

    override fun put(name: String, value: String?) {
        if (value == null) { SecItemDelete(query(name)); return }
        get(name) // there and unreadable: refused, not replaced
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        SecItemDelete(query(name))
        val q = query(name)
        CFDictionaryAddValue(q, kSecValueData, CFBridgingRetain(data))
        CFDictionaryAddValue(q, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(q, null)
        check(status == errSecSuccess) { "SecItemAdd: $status" }
    }
}
