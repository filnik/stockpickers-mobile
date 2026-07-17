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
     * `investing/web/lib/queries.ts::getMomentumLeaders`.
     *
     * !! THIS LOGIC IS OWNED BY THE PYTHON PIPELINE + THE WEB CLIENT. !!
     * Do not "improve" or reinvent the thresholds here: the quality verdict,
     * the Wyckoff phase and the ADR dedup are all computed upstream and merely
     * READ by this client. If the rules change upstream, port them across —
     * they must not drift.
     *
     * Qualifying rules, in order:
     *  1. country ∈ BUCKET_COUNTRIES (US / IT / JP / KR / TW).
     *  2. the window's momentum is non-null.
     *  3. clenow non-null AND > 0 (positive, regular uptrend).
     *  4. quality_gate.passes_filters === true — FAIL-SAFE: a row with a missing
     *     verdict (NULL, i.e. absent / `{}` upstream) is EXCLUDED. `qualityPasses = 1`
     *     gives exactly this: in SQL, `NULL = 1` is NULL → the row is filtered out.
     *     (Using `!= 0` would fail OPEN and leak un-evaluated rows.)
     *  5. wyckoff_markdown !== true — INVERSE fail-safe: only an explicit true
     *     excludes; NULL/absent is kept, so an old payload can't empty the board.
     *  6. duplicate_of is not a non-empty string — fail-open on NULL/absent.
     *
     * Then: ORDER BY the window's momentum DESC, LIMIT :limit.
     *
     * The window is a bind parameter driving a CASE, so filtering + ordering run
     * in SQLite over the full cached universe instead of in memory.
     */
    @Query(
        """
        SELECT * FROM tickers
        WHERE country IN ('United States', 'Italy', 'Japan', 'South Korea', 'Taiwan')
          AND (CASE :window
                 WHEN '1m' THEN mom1m
                 WHEN '2m' THEN mom2m
                 WHEN '3m' THEN mom3m
               END) IS NOT NULL
          AND clenow IS NOT NULL
          AND clenow > 0
          AND qualityPasses = 1
          AND (wyckoffMarkdown IS NULL OR wyckoffMarkdown = 0)
          AND (duplicateOf IS NULL OR duplicateOf = '')
        ORDER BY (CASE :window
                    WHEN '1m' THEN mom1m
                    WHEN '2m' THEN mom2m
                    WHEN '3m' THEN mom3m
                  END) DESC
        LIMIT :limit
        """,
    )
    fun observeMomentumLeaders(window: String, limit: Int): Flow<List<TickerEntity>>

    @Upsert
    suspend fun upsertAll(tickers: List<TickerEntity>)

    @Query("SELECT COUNT(*) FROM tickers")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSyncMetadata(metadata: SyncMetadataEntity)

    @Query("SELECT lastSyncedAt FROM sync_metadata WHERE id = ${SyncMetadataEntity.SYNC_ID}")
    fun observeLastSyncedAt(): Flow<Long?>
}
