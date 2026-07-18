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
     *
     * [sort] picks the ranking key, [geo] the country chip. Both are applied in
     * SQL, over the whole cached universe, with the chip filtered BEFORE the
     * limit — so this returns the top [limit] OF THAT BUCKET.
     */
    fun observeMomentumLeaders(
        sort: LeaderSort,
        geo: GeoFilter = GeoFilter.ALL,
        limit: Int = 10,
    ): Flow<List<Ticker>>

    /** How many rows qualify per country chip under [sort]. Not limited. */
    fun observeGeoCounts(sort: LeaderSort): Flow<GeoCounts>

    /**
     * One row by ticker, from the same Room cache. Emits null when the ticker is
     * not (or no longer) cached. Like [observeMomentumLeaders], it never touches
     * the network.
     */
    fun observeTicker(ticker: String): Flow<TickerDetail?>

    /** Epoch millis of the last SUCCESSFUL sync, or null if never synced. */
    fun observeLastSyncedAt(): Flow<Long?>

    /** Fetches from Supabase and upserts into Room. Cache survives failure. */
    suspend fun refresh(): RefreshResult

    /**
     * Cached price history for [ticker]'s detail chart, from Room. Offline-first
     * and network-free like [observeTicker]; emits null until [refreshPriceSeries]
     * has landed a series (or when the symbol has no chart data).
     */
    fun observePriceSeries(ticker: String): Flow<PriceSeries?>

    /**
     * Fetches [ticker]'s price history from Yahoo into Room, unless the cache is
     * still fresh (skipped within a short TTL to respect Yahoo's IP rate limit).
     * Fire-and-forget: it never throws and never blanks the cache on failure.
     */
    suspend fun refreshPriceSeries(ticker: String)
}
