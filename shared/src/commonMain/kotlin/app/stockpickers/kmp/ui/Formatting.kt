package app.stockpickers.kmp.ui

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** commonMain has no String.format — small manual fixed-decimal formatter. */
internal fun Double.format(decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (abs(this) * factor).roundToLong()
    val whole = scaled / factor
    val frac = scaled % factor
    val sign = if (this < 0) "-" else ""
    if (decimals == 0) return "$sign$whole"
    return "$sign$whole.${frac.toString().padStart(decimals, '0')}"
}

/** `mom_*` are decimal FRACTIONS (0.55 → "+55.0%"). */
internal fun formatMomentum(fraction: Double?): String =
    fraction?.let { (if (it >= 0) "+" else "") + (it * 100).format(1) + "%" } ?: "—"

internal fun formatClenow(value: Double?): String = value?.format(2) ?: "—"

/** Coarse "x ago" label; good enough for a sync indicator. */
internal fun formatRelativeTime(epochMillis: Long, nowMillis: Long): String {
    val diff = nowMillis - epochMillis
    if (diff < 0) return "just now"
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}
