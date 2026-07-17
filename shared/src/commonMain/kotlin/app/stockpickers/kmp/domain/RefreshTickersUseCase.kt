package app.stockpickers.kmp.domain

/**
 * Pulls the scanner universe from Supabase into the Room cache.
 *
 * A thin pass-through today, and that is fine: the point is that the ViewModel
 * depends on ONE action rather than on the whole repository surface. Retry or
 * throttling policy, if it ever arrives, lands here — not in the ViewModel.
 */
class RefreshTickersUseCase(
    private val repository: TickerRepository,
) {
    suspend operator fun invoke(): RefreshResult = repository.refresh()
}
