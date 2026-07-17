package app.stockpickers.kmp.domain

/**
 * A single scanner row, as the UI cares about it.
 *
 * `momentum` values are DECIMAL FRACTIONS (0.55 == +55%), matching the Python
 * pipeline / web contract. Never pre-multiply by 100 here — formatting is the
 * UI's job.
 */
data class Ticker(
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
) {
    fun momentumFor(window: MomentumWindow): Double? = when (window) {
        MomentumWindow.ONE_MONTH -> mom1m
        MomentumWindow.TWO_MONTHS -> mom2m
        MomentumWindow.THREE_MONTHS -> mom3m
    }
}

/** The three momentum windows the leaders board exposes. */
enum class MomentumWindow(val apiKey: String, val label: String) {
    ONE_MONTH("1m", "1M"),
    TWO_MONTHS("2m", "2M"),
    THREE_MONTHS("3m", "3M"),
}

/**
 * The leaders board's ranking key — mirror of the web's `AcceleratingSort` +
 * `SORT_KEY` (`investing/web/lib/picks-filters.ts`), whose tabs are
 * `Forza · 1M · 2M · 3M` (`components/picks/AcceleratingTabs.tsx`).
 *
 * [STRENGTH] ("Forza", the web's `aggregate`) ranks by `clenow` — the global
 * trend-strength leader — and is the default, first tab. The other three rank by
 * their momentum window. The quality gate is IDENTICAL for every sort, so
 * changing the ranking key can never surface a name the gate wouldn't admit.
 *
 * [window] is null exactly for [STRENGTH]: it is what tells the UI to show the
 * clenow score rather than a momentum reading, and the DAO to skip the
 * window-presence check (there is no window to require).
 */
enum class LeaderSort(val sortKey: String, val label: String, val window: MomentumWindow?) {
    STRENGTH("aggregate", "Forza", null),
    ONE_MONTH(MomentumWindow.ONE_MONTH.apiKey, "1M", MomentumWindow.ONE_MONTH),
    TWO_MONTHS(MomentumWindow.TWO_MONTHS.apiKey, "2M", MomentumWindow.TWO_MONTHS),
    THREE_MONTHS(MomentumWindow.THREE_MONTHS.apiKey, "3M", MomentumWindow.THREE_MONTHS),
}
