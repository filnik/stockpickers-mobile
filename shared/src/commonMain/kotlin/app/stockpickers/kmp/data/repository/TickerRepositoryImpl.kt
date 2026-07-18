package app.stockpickers.kmp.data.repository

import app.stockpickers.kmp.data.local.GeoCountsRow
import app.stockpickers.kmp.data.local.PriceSeriesEntity
import app.stockpickers.kmp.data.local.ScannerDao
import app.stockpickers.kmp.data.local.SyncMetadataEntity
import app.stockpickers.kmp.data.local.TickerEntity
import app.stockpickers.kmp.data.remote.SupabaseScannerApi
import app.stockpickers.kmp.data.remote.TickerDto
import app.stockpickers.kmp.data.remote.YahooChartApi
import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.PricePoint
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.QualityGate
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.domain.TickerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class TickerRepositoryImpl(
    private val api: SupabaseScannerApi,
    private val dao: ScannerDao,
    private val chartApi: YahooChartApi,
    private val json: Json,
) : TickerRepository {

    override fun observeMomentumLeaders(
        sort: LeaderSort,
        geo: GeoFilter,
        limit: Int,
    ): Flow<List<Ticker>> = dao.observeMomentumLeaders(sort.sortKey, geo.key, limit)
        .map { rows -> rows.map(TickerEntity::toDomain) }

    override fun observeGeoCounts(sort: LeaderSort): Flow<GeoCounts> =
        dao.observeGeoCounts(sort.sortKey).map(GeoCountsRow::toDomain)

    override fun observeTicker(ticker: String): Flow<TickerDetail?> =
        dao.observeTicker(ticker).map { row -> row?.toDetail() }

    override fun observeLastSyncedAt(): Flow<Long?> = dao.observeLastSyncedAt()

    @OptIn(ExperimentalTime::class)
    override suspend fun refresh(): RefreshResult = try {
        val remote = api.fetchScannerCache()
        // Upsert (never wipe-then-insert): a partial failure must not leave the
        // user staring at an empty screen.
        dao.upsertAll(remote.map(TickerDto::toEntity))
        dao.setSyncMetadata(SyncMetadataEntity(lastSyncedAt = Clock.System.now().toEpochMilliseconds()))
        RefreshResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Offline / server error: the cached rows stay on screen untouched.
        RefreshResult.Failed(e.message ?: "Unknown error")
    }

    override fun observePriceSeries(ticker: String, range: ChartRange): Flow<PriceSeries?> =
        dao.observePriceSeries(ticker, range.rangeKey).map { entity -> entity?.toDomain(json) }

    @OptIn(ExperimentalTime::class)
    override suspend fun refreshPriceSeries(ticker: String, range: ChartRange) {
        try {
            if (range.isIntraday) refreshIntraday(ticker, range) else refreshDailyGroup(ticker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Offline / 429 / parse error: graceful — whatever is cached survives.
        }
    }

    /** Intraday (1D/1W): one fetch per range, short TTL — these move all session. */
    @OptIn(ExperimentalTime::class)
    private suspend fun refreshIntraday(ticker: String, range: ChartRange) {
        val fetchedAt = dao.getPriceSeriesFetchedAt(ticker, range.rangeKey)
        val now = Clock.System.now().toEpochMilliseconds()
        if (fetchedAt != null && now - fetchedAt < INTRADAY_TTL_MILLIS) return
        // no data (e.g. intraday unavailable for the symbol) → keep whatever cache exists
        val series = chartApi.fetchChart(ticker, range.yahooRange, range.yahooInterval) ?: return
        dao.upsertPriceSeries(series.toEntity(rangeKey = range.rangeKey, fetchedAt = now, json = json))
    }

    /**
     * The four daily ranges (1M/3M/6M/1Y) all use `interval=1d`, so ONE `range=1y`
     * fetch contains them all. We fetch it once and write all four Room rows by
     * slicing to each window — so switching among daily chips is served from Room,
     * with ZERO extra network calls (and one call instead of four = kinder on
     * Yahoo's per-IP limit). Freshness is gated on the 1Y row: all four are written
     * together, so their TTL is shared.
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun refreshDailyGroup(ticker: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val fetchedAt = dao.getPriceSeriesFetchedAt(ticker, ChartRange.ONE_YEAR.rangeKey)
        if (fetchedAt != null && now - fetchedAt < DAILY_TTL_MILLIS) return

        val full = chartApi.fetchChart(ticker, range = "1y", interval = "1d") ?: return
        val pts = full.points
        if (pts.isEmpty()) return
        val lastEpoch = pts.last().epochSeconds

        for ((range, windowDays) in DAILY_WINDOWS) {
            val firstIdx = windowDays?.let { days ->
                val threshold = lastEpoch - days.toLong() * SECONDS_PER_DAY
                pts.indexOfFirst { it.epochSeconds >= threshold }.let { if (it < 0) 0 else it }
            } ?: 0
            val slice = pts.subList(firstIdx, pts.size)
            // The % baseline for a slice is the close JUST BEFORE its window; for the
            // full 1Y (firstIdx == 0) fall back to Yahoo's pre-range close.
            val prevClose = if (firstIdx > 0) pts[firstIdx - 1].close else full.previousClose
            val sliced = PriceSeries(
                ticker = ticker,
                currency = full.currency,
                last = full.last,
                previousClose = prevClose,
                points = slice,
            )
            dao.upsertPriceSeries(sliced.toEntity(rangeKey = range.rangeKey, fetchedAt = now, json = json))
        }
    }

    private companion object {
        // Daily ranges (1M+): ~6h — long enough to spare Yahoo repeat hits during a
        // browsing session, short enough that a daily close shows up same day.
        const val DAILY_TTL_MILLIS = 6L * 60 * 60 * 1000

        // Intraday ranges (1D/1W): ~5min — the series moves all session, so a short
        // window keeps it live while still shielding Yahoo from per-chip hammering.
        const val INTRADAY_TTL_MILLIS = 5L * 60 * 1000

        const val SECONDS_PER_DAY = 86_400L

        // Each daily range and the trailing-days window to slice from the 1Y series.
        // null = the whole series (1Y). A few extra days of margin absorb weekends/holidays.
        val DAILY_WINDOWS: List<Pair<ChartRange, Int?>> = listOf(
            ChartRange.ONE_MONTH to 31,
            ChartRange.THREE_MONTHS to 93,
            ChartRange.SIX_MONTHS to 186,
            ChartRange.ONE_YEAR to null,
        )
    }
}

/** Persistence mirror of [PricePoint] — keeps @Serializable out of the domain. */
@Serializable
private data class PricePointJson(val t: Long, val c: Double)

private fun PriceSeries.toEntity(rangeKey: String, fetchedAt: Long, json: Json): PriceSeriesEntity =
    PriceSeriesEntity(
        ticker = ticker,
        rangeKey = rangeKey,
        currency = currency,
        last = last,
        previousClose = previousClose,
        pointsJson = json.encodeToString(points.map { PricePointJson(it.epochSeconds, it.close) }),
        fetchedAt = fetchedAt,
    )

private fun PriceSeriesEntity.toDomain(json: Json): PriceSeries =
    PriceSeries(
        ticker = ticker,
        currency = currency,
        last = last,
        previousClose = previousClose,
        points = json.decodeFromString<List<PricePointJson>>(pointsJson)
            .map { PricePoint(epochSeconds = it.t, close = it.c) },
    )

private fun GeoCountsRow.toDomain() = GeoCounts(
    total = total,
    usa = usa,
    ita = ita,
    asia = asia,
)

private fun TickerDto.toEntity() = TickerEntity(
    ticker = ticker,
    name = name,
    country = country,
    sector = sector,
    priceEur = priceEur,
    updatedAt = updatedAt,
    clenow = clenow,
    mom1m = mom1m,
    mom2m = mom2m,
    mom3m = mom3m,
    mom12m = mom12m,
    forwardPe = forwardPe,
    peg = peg,
    roic = roic,
    r2 = r2,
    // Flatten Python's verdict. Absent `quality_gate` / absent `passes_filters`
    // both collapse to NULL, which the DAO's `qualityPasses = 1` excludes (fail-safe).
    qualityPasses = qualityGate?.passesFilters,
    qualityReason = qualityGate?.reason,
    qualityFailedFilter = qualityGate?.failedFilter,
    wyckoffMarkdown = wyckoffMarkdown,
    duplicateOf = duplicateOf,
)

private fun TickerEntity.toDetail() = TickerDetail(
    ticker = ticker,
    name = name,
    country = country,
    sector = sector,
    priceEur = priceEur,
    clenow = clenow,
    mom1m = mom1m,
    mom2m = mom2m,
    mom3m = mom3m,
    mom12m = mom12m,
    forwardPe = forwardPe,
    peg = peg,
    roic = roic,
    r2 = r2,
    // Re-inflate the verdict the DTO mapping flattened. All-null members mean the
    // pipeline published no `quality_gate` at all → surface "unknown", not a gate.
    qualityGate = if (qualityPasses == null && qualityReason == null && qualityFailedFilter == null) {
        null
    } else {
        QualityGate(
            passesFilters = qualityPasses,
            reason = qualityReason,
            failedFilter = qualityFailedFilter,
        )
    },
    updatedAt = updatedAt,
)

private fun TickerEntity.toDomain() = Ticker(
    ticker = ticker,
    name = name,
    country = country,
    sector = sector,
    priceEur = priceEur,
    clenow = clenow,
    mom1m = mom1m,
    mom2m = mom2m,
    mom3m = mom3m,
    mom12m = mom12m,
    forwardPe = forwardPe,
    peg = peg,
    roic = roic,
    r2 = r2,
)
