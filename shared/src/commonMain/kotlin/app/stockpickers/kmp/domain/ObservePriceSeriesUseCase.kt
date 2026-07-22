package app.stockpickers.kmp.domain

import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Single

/**
 * One ticker's cached price history for the detail chart AT [range] — offline-first,
 * no network. The companion [RefreshPriceSeriesUseCase] fills the cache; this only
 * reads it, and the screen re-renders when Room re-emits.
 */
@Single
class ObservePriceSeriesUseCase(private val repository: TickerRepository) {
    operator fun invoke(ticker: String, range: ChartRange): Flow<PriceSeries?> =
        repository.observePriceSeries(ticker, range)
}
