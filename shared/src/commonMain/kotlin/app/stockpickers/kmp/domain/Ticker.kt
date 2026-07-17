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
