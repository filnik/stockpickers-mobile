package app.stockpickers.kmp.data.repository

import app.stockpickers.kmp.data.local.GeoCountsRow
import app.stockpickers.kmp.data.local.PriceSeriesEntity
import app.stockpickers.kmp.data.local.ScannerDao
import app.stockpickers.kmp.data.local.SyncMetadataEntity
import app.stockpickers.kmp.data.local.TickerEntity
import app.stockpickers.kmp.data.local.TickerProfileEntity
import app.stockpickers.kmp.data.remote.DescriptionsRowDto
import app.stockpickers.kmp.data.remote.SupabaseDescriptionsApi
import app.stockpickers.kmp.data.remote.nextEarningsOrNull
import app.stockpickers.kmp.data.remote.SupabaseScannerApi
import app.stockpickers.kmp.data.remote.TickerDto
import app.stockpickers.kmp.data.remote.YahooChartApi
import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.ContentFreshness
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.NextEarnings
import app.stockpickers.kmp.domain.PricePoint
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.QualityGate
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.domain.TickerProfile
import app.stockpickers.kmp.domain.TickerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class TickerRepositoryImpl(
    private val api: SupabaseScannerApi,
    private val dao: ScannerDao,
    private val chartApi: YahooChartApi,
    private val descriptionsApi: SupabaseDescriptionsApi,
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

    @OptIn(ExperimentalTime::class)
    override fun observeProfile(ticker: String): Flow<TickerProfile?> =
        dao.observeProfile(ticker).map { entity -> entity?.toDomain(json, Clock.System.now()) }

    /**
     * Unlike the price series, this ALWAYS writes a row — even when upstream has no
     * profile for the symbol. Most tickers have none, so without that tombstone the
     * common case would re-hit the network on every visit to the screen; the TTL gate
     * below can only suppress a fetch it has a `fetchedAt` for.
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun refreshProfile(ticker: String) {
        try {
            val now = Clock.System.now().toEpochMilliseconds()
            val fetchedAt = dao.getProfileFetchedAt(ticker)
            if (fetchedAt != null && now - fetchedAt < PROFILE_TTL_MILLIS) return

            val row = descriptionsApi.fetchProfile(ticker)
            // Key the row by the ticker we were ASKED for, not by the one upstream
            // echoes back (which is uppercased there): the reader looks it up with the
            // local spelling, and a case mismatch would make every row invisible.
            dao.upsertProfile(row.toEntity(localTicker = ticker, fetchedAt = now, json = json))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Offline / server error / breaking payload change: graceful — whatever is
            // cached survives, and no tombstone is written (we learned nothing).
        }
    }

    private companion object {
        // Daily ranges (1M+): ~6h — long enough to spare Yahoo repeat hits during a
        // browsing session, short enough that a daily close shows up same day.
        const val DAILY_TTL_MILLIS = 6L * 60 * 60 * 1000

        // Profiles: ~6h. This is the age of OUR COPY, not of the text — upstream
        // regenerates at most daily, so anything shorter would just re-download the
        // same paragraphs. Distinct from the ttl_days the pipeline publishes, which
        // only tells the reader whether the text itself has gone stale.
        const val PROFILE_TTL_MILLIS = 6L * 60 * 60 * 1000

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

// --- Ticker profile -------------------------------------------------------------
//
// Upstream's own TTL defaults, applied HERE rather than as defaults on the DTO: they
// are policy about how long a kind of text stays true, not a fact about the wire
// format. Keeping them out of the DTO also means it decodes correctly under any Json
// configuration instead of leaning on the shared instance staying lenient.

/** "What the company does" changes slowly. */
private const val TIMELESS_TTL_DAYS = 30

/** "Where it stands now" does not. */
private const val CURRENT_TTL_DAYS = 7

/**
 * Wire row → cache row. A null [DescriptionsRowDto] means upstream has no profile for
 * this ticker: that produces a TOMBSTONE — an all-null row whose only real content is
 * [TickerProfileEntity.fetchedAt] — so we remember having asked.
 */
private fun DescriptionsRowDto?.toEntity(
    localTicker: String,
    fetchedAt: Long,
    json: Json,
): TickerProfileEntity {
    // `next_earnings` arrives as an object on some rows and a bare string on others;
    // narrow it once, here, so nothing downstream has to know that.
    val earnings = this?.current?.nextEarningsOrNull(json)
    return TickerProfileEntity(
        ticker = localTicker,
        timelessDescription = this?.timeless?.description,
        timelessUpdatedAt = this?.timeless?.updatedAt,
        timelessTtlDays = this?.timeless?.ttlDays,
        currentDescription = this?.current?.description,
        currentUpdatedAt = this?.current?.updatedAt,
        currentTtlDays = this?.current?.ttlDays,
        prosJson = json.encodeToString(this?.current?.pro.orEmpty()),
        consJson = json.encodeToString(this?.current?.con.orEmpty()),
        earningsDate = earnings?.date,
        earningsConsensus = earnings?.consensus,
        // Upstream publishes a float as readily as an int; round once, here.
        earningsDaysAway = earnings?.daysAway?.toInt(),
        fetchedAt = fetchedAt,
    )
}

/**
 * Cache row → domain, or NULL when there is nothing worth showing.
 *
 * This is the ONE place that decides a profile is empty. Both platforms then reduce
 * to a single null check, so Compose and SwiftUI cannot drift into two different
 * notions of "blank" — which they would, given the iOS detail screen is written
 * separately in Swift. It also keeps the tombstone an implementation detail of the
 * cache: the domain never sees one.
 */
@OptIn(ExperimentalTime::class)
private fun TickerProfileEntity.toDomain(json: Json, now: Instant): TickerProfile? {
    val pros = json.decodeFromString<List<String>>(prosJson)
    val cons = json.decodeFromString<List<String>>(consJson)
    val earnings = if (earningsDate != null || earningsConsensus != null) {
        NextEarnings(
            date = earningsDate,
            // Recomputed, never replayed: upstream's countdown was correct on the day
            // it was written and drifts by a day every day after — offline, for weeks.
            // Its snapshot stands in only when there IS a date and we failed to parse
            // it; with no date at all there is nothing to anchor a countdown to, and a
            // number we cannot check is worse than no number.
            daysAway = earningsDate?.let { daysUntilOrNull(it, now) ?: earningsDaysAway },
            consensus = earningsConsensus,
        )
    } else {
        null
    }
    val hasCurrentText = !currentDescription.isNullOrBlank() || pros.isNotEmpty() || cons.isNotEmpty()
    if (timelessDescription.isNullOrBlank() && !hasCurrentText && earnings == null) return null

    return TickerProfile(
        ticker = ticker,
        timelessDescription = timelessDescription?.takeIf { it.isNotBlank() },
        currentDescription = currentDescription?.takeIf { it.isNotBlank() },
        pros = pros,
        cons = cons,
        nextEarnings = earnings,
        // A block with no text has no age worth reporting.
        timelessFreshness = if (timelessDescription.isNullOrBlank()) {
            ContentFreshness.UNKNOWN
        } else {
            freshnessOf(timelessUpdatedAt, timelessTtlDays, TIMELESS_TTL_DAYS, now)
        },
        currentFreshness = if (!hasCurrentText) {
            ContentFreshness.UNKNOWN
        } else {
            freshnessOf(currentUpdatedAt, currentTtlDays, CURRENT_TTL_DAYS, now)
        },
    )
}

/**
 * Age of the text against the TTL published with it.
 *
 * An absent or unparseable timestamp yields UNKNOWN, never FRESH — same fail-safe as
 * the quality gate: we do not assert a verdict we cannot prove.
 */
@OptIn(ExperimentalTime::class)
private fun freshnessOf(
    updatedAt: String?,
    ttlDays: Int?,
    defaultTtlDays: Int,
    now: Instant,
): ContentFreshness {
    val written = updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return ContentFreshness.UNKNOWN
    val ttl = (ttlDays ?: defaultTtlDays).coerceAtLeast(1).days
    return if (now - written <= ttl) ContentFreshness.FRESH else ContentFreshness.STALE
}

/**
 * Whole days from today to [date], in the device's time zone. Negative once the date
 * has passed — the UI decides what to do with that; this only reports.
 *
 * Accepts both a bare date ("2026-02-05") and a full timestamp, because the upstream
 * field is free-form JSON rather than a typed column. Null when it is neither.
 */
@OptIn(ExperimentalTime::class)
private fun daysUntilOrNull(date: String, now: Instant): Int? {
    val tz = TimeZone.currentSystemDefault()
    val target = runCatching { LocalDate.parse(date) }.getOrNull()
        ?: runCatching { Instant.parse(date).toLocalDateTime(tz).date }.getOrNull()
        ?: return null
    return now.toLocalDateTime(tz).date.daysUntil(target)
}

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
