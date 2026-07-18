package app.stockpickers.kmp.domain

/**
 * Pulls [ticker]'s price history from Yahoo into the Room cache. Fire-and-forget:
 * respects a freshness TTL, never throws, and leaves the cache intact on failure.
 * The freshness/throttling policy lives in the repository, not the ViewModel.
 */
class RefreshPriceSeriesUseCase(
    private val repository: TickerRepository,
) {
    suspend operator fun invoke(ticker: String) = repository.refreshPriceSeries(ticker)
}
