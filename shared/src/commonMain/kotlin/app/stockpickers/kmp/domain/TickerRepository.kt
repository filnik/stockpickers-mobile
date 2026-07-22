package app.stockpickers.kmp.domain

import kotlinx.coroutines.flow.Flow

/**
 * Why a refresh failed.
 *
 * The distinction is not cosmetic: only [OFFLINE] is about the user's own
 * connection. Reporting an upstream fault as "offline" sends them to check a
 * network that is working, and hides the one thing they could act on — waiting,
 * or reporting it.
 */
enum class RefreshFailure {
    /** The server was never reached: no network, DNS failure, or a timeout. */
    OFFLINE,

    /** Reached, and it refused — a 4xx/5xx from PostgREST. */
    SERVER,

    /** It answered and we could not use the answer; a schema change, most likely. */
    UNKNOWN,
}

/** Result of a refresh attempt — lets the UI distinguish "offline" from "broken". */
sealed interface RefreshResult {
    data object Success : RefreshResult
    data class Failed(val reason: RefreshFailure, val message: String) : RefreshResult
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
    fun observeMomentumLeaders(sort: LeaderSort, geo: GeoFilter = GeoFilter.ALL, limit: Int = 10): Flow<List<Ticker>>

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
     * Cached price history for [ticker]'s detail chart AT [range], from Room.
     * Offline-first and network-free like [observeTicker]; emits null until
     * [refreshPriceSeries] has landed a series for that (ticker, range) — or when the
     * symbol has no chart data for it (e.g. intraday on a thin foreign name).
     */
    fun observePriceSeries(ticker: String, range: ChartRange): Flow<PriceSeries?>

    /**
     * Fetches [ticker]'s price history for [range] from Yahoo into Room, unless that
     * (ticker, range) cache is still fresh. The TTL is range-dependent — short for
     * intraday windows, longer for daily ones — to respect Yahoo's IP rate limit.
     * Fire-and-forget: it never throws and never blanks the cache on failure.
     */
    suspend fun refreshPriceSeries(ticker: String, range: ChartRange)

    /**
     * [ticker]'s written profile from Room. Offline-first and network-free like
     * [observeTicker].
     *
     * Emits null both while uncached AND when upstream simply has no profile for the
     * symbol — the caller cannot tell them apart, and does not need to: either way
     * there is nothing to show. Most tickers fall in the second case.
     */
    fun observeProfile(ticker: String): Flow<TickerProfile?>

    /**
     * Fetches [ticker]'s profile from Supabase into Room, unless the cached copy is
     * still fresh. Fire-and-forget with the same contract as [refreshPriceSeries]: it
     * never throws and never blanks the cache on failure.
     */
    suspend fun refreshProfile(ticker: String)
}
