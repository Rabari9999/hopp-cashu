package space.sovereigncast.hopp.platform

/**
 * Secrets the app holds — the NWC connection string, which can spend from the
 * listener's wallet (ADR 0005). Android Keystore / iOS Keychain; never the
 * database, never the OS backup (ADR 0015 backs up everything but keys).
 */
interface SecretStore {
    fun get(name: String): String?
    /** null removes it. */
    fun put(name: String, value: String?)
}

/** For tests and previews. */
class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun get(name: String) = values[name]
    override fun put(name: String, value: String?) { if (value == null) values -= name else values[name] = value }
}
