package space.sovereigncast.hopp.platform

/**
 * A secret is there but cannot be read right now — the platform's key store
 * did not answer, or the key it was sealed with is gone. Not "nothing
 * stored": the entry stays, and [SecretStore.put] refuses to write over it
 * (security review 2026-10-20) — a Nostr key made in its place would have
 * replaced the one that was only unreadable for the moment.
 */
class SecretUnreadable(name: String, cause: Throwable? = null) : Exception("secret '$name' cannot be read", cause)

/**
 * Secrets the app holds — the NWC connection string, which can spend from the
 * listener's wallet (ADR 0005), and the Nostr key (ADR 0021). Android Keystore /
 * iOS Keychain; never the database, never the OS backup (ADR 0015 backs up
 * everything but keys).
 */
interface SecretStore {
    /** The secret, or null when none is stored under [name]. Throws [SecretUnreadable] when one is and cannot be read. */
    fun get(name: String): String?
    /** null removes it — always possible, the way out of an unreadable entry.
     *  A value never replaces an entry that is there but unreadable: [SecretUnreadable]. */
    fun put(name: String, value: String?)
}

/** For tests and previews. */
class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    /** Names whose entries read as unreadable — a key store that does not answer, simulated. */
    val unreadable = mutableSetOf<String>()
    override fun get(name: String): String? {
        if (name in unreadable && name in values) throw SecretUnreadable(name)
        return values[name]
    }
    override fun put(name: String, value: String?) {
        if (value == null) { values -= name; unreadable -= name; return }
        if (name in unreadable && name in values) throw SecretUnreadable(name)
        values[name] = value
    }
}
