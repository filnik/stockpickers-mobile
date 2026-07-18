package app.stockpickers.kmp.fake

import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.domain.TickerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake of the domain [TickerRepository] for ViewModel tests. The real use cases
 * (`GetMomentumLeadersUseCase` etc.) are thin, so tests wire them over this fake
 * rather than mocking each — exercising VM + use-case together.
 *
 * The flows are settable ([leadersFlow], [countsFlow], [lastSyncedFlow]) so a test
 * can pre-seed the cache; [refreshResult] chooses whether the next refresh
 * succeeds or fails, and [lastLeadersQuery] records the sort/geo the VM last asked
 * for (to prove selection wiring).
 */
class FakeTickerRepository : TickerRepository {

    val leadersFlow = MutableStateFlow<List<Ticker>>(emptyList())
    val countsFlow = MutableStateFlow(GeoCounts())
    val tickerFlow = MutableStateFlow<TickerDetail?>(null)
    val priceSeriesFlow = MutableStateFlow<PriceSeries?>(null)
    val lastSyncedFlow = MutableStateFlow<Long?>(null)

    var refreshResult: RefreshResult = RefreshResult.Success
    var refreshCount = 0
        private set
    var priceSeriesRefreshCount = 0
        private set
    /** The (ticker, range) the VM last asked to refresh — proves range wiring. */
    var lastPriceSeriesRefresh: Pair<String, ChartRange>? = null
        private set
    /** The range the VM last observed the series for. */
    var lastObservedRange: ChartRange? = null
        private set
    var lastLeadersQuery: Triple<LeaderSort, GeoFilter, Int>? = null
        private set

    override fun observeMomentumLeaders(sort: LeaderSort, geo: GeoFilter, limit: Int): Flow<List<Ticker>> {
        lastLeadersQuery = Triple(sort, geo, limit)
        return leadersFlow
    }

    override fun observeGeoCounts(sort: LeaderSort): Flow<GeoCounts> = countsFlow

    override fun observeTicker(ticker: String): Flow<TickerDetail?> = tickerFlow

    override fun observeLastSyncedAt(): Flow<Long?> = lastSyncedFlow

    override suspend fun refresh(): RefreshResult {
        refreshCount++
        return refreshResult
    }

    override fun observePriceSeries(ticker: String, range: ChartRange): Flow<PriceSeries?> {
        lastObservedRange = range
        return priceSeriesFlow
    }

    override suspend fun refreshPriceSeries(ticker: String, range: ChartRange) {
        priceSeriesRefreshCount++
        lastPriceSeriesRefresh = ticker to range
    }
}
