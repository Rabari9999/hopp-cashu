package space.sovereigncast.hopp.platform

/**
 * A place for the Nostr key that outlives the device (device feedback
 * 2026-10-16: a password-locked ncryptsec was the only backup, and Primal
 * cannot read it). Android: the password manager, through the Credential
 * Manager (Google Password Manager, Bitwarden, KeePassDX …). iOS: the iCloud
 * Keychain, synced to the listener's Apple devices. Hopp's own copy stays in
 * its device-only store either way.
 */
interface KeyVault {
    enum class Kind { PasswordManager, ICloudKeychain }
    val kind: Kind
    /** Saves [secret] (an nsec) under [account]; false when the listener cancelled or nothing took it. */
    suspend fun save(account: String, secret: String): Boolean
    /** The nsec saved last, or null — none there, or the listener cancelled. */
    suspend fun load(): String?
    /**
     * Every nsec still saved, the one [load] returns first. A password manager
     * offers one credential. The iCloud Keychain can hold an older key beside
     * the latest; this is how the existing restore reaches it.
     */
    suspend fun saved(): List<String> = listOfNotNull(load())
}
