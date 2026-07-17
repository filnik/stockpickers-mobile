package app.stockpickers.kmp.domain

import kotlinx.coroutines.flow.Flow

/** Result of a refresh attempt — lets the UI distinguish "offline" from "broken". */
sealed interface RefreshResult {
    data object Success : RefreshResult
    data class Failed(val message: String) : RefreshResult
}

interface TickerRepository {
    /**
     * Offline-first: this Flow is backed by Room and is the ONLY source the UI
     * observes. It emits cached data immediately and re-emits after every
     * successful sync. It never touches the network.
     */
    fun observeMomentumLeaders(window: MomentumWindow, limit: Int = 10): Flow<List<Ticker>>

    /** Epoch millis of the last SUCCESSFUL sync, or null if never synced. */
    fun observeLastSyncedAt(): Flow<Long?>

    /** Fetches from Supabase and upserts into Room. Cache survives failure. */
    suspend fun refresh(): RefreshResult
}
