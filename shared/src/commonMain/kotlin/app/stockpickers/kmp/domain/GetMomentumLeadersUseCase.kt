package app.stockpickers.kmp.domain

import kotlinx.coroutines.flow.Flow

/**
 * Leaders for a given ranking key + country chip, straight from the local cache.
 *
 * The qualifying rules, the geo filter and the ordering are NOT applied here:
 * they live in the DAO's SQL (see ScannerDao.observeMomentumLeaders) so that
 * filtering and sorting happen in SQLite over the whole cached universe rather
 * than in memory.
 */
class GetMomentumLeadersUseCase(
    private val repository: TickerRepository,
) {
    operator fun invoke(
        sort: LeaderSort,
        geo: GeoFilter = GeoFilter.ALL,
        limit: Int = DEFAULT_LIMIT,
    ): Flow<List<Ticker>> = repository.observeMomentumLeaders(sort, geo, limit)

    companion object {
        const val DEFAULT_LIMIT = 10
    }
}
