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

/**
 * A plain ratio (P/E, PEG, R²). Unitless — do NOT append '%'.
 * Mirrors the web client's `ratio()` (investing/web `analisi-titolo`).
 */
internal fun formatRatio(value: Double?, decimals: Int = 2): String =
    value?.format(decimals) ?: "—"

/**
 * ROIC is a decimal FRACTION upstream (0.18 → "18.0%"), exactly like `mom_*`.
 * Authority: `RichPickCard.tsx` renders it as `formatNumberIt(v * 100, 0)%`.
 * Unlike [formatMomentum] it carries no explicit '+' — it is a level, not a delta.
 */
internal fun formatPercent(fraction: Double?): String =
    fraction?.let { (it * 100).format(1) + "%" } ?: "—"

internal fun formatPriceEur(value: Double?): String =
    value?.let { "€" + it.format(2) } ?: "—"
