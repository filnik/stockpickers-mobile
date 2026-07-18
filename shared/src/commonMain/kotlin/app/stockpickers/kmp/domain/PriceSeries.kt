package app.stockpickers.kmp.domain

/**
 * One daily close, plotted by the detail screen's price chart.
 *
 * [epochSeconds] is Yahoo's daily timestamp in SECONDS (not millis). Kept as the
 * raw epoch so the UI owns any date formatting — same rule as `mom_*` on [Ticker].
 */
data class PricePoint(val epochSeconds: Long, val close: Double)

/**
 * A ticker's recent price history plus its latest quote, as the chart needs it.
 *
 * Sourced from Yahoo Finance's chart endpoint and cached in Room (offline-first,
 * like the scanner rows). [points] is already cleaned: timestamps whose close was
 * null upstream are dropped, so every point is drawable. An empty [points] means
 * "no chart to show" — the UI renders a graceful placeholder rather than an empty
 * canvas.
 *
 * [currency]/[last]/[previousClose] come from the response `meta` and may be null
 * for thin or delisted symbols; the UI shows "—" for a missing value.
 */
data class PriceSeries(
    val ticker: String,
    val currency: String?,
    val last: Double?,
    val previousClose: Double?,
    val points: List<PricePoint>,
) {
    /**
     * The SELECTED PERIOD's absolute change, in the quote currency, or null when
     * either bound is missing.
     *
     * Yahoo's `meta.chartPreviousClose` (mapped to [previousClose]) is the close
     * just BEFORE the requested window, so `last - previousClose` is the change over
     * exactly that window — it moves with the range (a 1D delta for `1d`, a 1Y delta
     * for `1y`). Same value the range selector's coloured figure shows; exposed here
     * so both Android (Compose) and iOS (SwiftUI, via SKIE) read one source.
     */
    val periodChange: Double?
        get() = if (last != null && previousClose != null) last - previousClose else null

    /**
     * The selected period's change as a DECIMAL FRACTION (0.05 == +5%), or null when
     * a bound is missing or [previousClose] is zero (no meaningful base). The UI
     * multiplies by 100 for display, exactly like `mom_*`.
     */
    val periodChangePercent: Double?
        get() = if (last != null && previousClose != null && previousClose != 0.0) {
            (last - previousClose) / previousClose
        } else {
            null
        }
}
