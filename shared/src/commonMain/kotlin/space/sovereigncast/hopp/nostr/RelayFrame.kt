package space.sovereigncast.hopp.nostr

/**
 * The head of a relay message (NIP-01): its type and its second element — the subscription of an
 * `EVENT`, `EOSE` or `CLOSED`, the event id of an `OK` — read as JSON reads them, whitespace and
 * escapes included, without reading the rest. On a shared connection ([withRelayPool]) it tells a
 * request's own answers from the others' before a large message is read (delta audit 6 2026-10-24,
 * RP-02: a search for the subscription in the first 100 characters passed over answers padded with
 * spaces or written with escapes, and the EOSE after them made an empty read look complete).
 */
internal object RelayFrame {
    /** Longer than any type or id Hopp asks for: such a head is not an answer to it. */
    private const val LONGEST = 128

    /** Type and second element; null when the message does not start as `["type", "string"` does. */
    fun head(text: String): Pair<String, String>? {
        var i = skip(text, 0)
        if (i >= text.length || text[i] != '[') return null
        val type = string(text, skip(text, i + 1)) ?: return null
        i = skip(text, type.second)
        if (i >= text.length || text[i] != ',') return null
        val second = string(text, skip(text, i + 1)) ?: return null
        return type.first to second.first
    }

    private fun skip(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\n' || text[i] == '\r' || text[i] == '\t')) i++
        return i
    }

    /** The JSON string at [from], and where it ends; null for anything else, or one longer than [LONGEST]. */
    private fun string(text: String, from: Int): Pair<String, Int>? {
        if (from >= text.length || text[from] != '"') return null
        val out = StringBuilder()
        var i = from + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> return out.toString() to i + 1
                c == '\\' -> {
                    if (i + 1 >= text.length) return null
                    when (val e = text[i + 1]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 6 > text.length) return null
                            out.append(text.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: return null)
                            i += 4
                        }
                        else -> return null
                    }
                    i += 2
                }
                c < ' ' -> return null
                else -> { out.append(c); i++ }
            }
            if (out.length > LONGEST) return null
        }
        return null
    }
}
