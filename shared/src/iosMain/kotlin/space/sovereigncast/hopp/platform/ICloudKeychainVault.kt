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
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrLabel
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * The Nostr key in the iCloud Keychain: a synchronizable item, so it reaches
 * the listener's other Apple devices and a new iPhone, end-to-end encrypted
 * by Apple — when iCloud Keychain is on. Hopp's working copy stays in its
 * device-only item ([KeychainSecretStore]); this one is the backup.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class ICloudKeychainVault : KeyVault {
    override val kind = KeyVault.Kind.ICloudKeychain

    private fun query(account: String?): CFMutableDictionaryRef? {
        val q = CFDictionaryCreateMutable(null, 8, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(q, kSecAttrService, CFBridgingRetain(SERVICE))
        CFDictionaryAddValue(q, kSecAttrSynchronizable, kCFBooleanTrue)
        account?.let { CFDictionaryAddValue(q, kSecAttrAccount, CFBridgingRetain(it)) }
        return q
    }

    // One item per key: a backup replaces only the same key's earlier copy, so
    // backing up a new identity leaves the last one's backup in place — deleting
    // by service alone took every Hopp key off every device (code review
    // 2026-10-17, H3). A second item names the key backed up last, which a
    // restore takes.
    override suspend fun save(account: String, secret: String): Boolean =
        write(account, secret) && write(LATEST, account)

    override suspend fun load(): String? {
        // Backups from before the LATEST item: the one key there was.
        val nsec = read(LATEST)?.let { read(it) } ?: read(null)
        return nsec?.takeIf { it.startsWith("nsec1") }
    }

    private fun write(account: String, value: String): Boolean {
        SecItemDelete(query(account))
        val q = query(account)
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
        CFDictionaryAddValue(q, kSecValueData, CFBridgingRetain(data))
        CFDictionaryAddValue(q, kSecAttrLabel, CFBridgingRetain("Hopp · Nostr"))
        // Synchronizable items cannot be "this device only".
        CFDictionaryAddValue(q, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        return SecItemAdd(q, null) == errSecSuccess
    }

    private fun read(account: String?): String? = memScoped {
        val q = query(account)
        CFDictionaryAddValue(q, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        if (SecItemCopyMatching(q, result.ptr) != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    private companion object {
        const val SERVICE = "space.sovereigncast.hopp.nostr-backup"
        /** The account of the item that holds the npub backed up last — never an npub itself. */
        const val LATEST = "latest"
    }
}
