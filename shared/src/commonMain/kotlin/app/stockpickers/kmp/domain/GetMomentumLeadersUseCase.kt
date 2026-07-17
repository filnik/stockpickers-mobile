package app.stockpickers.kmp.domain

import kotlinx.coroutines.flow.Flow

/**
 * Momentum leaders for a given window, straight from the local cache.
 *
 * The qualifying rules + ordering are NOT applied here: they live in the DAO's
 * SQL (see ScannerDao.observeMomentumLeaders) so that filtering and sorting
 * happen in SQLite over the whole cached universe rather than in memory.
 */
class GetMomentumLeadersUseCase(
    private val repository: TickerRepository,
) {
    operator fun invoke(
        window: MomentumWindow,
        limit: Int = DEFAULT_LIMIT,
    ): Flow<List<Ticker>> = repository.observeMomentumLeaders(window, limit)

    companion object {
        const val DEFAULT_LIMIT = 10
    }
}
