package app.stockpickers.kmp.domain

import kotlinx.coroutines.flow.Flow

/** Epoch millis of the last SUCCESSFUL sync, or null if the cache was never filled. */
class ObserveLastSyncedAtUseCase(
    private val repository: TickerRepository,
) {
    operator fun invoke(): Flow<Long?> = repository.observeLastSyncedAt()
}
