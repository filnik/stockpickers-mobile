package app.stockpickers.kmp.domain

/**
 * The full picture of one scanner row, for the detail screen.
 *
 * Separate from [Ticker] on purpose: [Ticker] is the lean projection the leaders
 * board needs, and adding detail-only fields to it would push nullable noise into
 * every list row. Same source table, different read model.
 *
 * As in [Ticker], `mom*` are DECIMAL FRACTIONS (0.55 == +55%) — formatting is the
 * UI's job.
 */
data class TickerDetail(
    val ticker: String,
    val name: String?,
    val country: String?,
    val sector: String?,
    val priceEur: Double?,
    val clenow: Double?,
    val mom1m: Double?,
    val mom2m: Double?,
    val mom3m: Double?,
    val mom12m: Double?,
    val forwardPe: Double?,
    val peg: Double?,
    val roic: Double?,
    val r2: Double?,
    val qualityGate: QualityGate?,
    /** ISO-8601 timestamp published by the pipeline, not the local sync clock. */
    val updatedAt: String?,
)

/**
 * The Python pipeline's verdict on a row, read verbatim.
 *
 * [passesFilters] is nullable because upstream leaves `quality_gate` absent (or
 * `{}`) on rows it has not evaluated yet. Null means UNKNOWN, never "passed" —
 * mirror the DAO's fail-safe reading and never coerce it to false/true.
 */
data class QualityGate(
    val passesFilters: Boolean?,
    /** Human-readable explanation, present mostly on rejections. */
    val reason: String?,
    /** Which named filter rejected the row, when upstream says. */
    val failedFilter: String?,
)
