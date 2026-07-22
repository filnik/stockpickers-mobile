package app.stockpickers.kmp.fake

import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.domain.TickerProfile
import app.stockpickers.kmp.domain.TickerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake of the domain [TickerRepository], used to DRIVE STATE in ViewModel tests.
 *
 * The real use cases are thin pass-throughs, so tests wire them over this fake
 * rather than stubbing each one — which exercises the ViewModel and its use cases
 * together. Every flow is a settable [MutableStateFlow], so standing up a world is
 * one line instead of six stubs:
 *
 * ```
 * repository.leadersFlow.value = TickerModelCreator.list(3)
 * repository.refreshResult = RefreshResult.Failed(RefreshFailure.OFFLINE, "network down")
 * ```
 *
 * It deliberately records NOTHING. Call counts and last-call arguments used to be
 * tracked here by hand — four counters and three "last call" fields — which is
 * precisely the bookkeeping a mocking library exists to delete. Wrap this in a
 * Mokkery `spy` and verify against that instead.
 */
class FakeTickerRepository : TickerRepository {

    val leadersFlow = MutableStateFlow<List<Ticker>>(emptyList())
    val countsFlow = MutableStateFlow(GeoCounts())
    val tickerFlow = MutableStateFlow<TickerDetail?>(null)
    val priceSeriesFlow = MutableStateFlow<PriceSeries?>(null)
    val profileFlow = MutableStateFlow<TickerProfile?>(null)
    val lastSyncedFlow = MutableStateFlow<Long?>(null)

    /** Chooses whether the next [refresh] reports success or failure. */
    var refreshResult: RefreshResult = RefreshResult.Success

    override fun observeMomentumLeaders(sort: LeaderSort, geo: GeoFilter, limit: Int): Flow<List<Ticker>> = leadersFlow

    override fun observeGeoCounts(sort: LeaderSort): Flow<GeoCounts> = countsFlow

    override fun observeTicker(ticker: String): Flow<TickerDetail?> = tickerFlow

    override fun observeLastSyncedAt(): Flow<Long?> = lastSyncedFlow

    override suspend fun refresh(): RefreshResult = refreshResult

    override fun observePriceSeries(ticker: String, range: ChartRange): Flow<PriceSeries?> = priceSeriesFlow

    override suspend fun refreshPriceSeries(ticker: String, range: ChartRange) = Unit

    override fun observeProfile(ticker: String): Flow<TickerProfile?> = profileFlow

    override suspend fun refreshProfile(ticker: String) = Unit
}
