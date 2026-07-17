package app.stockpickers.kmp.data.local

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.stockpickers.kmp.modelcreators.TickerEntityModelCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The single most important test in the suite: it runs the REAL DAO SQL against a
 * real (in-memory) SQLite engine, so the qualifying gate, the geo buckets and the
 * ranking keys are verified as they actually execute — not paraphrased in a fake.
 *
 * JVM-only (androidHostTest) because it needs a platform SQLite driver. Room's
 * context-less `inMemoryDatabaseBuilder` + [BundledSQLiteDriver] keeps it fast and
 * Robolectric-free. The rules under test are OWNED UPSTREAM (Python pipeline / web
 * client) and mirrored in `ScannerDao.observeMomentumLeaders` — see that KDoc.
 */
class ScannerDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ScannerDao

    private val base get() = TickerEntityModelCreator.model

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        dao = db.scannerDao()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun seed(rows: List<TickerEntity>) = runBlocking { dao.upsertAll(rows) }

    private fun leaders(sort: String, geo: String = "all", limit: Int = 50): List<String> =
        runBlocking { dao.observeMomentumLeaders(sort, geo, limit).first().map { it.ticker } }

    private fun counts(sort: String): GeoCountsRow =
        runBlocking { dao.observeGeoCounts(sort).first() }

    // ---- ranking ---------------------------------------------------------

    @Test
    fun WHEN_sort_is_Forza_THEN_it_orders_by_clenow_desc_and_keeps_window_null_rows() {
        seed(
            listOf(
                base.copy(ticker = "HI", clenow = 3.0),
                base.copy(ticker = "LO", clenow = 1.0),
                // No momentum window at all — aggregate ranks by clenow, so it must
                // NOT require a window (rule 2 is window-sorts only).
                base.copy(ticker = "MID", clenow = 2.0, mom1m = null, mom2m = null, mom3m = null),
            ),
        )
        assertEquals(listOf("HI", "MID", "LO"), leaders("aggregate"))
    }

    @Test
    fun WHEN_sort_is_1M_THEN_it_orders_by_mom1m_desc_and_excludes_window_null_rows() {
        seed(
            listOf(
                base.copy(ticker = "A", mom1m = 0.30),
                base.copy(ticker = "B", mom1m = 0.50),
                base.copy(ticker = "C", mom1m = null), // window sort → excluded
            ),
        )
        assertEquals(listOf("B", "A"), leaders("1m"))
    }

    @Test
    fun WHEN_sort_is_3M_THEN_it_orders_by_mom3m_desc() {
        seed(
            listOf(
                base.copy(ticker = "A", mom3m = 0.10),
                base.copy(ticker = "B", mom3m = 0.90),
                base.copy(ticker = "C", mom3m = 0.50),
            ),
        )
        assertEquals(listOf("B", "C", "A"), leaders("3m"))
    }

    @Test
    fun WHEN_limit_is_given_THEN_only_the_top_N_are_returned() {
        // list(5): clenow 5,4,3,2,1 for T0..T4 → aggregate order T0..T4.
        seed(TickerEntityModelCreator.list(5))
        assertEquals(listOf("T0", "T1"), leaders("aggregate", limit = 2))
    }

    // ---- quality gate (fail-safe) ---------------------------------------

    @Test
    fun WHEN_quality_verdict_is_missing_or_false_THEN_the_row_is_excluded() {
        seed(
            listOf(
                base.copy(ticker = "PASS", qualityPasses = true),
                base.copy(ticker = "UNKNOWN", qualityPasses = null), // fail-safe: NULL = 1 is NULL
                base.copy(ticker = "REJECTED", qualityPasses = false),
            ),
        )
        assertEquals(listOf("PASS"), leaders("aggregate"))
    }

    @Test
    fun WHEN_wyckoff_markdown_is_true_THEN_the_row_is_excluded_but_null_is_kept() {
        seed(
            listOf(
                base.copy(ticker = "KEEP_FALSE", wyckoffMarkdown = false),
                base.copy(ticker = "KEEP_NULL", wyckoffMarkdown = null), // inverse fail-safe: kept
                base.copy(ticker = "DROP_TRUE", wyckoffMarkdown = true),
            ),
        )
        assertEquals(setOf("KEEP_FALSE", "KEEP_NULL"), leaders("aggregate").toSet())
    }

    @Test
    fun WHEN_duplicate_of_is_non_empty_THEN_the_row_is_excluded_but_empty_or_null_is_kept() {
        seed(
            listOf(
                base.copy(ticker = "KEEP_NULL", duplicateOf = null),
                base.copy(ticker = "KEEP_EMPTY", duplicateOf = ""),
                base.copy(ticker = "DROP_ADR", duplicateOf = "AAPL"),
            ),
        )
        assertEquals(setOf("KEEP_NULL", "KEEP_EMPTY"), leaders("aggregate").toSet())
    }

    @Test
    fun WHEN_clenow_is_not_positive_or_missing_THEN_the_row_is_excluded() {
        seed(
            listOf(
                base.copy(ticker = "POS", clenow = 1.0),
                base.copy(ticker = "ZERO", clenow = 0.0),
                base.copy(ticker = "NEG", clenow = -1.0),
                base.copy(ticker = "NULLCL", clenow = null),
            ),
        )
        assertEquals(listOf("POS"), leaders("aggregate"))
    }

    // ---- geo buckets -----------------------------------------------------

    private fun seedAllCountries() = seed(
        listOf(
            base.copy(ticker = "US1", country = "United States"),
            base.copy(ticker = "IT1", country = "Italy"),
            base.copy(ticker = "JP1", country = "Japan"),
            base.copy(ticker = "KR1", country = "South Korea"),
            base.copy(ticker = "TW1", country = "Taiwan"),
            base.copy(ticker = "FR1", country = "France"), // not in any bucket
            base.copy(ticker = "NULLC", country = null),    // not in any bucket
        ),
    )

    @Test
    fun WHEN_geo_is_a_bucket_THEN_only_that_bucket_countries_are_returned() {
        seedAllCountries()
        assertEquals(setOf("US1"), leaders("aggregate", "us").toSet())
        assertEquals(setOf("IT1"), leaders("aggregate", "it").toSet())
        assertEquals(setOf("JP1", "KR1", "TW1"), leaders("aggregate", "asia").toSet())
    }

    @Test
    fun WHEN_geo_is_all_THEN_the_whole_bucket_universe_is_returned_but_non_bucket_countries_are_excluded() {
        seedAllCountries()
        val all = leaders("aggregate", "all").toSet()
        assertEquals(setOf("US1", "IT1", "JP1", "KR1", "TW1"), all)
        assertFalse("FR1" in all)
        assertFalse("NULLC" in all)
    }

    // ---- geo counts ------------------------------------------------------

    @Test
    fun WHEN_counting_THEN_the_pool_matches_per_bucket_and_sums_to_total() {
        seedAllCountries()
        val c = counts("aggregate")
        assertEquals(5, c.total)
        assertEquals(1, c.usa)
        assertEquals(1, c.ita)
        assertEquals(3, c.asia)
        assertEquals(c.total, c.usa + c.ita + c.asia) // partition invariant (see GeoCounts)
    }

    @Test
    fun WHEN_the_cache_is_empty_THEN_geo_counts_are_zero_not_null() {
        // COUNT(CASE ...) over zero rows must yield 0, not NULL (which would blow up
        // the non-null Ints of GeoCountsRow). This is why the DAO uses COUNT, not SUM.
        assertEquals(GeoCountsRow(total = 0, usa = 0, ita = 0, asia = 0), counts("aggregate"))
    }

    @Test
    fun WHEN_sort_is_a_window_THEN_geo_counts_apply_the_same_gate_as_the_list() {
        seed(
            listOf(
                base.copy(ticker = "US_OK", country = "United States", mom1m = 0.2),
                base.copy(ticker = "US_NULL", country = "United States", mom1m = null), // excluded under 1m
                base.copy(ticker = "IT_OK", country = "Italy", mom1m = 0.1),
            ),
        )
        val c = counts("1m")
        assertEquals(2, c.total) // US_NULL dropped by the window gate, in counts too
        assertEquals(1, c.usa)
        assertEquals(1, c.ita)
        assertTrue(0 == c.asia)
    }
}
