package app.stockpickers.kmp.domain

/**
 * The time windows the detail price chart can show — a TradingView-style range
 * selector (1D · 1W · 1M · 3M · 6M · 1Y).
 *
 * Each entry carries the two Yahoo query knobs it maps to: [yahooRange] (the
 * `range` param) and [yahooInterval] (the candle size, the `interval` param).
 * Short windows use intraday candles; a month or more uses daily closes — mirror
 * of what TradingView / Yahoo's own chart pick per range.
 *
 * [label] is a domain symbol (never translated, exactly like [MomentumWindow.label]).
 *
 * [rangeKey] is the STABLE cache key: it is part of the Room `price_series` composite
 * primary key, so each (ticker, range) pair is cached independently. It is the enum
 * name — invariant across relabels of [label] / re-mappings of [yahooRange].
 *
 * [isIntraday] tells the repository which freshness TTL to apply: intraday windows
 * move all day and are cached briefly; daily windows barely change intraday and are
 * cached for hours. See `TickerRepositoryImpl.refreshPriceSeries`.
 */
enum class ChartRange(
    val label: String,
    val yahooRange: String,
    val yahooInterval: String,
) {
    ONE_DAY("1D", "1d", "5m"),
    ONE_WEEK("1W", "5d", "30m"),
    ONE_MONTH("1M", "1mo", "1d"),
    THREE_MONTHS("3M", "3mo", "1d"),
    SIX_MONTHS("6M", "6mo", "1d"),
    ONE_YEAR("1Y", "1y", "1d");

    /** Stable per-range cache key for the Room composite PK (invariant of the label). */
    val rangeKey: String get() = name

    /** Intraday candles (< 1 day) move all session → a short cache TTL applies. */
    val isIntraday: Boolean get() = yahooInterval != "1d"

    companion object {
        /** The window the chart opens on — the pre-selector behaviour (6 months). */
        val DEFAULT: ChartRange = SIX_MONTHS
    }
}
