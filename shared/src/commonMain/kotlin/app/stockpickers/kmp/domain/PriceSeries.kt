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
)
