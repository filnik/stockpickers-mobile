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

/**
 * The symbol for an ISO currency code, or null when it has no widely-read one.
 * Deliberately tiny: it covers the markets this scanner actually surfaces, and
 * anything else (TWD, SEK, …) reads better as its code than as a guessed glyph.
 */
private fun currencySymbol(code: String): String? = when (code.uppercase()) {
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    "JPY", "CNY" -> "¥"
    else -> null
}

/**
 * The live quote for the price chart. Currencies with a symbol lead with it
 * ("$150.00", "€93.20") — the fastest way to see WHICH money this is; the rest
 * trail their ISO code ("35.55 TWD"). Unlike [formatPriceEur] the currency is
 * Yahoo's (the market's own), not the scanner's EUR conversion.
 */
internal fun formatQuote(value: Double?, currency: String?): String {
    if (value == null) return "—"
    val amount = value.format(2)
    val code = currency ?: return amount
    return currencySymbol(code)?.let { "$it$amount" } ?: "$amount $code"
}

/**
 * The selected period's ABSOLUTE change, ALWAYS signed ("+$12.34", "-5.10 TWD").
 * The explicit "+" marks it as a delta over the window, not a level. The sign leads
 * the symbol (`-$3.95`, never `$-3.95`), so the magnitude is formatted unsigned.
 */
internal fun formatSignedQuote(value: Double?, currency: String?): String {
    if (value == null) return "—"
    val sign = if (value >= 0) "+" else "-"
    val amount = abs(value).format(2)
    val code = currency ?: return "$sign$amount"
    return currencySymbol(code)?.let { "$sign$it$amount" } ?: "$sign$amount $code"
}

/**
 * The selected period's change as a signed percentage from a DECIMAL FRACTION
 * (0.021 → "+2.10%", -0.05 → "-5.00%"). Same convention as [formatMomentum].
 */
internal fun formatSignedPercent(fraction: Double?): String =
    fraction?.let { (if (it >= 0) "+" else "") + (it * 100).format(2) + "%" } ?: "—"
