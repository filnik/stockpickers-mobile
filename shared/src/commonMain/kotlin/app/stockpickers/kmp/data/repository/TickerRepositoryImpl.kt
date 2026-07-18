package app.stockpickers.kmp.data.repository

import app.stockpickers.kmp.data.local.GeoCountsRow
import app.stockpickers.kmp.data.local.PriceSeriesEntity
import app.stockpickers.kmp.data.local.ScannerDao
import app.stockpickers.kmp.data.local.SyncMetadataEntity
import app.stockpickers.kmp.data.local.TickerEntity
import app.stockpickers.kmp.data.remote.SupabaseScannerApi
import app.stockpickers.kmp.data.remote.TickerDto
import app.stockpickers.kmp.data.remote.YahooChartApi
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

    override fun observePriceSeries(ticker: String): Flow<PriceSeries?> =
        dao.observePriceSeries(ticker).map { entity -> entity?.toDomain(json) }

    @OptIn(ExperimentalTime::class)
    override suspend fun refreshPriceSeries(ticker: String) {
        try {
            // Freshness gate: Yahoo rate-limits by IP, so we fetch a given ticker
            // at most once per TTL and serve Room in between.
            val fetchedAt = dao.getPriceSeriesFetchedAt(ticker)
            val now = Clock.System.now().toEpochMilliseconds()
            if (fetchedAt != null && now - fetchedAt < PRICE_SERIES_TTL_MILLIS) return

            val series = chartApi.fetchChart(ticker) ?: return // no data → keep cache
            dao.upsertPriceSeries(series.toEntity(fetchedAt = now, json = json))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Offline / 429 / parse error: graceful — whatever is cached survives.
        }
    }

    private companion object {
        // ~6h freshness window: long enough to spare Yahoo repeat hits during a
        // browsing session, short enough that a daily close shows up same day.
        const val PRICE_SERIES_TTL_MILLIS = 6L * 60 * 60 * 1000
    }
}

/** Persistence mirror of [PricePoint] — keeps @Serializable out of the domain. */
@Serializable
private data class PricePointJson(val t: Long, val c: Double)

private fun PriceSeries.toEntity(fetchedAt: Long, json: Json): PriceSeriesEntity =
    PriceSeriesEntity(
        ticker = ticker,
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
