package space.sovereigncast.hopp.platform

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Crash reports, shared by hand (ADR 0015): the platform's crash handler
 * writes the last one here, the app shows it on the next start, and only the
 * listener sends it on — nothing leaves the phone by itself. [dir] lies
 * outside the OS backup; null where the platform keeps none.
 */
class CrashReports(private val dir: String?) {
    private val file get() = dir?.let { Path(it, "last-crash.txt") }

    fun pending(): String? = file?.takeIf { SystemFileSystem.exists(it) }?.let { f ->
        runCatching { SystemFileSystem.source(f).buffered().use { it.readString() } }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun write(report: String) {
        val f = file ?: return
        runCatching {
            SystemFileSystem.createDirectories(Path(dir!!))
            SystemFileSystem.sink(f).buffered().use { it.writeString(report) }
        }
    }

    fun clear() { file?.let { runCatching { SystemFileSystem.delete(it, mustExist = false) } } }

    companion object {
        /** Long enough for the stack trace, short enough for an issue link. */
        private const val MAX_TRACE = 6_000

        /**
         * The report as the listener sees and sends it: version, platform,
         * time, thread and the stack trace — with addresses and long keys
         * blanked, so no feed, wallet connection or key goes along (ADR 0015).
         */
        fun format(app: AppInfo, systemVersion: String, isoTime: String, thread: String, error: Throwable): String = buildString {
            appendLine("Hopp crash report")
            appendLine("Version: ${app.version}")
            appendLine("System: ${app.platform} $systemVersion; ${app.deviceClass}")
            appendLine("Time: $isoTime")
            appendLine("Thread: $thread")
            appendLine()
            append(scrub(error.stackTraceToString()).take(MAX_TRACE))
        }

        private val URL = Regex("""\b(?:https?|nostr\+walletconnect|nostr|lightning|lnurl\w*|wss?)://[^\s"'<>)]+""", RegexOption.IGNORE_CASE)
        /** Invoices (amount in the prefix: lnbc210n1…), LNURLs and Nostr keys. */
        private val LN = Regex("""\b(?:ln(?:bcrt|bc|tbs|tb)[0-9]*[munp]?|lnurl|nsec|npub|nprofile|nevent)1[0-9a-z]{20,}\b""", RegexOption.IGNORE_CASE)
        private val HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")
        private val ADDRESS = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")

        internal fun scrub(text: String): String = text
            .replace(URL, "<address>")
            .replace(LN, "<key>")
            .replace(HEX, "<hex>")
            .replace(ADDRESS, "<address>")
    }
}
