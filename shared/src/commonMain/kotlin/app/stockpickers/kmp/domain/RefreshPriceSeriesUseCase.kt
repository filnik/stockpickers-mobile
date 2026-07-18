package app.stockpickers.kmp.domain

/**
 * Pulls [ticker]'s price history for [range] from Yahoo into the Room cache.
 * Fire-and-forget: respects a range-dependent freshness TTL, never throws, and
 * leaves the cache intact on failure. The freshness/throttling policy lives in the
 * repository, not the ViewModel.
 */
class RefreshPriceSeriesUseCase(
    private val repository: TickerRepository,
) {
    suspend operator fun invoke(ticker: String, range: ChartRange) =
        repository.refreshPriceSeries(ticker, range)
}
