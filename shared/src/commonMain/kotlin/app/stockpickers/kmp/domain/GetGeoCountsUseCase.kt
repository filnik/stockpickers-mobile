package app.stockpickers.kmp.domain

import kotlinx.coroutines.flow.Flow

/**
 * Per-chip counts for the leaders board under a given ranking key.
 *
 * Separate from [GetMomentumLeadersUseCase] on purpose — one action, one use
 * case. The counts are NOT a slice of the leaders list: that list is truncated
 * to a top-N, these span the whole qualifying pool (see [GeoCounts]).
 */
class GetGeoCountsUseCase(
    private val repository: TickerRepository,
) {
    operator fun invoke(sort: LeaderSort): Flow<GeoCounts> = repository.observeGeoCounts(sort)
}
