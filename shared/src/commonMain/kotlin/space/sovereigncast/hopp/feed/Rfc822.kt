package space.sovereigncast.hopp.feed

/**
 * RSS dates (RFC 822 / 2822) → epoch seconds, tolerant the way feeds need:
 * optional weekday, one- or two-digit day, missing seconds, two-digit years,
 * named zones (GMT, UT, EST …), "+0000" or "+00:00". Returns null rather than
 * guessing when the string is not a date at all.
 */
internal object Rfc822 {
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val ZONES = mapOf(
        "gmt" to 0, "ut" to 0, "utc" to 0, "z" to 0,
        "est" to -300, "edt" to -240, "cst" to -360, "cdt" to -300,
        "mst" to -420, "mdt" to -360, "pst" to -480, "pdt" to -420,
        "cet" to 60, "cest" to 120, "bst" to 60,
    )
    private val PATTERN = Regex(
        """^(?:[A-Za-z]{2,9},?\s*)?(\d{1,2})\s+([A-Za-z]{3,9})\.?\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?\s*([+-]\d{2}:?\d{2}|[A-Za-z]{1,5})?$""",
    )

    fun parse(value: String?): Long? {
        val m = PATTERN.find(value?.trim() ?: return null) ?: return null
        val (d, mon, y, hh, mm, ss, zone) = m.destructured
        val month = MONTHS.indexOf(mon.take(3).lowercase()) + 1
        if (month == 0) return null
        var year = y.toInt()
        if (y.length == 2) year += if (year < 70) 2000 else 1900
        val offsetMinutes = when {
            zone.isEmpty() -> 0
            zone[0] == '+' || zone[0] == '-' -> {
                val digits = zone.substring(1).replace(":", "")
                val minutes = digits.take(2).toInt() * 60 + digits.drop(2).toInt()
                if (zone[0] == '-') -minutes else minutes
            }
            else -> ZONES[zone.lowercase()] ?: 0
        }
        val days = daysFromCivil(year, month, d.toInt())
        val seconds = days * 86_400L + hh.toInt() * 3600 + mm.toInt() * 60 + (ss.ifEmpty { "0" }).toInt()
        return seconds - offsetMinutes * 60L
    }

    /** Days since 1970-01-01 (Howard Hinnant's algorithm, proleptic Gregorian). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}

/** `itunes:duration`: "3600", "60:00", "1:00:00", "1:00:00.5". */
internal fun parseDurationSeconds(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = text.split(':')
    if (parts.size > 3) return null
    var total = 0L
    for (part in parts) {
        val n = part.substringBefore('.').toLongOrNull() ?: return null
        total = total * 60 + n
    }
    return total
}
