package app.stockpickers.kmp.domain

/**
 * Pulls [ticker]'s written profile from Supabase into the Room cache.
 * Fire-and-forget: respects a freshness TTL, never throws, and leaves the cache
 * intact on failure. As with the price series, the throttling policy lives in the
 * repository rather than the ViewModel.
 */
class RefreshTickerProfileUseCase(
    private val repository: TickerRepository,
) {
    suspend operator fun invoke(ticker: String) = repository.refreshProfile(ticker)
}
