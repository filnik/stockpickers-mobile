package app.stockpickers.kmp.domain

import kotlinx.coroutines.flow.Flow

/**
 * One ticker's detail, straight from the local cache — offline-first, no network.
 *
 * One action, one use case: this only reads. Refreshing the cache stays
 * [TickerRepository.refresh]'s job, driven by the leaders screen; the detail
 * screen re-renders on its own because Room re-emits after every sync.
 */
class GetTickerDetailUseCase(
    private val repository: TickerRepository,
) {
    operator fun invoke(ticker: String): Flow<TickerDetail?> = repository.observeTicker(ticker)
}
