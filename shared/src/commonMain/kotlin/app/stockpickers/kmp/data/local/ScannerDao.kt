package app.stockpickers.kmp.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannerDao {

    /**
     * MOMENTUM LEADERS — SQL mirror of the authoritative implementation in
     * `investing/web/lib/queries.ts::getMomentumLeaders` (the gate + the window
     * ranking) and `investing/web/lib/picks-filters.ts` (the `Forza` ranking and
     * the geo buckets).
     *
     * !! THIS LOGIC IS OWNED BY THE PYTHON PIPELINE + THE WEB CLIENT. !!
     * Do not "improve" or reinvent the thresholds here: the quality verdict,
     * the Wyckoff phase and the ADR dedup are all computed upstream and merely
     * READ by this client. If the rules change upstream, port them across —
     * they must not drift.
     *
     * ── THE COUNTRY LIST BELOW IS THE FOURTH COPY. KNOWN, ACCEPTED DEBT. ──
     * `picks-filters.ts` already warns that its geo mapping mirrors Python's
     * `strategies/pe_switch.py::_PE_SWITCH_BUCKET_COUNTRIES` and the server-side
     * `queries.ts::BUCKET_COUNTRIES` — "keep the three in sync". This SQL makes
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

    @Query("SELECT COUNT(*) FROM tickers")
    suspend fun count(): Int

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
data class GeoCountsRow(
    val total: Int,
    val usa: Int,
    val ita: Int,
    val asia: Int,
)
