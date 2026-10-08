package space.sovereigncast.hopp.ui.components

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

/** Epoch seconds → "07.10.2026" (spike; locale-aware formatting comes with ADR 0016). */
fun formatDate(epochSeconds: Long?): String {
    if (epochSeconds == null) return ""
    val days = Math.floorDivCompat(epochSeconds, 86_400)
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "${d.toString().padStart(2, '0')}.${m.toString().padStart(2, '0')}.$y"
}

private object Math {
    fun floorDivCompat(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q }
}

/** Bytes → "340 MB" / "1.2 GB" (whole megabytes are enough for downloads). */
fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    if (mb < 1024) return "${mb.toLong()} MB"
    val tenths = (mb / 1024 * 10).toLong()
    return if (tenths % 10 == 0L) "${tenths / 10} GB" else "${tenths / 10}.${tenths % 10} GB"
}
