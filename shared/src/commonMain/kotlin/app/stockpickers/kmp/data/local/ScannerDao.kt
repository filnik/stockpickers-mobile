package app.stockpickers.kmp.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannerDao {

    /**
     * MOMENTUM LEADERS — SQL mirror of the authoritative upstream implementation:
     * its leaders query (the gate + the window ranking) and its filter definitions
     * (the `Forza` ranking and the geo buckets).
     *
     * !! THIS LOGIC IS OWNED BY THE UPSTREAM PIPELINE + ITS WEB CLIENT. !!
     * Do not "improve" or reinvent the thresholds here: the quality verdict,
     * the Wyckoff phase and the ADR dedup are all computed upstream and merely
     * READ by this client. If the rules change upstream, port them across —
     * they must not drift.
     *
     * ── THE COUNTRY LIST BELOW IS THE FOURTH COPY. KNOWN, ACCEPTED DEBT. ──
     * Upstream already keeps the same geo mapping in three places — its filter
     * definitions, its server-side query and the pipeline itself — with a standing
     * note to "keep the three in sync". This SQL makes
     * it FOUR. That is a deliberate trade, not an oversight: the alternative is
     * an extra network round-trip per chip to a source of truth this
     * offline-first client cannot reach while offline. The cost is that a country
     * added upstream (a new ASIA market, say) must be added HERE TOO or this app
     * silently under-reports. If a fifth copy is ever needed, that is the signal
     * to publish the buckets as data (a column / an endpoint) instead.
     *
     * Qualifying rules, in order — IDENTICAL for every sort and every chip:
     *  1. country ∈ BUCKET_COUNTRIES (US / IT / JP / KR / TW).
     *  2. the window's momentum is non-null — WINDOW SORTS ONLY. `Forza` ranks by
     *     clenow, so requiring a window it does not read would drop rows the web's
     *     `aggregate` tab shows. The CASE's `ELSE 1` (non-null) is that skip.
     *  3. clenow non-null AND > 0 (positive, regular uptrend).
     *  4. quality_gate.passes_filters === true — FAIL-SAFE: a row with a missing
     *     verdict (NULL, i.e. absent / `{}` upstream) is EXCLUDED. `qualityPasses = 1`
     *     gives exactly this: in SQL, `NULL = 1` is NULL → the row is filtered out.
     *     (Using `!= 0` would fail OPEN and leak un-evaluated rows.)
     *  5. wyckoff_markdown !== true — INVERSE fail-safe: only an explicit true
     *     excludes; NULL/absent is kept, so an old payload can't empty the board.
     *  6. duplicate_of is not a non-empty string — fail-open on NULL/absent.
     *
     * Then: ORDER BY the sort's key DESC, LIMIT :limit.
     *
     * NO MERGE PASS, unlike the web. `getMomentumLeaders` + `getCountryLeaders` +
     * `getCountryMomentumLeaders` are stitched together there for ONE reason: the
     * global list is dominated by US/Taiwan, so a server-side top-N would leave the
     * ITA/ASIA chips empty. That whole dance is a workaround for not having the
     * universe to hand. We DO have it — all ~1804 rows are in Room — so the chip
     * filter is just a WHERE, applied BEFORE the LIMIT: "top 10 Italian names" comes
     * out directly, with the same gate and the same ordering. Same semantics, one query.
     *
     * :sort and :geo are bind parameters driving CASEs, so filtering + ordering
     * run in SQLite over the full cached universe instead of in memory.
     *
     * NULL ordering: the web sinks nulls with `?? -Infinity`. SQLite sorts NULL as
     * the smallest value, so a plain `DESC` puts them last — same result, for free.
     * (Rule 2/3 already make the sort key non-null in practice; this keeps parity
     * rather than relying on that.)
     */
    @Query(
        """
        SELECT * FROM tickers
        WHERE country IN ('United States', 'Italy', 'Japan', 'South Korea', 'Taiwan')
          AND (CASE :geo
                 WHEN 'us' THEN country = 'United States'
                 WHEN 'it' THEN country = 'Italy'
                 WHEN 'asia' THEN country IN ('Japan', 'South Korea', 'Taiwan')
                 ELSE 1
               END) = 1
          AND (CASE :sort
                 WHEN '1m' THEN mom1m
                 WHEN '2m' THEN mom2m
                 WHEN '3m' THEN mom3m
                 ELSE 1
               END) IS NOT NULL
          AND clenow IS NOT NULL
          AND clenow > 0
          AND qualityPasses = 1
          AND (wyckoffMarkdown IS NULL OR wyckoffMarkdown = 0)
          AND (duplicateOf IS NULL OR duplicateOf = '')
        ORDER BY (CASE :sort
                    WHEN 'aggregate' THEN clenow
                    WHEN '1m' THEN mom1m
                    WHEN '2m' THEN mom2m
                    WHEN '3m' THEN mom3m
                  END) DESC
        LIMIT :limit
        """,
    )
    fun observeMomentumLeaders(sort: String, geo: String, limit: Int): Flow<List<TickerEntity>>

    /**
     * Chip counts for [observeMomentumLeaders]: how many rows qualify per geo
     * bucket under the given sort. Deliberately NOT limited — see [GeoCountsRow].
     *
     * The WHERE clause must stay byte-for-byte the gate of [observeMomentumLeaders]
     * minus the geo predicate, or the chips will lie about their own lists.
     */
    @Query(
        """
        SELECT COUNT(*) AS total,
               COUNT(CASE WHEN country = 'United States' THEN 1 END) AS usa,
               COUNT(CASE WHEN country = 'Italy' THEN 1 END) AS ita,
               COUNT(CASE WHEN country IN ('Japan', 'South Korea', 'Taiwan') THEN 1 END) AS asia
        FROM tickers
        WHERE country IN ('United States', 'Italy', 'Japan', 'South Korea', 'Taiwan')
          AND (CASE :sort
                 WHEN '1m' THEN mom1m
                 WHEN '2m' THEN mom2m
                 WHEN '3m' THEN mom3m
                 ELSE 1
               END) IS NOT NULL
          AND clenow IS NOT NULL
          AND clenow > 0
          AND qualityPasses = 1
          AND (wyckoffMarkdown IS NULL OR wyckoffMarkdown = 0)
          AND (duplicateOf IS NULL OR duplicateOf = '')
        """,
    )
    fun observeGeoCounts(sort: String): Flow<GeoCountsRow>

    /**
     * A single row for the detail screen, by primary key.
     *
     * Deliberately UNFILTERED: unlike [observeMomentumLeaders] this applies no
     * quality/Wyckoff/dedup rule. The detail screen is a factual read-out of what
     * the pipeline published — including a failed quality verdict, which it
     * renders rather than hides. Emits null while the row is absent from cache.
     */
    @Query("SELECT * FROM tickers WHERE ticker = :ticker LIMIT 1")
    fun observeTicker(ticker: String): Flow<TickerEntity?>

    @Upsert
    suspend fun upsertAll(tickers: List<TickerEntity>)

    @Query("DELETE FROM tickers WHERE ticker NOT IN (:keep)")
    suspend fun deleteTickersNotIn(keep: List<String>)

    /**
     * Replaces the cached universe with exactly what upstream just published.
     *
     * The publisher HARD-DELETES rows every run (delisted names, symbols that left
     * the universe), so an upsert-only sync would keep them forever and they would
     * go on qualifying for the board. Upsert-then-delete inside one transaction is
     * what makes the local copy converge on the remote one.
     *
     * Call this ONLY with a complete, successfully-fetched page set. On a partial or
     * failed fetch the caller must not call it at all: deleting against a truncated
     * list would wipe most of the cache, which is exactly the offline-first promise
     * this project makes. The transaction keeps readers from ever observing the
     * intermediate state where the new rows are in but the old ones are not yet out.
     */
    @Transaction
    suspend fun replaceAll(tickers: List<TickerEntity>) {
        upsertAll(tickers)
        deleteTickersNotIn(tickers.map { it.ticker })
    }

    @Query("SELECT COUNT(*) FROM tickers")
    suspend fun count(): Int

    /**
     * Cached price history for the detail chart, for ONE (ticker, range). Emits null
     * while that pair is uncached. [rangeKey] is `ChartRange.rangeKey` — part of the
     * composite PK, so each range is stored and read independently.
     */
    @Query("SELECT * FROM price_series WHERE ticker = :ticker AND rangeKey = :rangeKey LIMIT 1")
    fun observePriceSeries(ticker: String, rangeKey: String): Flow<PriceSeriesEntity?>

    @Upsert
    suspend fun upsertPriceSeries(series: PriceSeriesEntity)

    /**
     * Local fetch time (epoch millis) of the cached (ticker, range) series, or null
     * if that pair is uncached. Drives the range-dependent freshness gate.
     */
    @Query("SELECT fetchedAt FROM price_series WHERE ticker = :ticker AND rangeKey = :rangeKey LIMIT 1")
    suspend fun getPriceSeriesFetchedAt(ticker: String, rangeKey: String): Long?

    /**
     * One ticker's cached written profile. Emits null while uncached — and keeps
     * emitting a row whose content columns are all null once we have learned that
     * upstream has no profile for it (the tombstone). Telling those apart is the
     * repository's business, not this screen's.
     */
    @Query("SELECT * FROM ticker_profiles WHERE ticker = :ticker LIMIT 1")
    fun observeProfile(ticker: String): Flow<TickerProfileEntity?>

    @Upsert
    suspend fun upsertProfile(profile: TickerProfileEntity)

    /**
     * Local fetch time (epoch millis) of the cached profile, or null if we have never
     * asked. Non-null for tombstones too — that is exactly what stops us re-asking.
     */
    @Query("SELECT fetchedAt FROM ticker_profiles WHERE ticker = :ticker LIMIT 1")
    suspend fun getProfileFetchedAt(ticker: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSyncMetadata(metadata: SyncMetadataEntity)

    @Query("SELECT lastSyncedAt FROM sync_metadata WHERE id = ${SyncMetadataEntity.SYNC_ID}")
    fun observeLastSyncedAt(): Flow<Long?>
}

/**
 * Projection for [ScannerDao.observeGeoCounts] — a query result, not an @Entity,
 * so it adds no table and needs no schema version bump.
 *
 * The columns are `COUNT(CASE …)`, never `SUM(CASE …)`: over zero matching rows
 * SUM yields NULL (which would blow up these non-null Ints on an empty cache),
 * while COUNT yields 0.
 */
data class GeoCountsRow(val total: Int, val usa: Int, val ita: Int, val asia: Int)
