package app.stockpickers.kmp.domain

import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Single

/**
 * One ticker's written profile from the cache — offline-first, no network. The
 * companion [RefreshTickerProfileUseCase] fills the cache; this only reads it.
 *
 * Emits null for the many symbols upstream has no profile for, so the detail screen
 * simply omits the card. That is the ordinary outcome, not a failure.
 */
@Single
class ObserveTickerProfileUseCase(private val repository: TickerRepository) {
    operator fun invoke(ticker: String): Flow<TickerProfile?> = repository.observeProfile(ticker)
}
