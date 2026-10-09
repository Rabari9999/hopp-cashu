package space.sovereigncast.hopp.ui.components

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

/** Epoch seconds → "07.10.2026", on the listener's clock (locale-aware formatting comes with ADR 0016). */
fun formatDate(epochSeconds: Long?): String {
    if (epochSeconds == null) return ""
    val t = local(epochSeconds)
    return "${two(t.day)}.${two(t.month.number)}.${t.year}"
}

/** Epoch seconds → "07.10.2026 · 14:05" — when a payment went out (device feedback 2026-10-09). */
fun formatDateTime(epochSeconds: Long): String {
    val t = local(epochSeconds)
    return "${formatDate(epochSeconds)} · ${two(t.hour)}:${two(t.minute)}"
}

/** An API's ISO time → "09.10. 23:39" on the listener's clock; as it came if it does not parse. */
@OptIn(ExperimentalTime::class)
fun formatIsoShort(iso: String): String = runCatching {
    val t = Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault())
    "${two(t.day)}.${two(t.month.number)}. ${two(t.hour)}:${two(t.minute)}"
}.getOrDefault(iso)

/** An API's ISO time as epoch seconds, or null. */
@OptIn(ExperimentalTime::class)
fun isoToEpochSeconds(iso: String): Long? = runCatching { Instant.parse(iso).epochSeconds }.getOrNull()

@OptIn(ExperimentalTime::class)
private fun local(epochSeconds: Long) = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault())
private fun two(n: Int) = n.toString().padStart(2, '0')

/** Bytes → "340 MB" / "1.2 GB" (whole megabytes are enough for downloads). */
fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    if (mb < 1024) return "${mb.toLong()} MB"
    val tenths = (mb / 1024 * 10).toLong()
    return if (tenths % 10 == 0L) "${tenths / 10} GB" else "${tenths / 10}.${tenths % 10} GB"
}

/** 2100 → "2.100" in German, "2,100" otherwise — sats are counted, not measured. */
fun formatSats(sats: Long, language: String = androidx.compose.ui.text.intl.Locale.current.language): String {
    val sep = if (language == "de") '.' else ','
    val digits = sats.toString().removePrefix("-")
    val grouped = digits.reversed().chunked(3).joinToString(sep.toString()).reversed()
    return if (sats < 0) "-$grouped" else grouped
}

/** Millisats as sats with up to three decimals — routing fees are often below one sat. */
fun formatMsat(msat: Long, language: String = androidx.compose.ui.text.intl.Locale.current.language): String {
    val whole = formatSats(msat / 1000, language)
    val frac = (msat % 1000).toString().padStart(3, '0').trimEnd('0')
    return if (frac.isEmpty()) whole else whole + (if (language == "de") "," else ".") + frac
}
