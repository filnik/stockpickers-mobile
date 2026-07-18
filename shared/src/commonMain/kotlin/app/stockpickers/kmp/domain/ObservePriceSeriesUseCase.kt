package app.stockpickers.kmp.domain

import kotlinx.coroutines.flow.Flow

/**
 * One ticker's cached price history for the detail chart — offline-first, no
 * network. The companion [RefreshPriceSeriesUseCase] fills the cache; this only
 * reads it, and the screen re-renders when Room re-emits.
 */
class ObservePriceSeriesUseCase(
    private val repository: TickerRepository,
) {
    operator fun invoke(ticker: String): Flow<PriceSeries?> = repository.observePriceSeries(ticker)
}
