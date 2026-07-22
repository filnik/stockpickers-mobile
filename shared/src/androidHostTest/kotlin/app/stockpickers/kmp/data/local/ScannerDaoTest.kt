package app.stockpickers.kmp.data.local

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.stockpickers.kmp.modelcreators.TickerEntityModelCreator
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

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

    private fun leaders(sort: String, geo: String = "all", limit: Int = 50): List<String> = runBlocking {
        dao.observeMomentumLeaders(sort, geo, limit).first().map { it.ticker }
    }

    private fun counts(sort: String): GeoCountsRow = runBlocking { dao.observeGeoCounts(sort).first() }

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
        leaders("aggregate") shouldContainExactly listOf("HI", "MID", "LO")
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
        leaders("1m") shouldContainExactly listOf("B", "A")
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
        leaders("3m") shouldContainExactly listOf("B", "C", "A")
    }

    @Test
    fun WHEN_limit_is_given_THEN_only_the_top_N_are_returned() {
        // list(5): clenow 5,4,3,2,1 for T0..T4 → aggregate order T0..T4.
        seed(TickerEntityModelCreator.list(5))
        leaders("aggregate", limit = 2) shouldContainExactly listOf("T0", "T1")
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
        leaders("aggregate") shouldContainExactly listOf("PASS")
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
        leaders("aggregate").toSet() shouldBe setOf("KEEP_FALSE", "KEEP_NULL")
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
        leaders("aggregate").toSet() shouldBe setOf("KEEP_NULL", "KEEP_EMPTY")
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
        leaders("aggregate") shouldContainExactly listOf("POS")
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
            base.copy(ticker = "NULLC", country = null), // not in any bucket
        ),
    )

    @Test
    fun WHEN_geo_is_a_bucket_THEN_only_that_bucket_countries_are_returned() {
        seedAllCountries()
        leaders("aggregate", "us").toSet() shouldBe setOf("US1")
        leaders("aggregate", "it").toSet() shouldBe setOf("IT1")
        leaders("aggregate", "asia").toSet() shouldBe setOf("JP1", "KR1", "TW1")
    }

    @Test
    fun WHEN_geo_is_all_THEN_the_whole_bucket_universe_is_returned_but_non_bucket_countries_are_excluded() {
        seedAllCountries()
        val all = leaders("aggregate", "all").toSet()
        all shouldBe setOf("US1", "IT1", "JP1", "KR1", "TW1")
        all shouldNotContain "FR1"
        all shouldNotContain "NULLC"
    }

    // ---- geo counts ------------------------------------------------------

    @Test
    fun WHEN_counting_THEN_the_pool_matches_per_bucket_and_sums_to_total() {
        seedAllCountries()
        val c = counts("aggregate")
        c.total shouldBe 5
        c.usa shouldBe 1
        c.ita shouldBe 1
        c.asia shouldBe 3
        (c.usa + c.ita + c.asia) shouldBe c.total // partition invariant (see GeoCounts)
    }

    @Test
    fun WHEN_the_cache_is_empty_THEN_geo_counts_are_zero_not_null() {
        // COUNT(CASE ...) over zero rows must yield 0, not NULL (which would blow up
        // the non-null Ints of GeoCountsRow). This is why the DAO uses COUNT, not SUM.
        counts("aggregate") shouldBe GeoCountsRow(total = 0, usa = 0, ita = 0, asia = 0)
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
        c.total shouldBe 2 // US_NULL dropped by the window gate, in counts too
        c.usa shouldBe 1
        c.ita shouldBe 1
        c.asia shouldBe 0
    }

    // ---- ticker profiles -------------------------------------------------

    private fun profile(ticker: String, description: String?, fetchedAt: Long = 1_000L) = TickerProfileEntity(
        ticker = ticker,
        timelessDescription = description,
        timelessUpdatedAt = null,
        timelessTtlDays = null,
        currentDescription = null,
        currentUpdatedAt = null,
        currentTtlDays = null,
        prosJson = "[]",
        consJson = "[]",
        earningsDate = null,
        earningsConsensus = null,
        earningsDaysAway = null,
        fetchedAt = fetchedAt,
    )

    @Test
    fun WHEN_a_profile_is_upserted_THEN_it_round_trips_through_real_sqlite() = runBlocking<Unit> {
        dao.upsertProfile(profile("DAVE", "Makes chips."))

        val stored = dao.observeProfile("DAVE").first()
        stored!!.timelessDescription shouldBe "Makes chips."
        dao.getProfileFetchedAt("DAVE") shouldBe 1_000L
    }

    @Test
    fun WHEN_a_ticker_has_no_profile_row_THEN_both_reads_answer_null() = runBlocking<Unit> {
        dao.observeProfile("NOPE").first().shouldBeNull()
        // Null here is what tells the repository it has never asked — as opposed to a
        // tombstone, which carries a fetchedAt and suppresses the refetch.
        dao.getProfileFetchedAt("NOPE").shouldBeNull()
    }

    // NOTE: there is deliberately NO test that upserts the same primary key twice —
    // for ANY entity, not just this one. `@Upsert` INSERTs and, on a uniqueness
    // violation, falls back to UPDATE; Room decides which it is by string-matching the
    // exception message for "unique" / 1555 / 2067. This source set runs with
    // `isReturnDefaultValues = true` (shared/build.gradle.kts), which stubs the
    // android.jar constructor of `android.database.SQLException`, so the message never
    // reaches the object and `getMessage()` returns null. Room then re-throws instead
    // of updating. Verified directly: `SQLException("...").message` is null here.
    //
    // It is an artefact of THIS environment, not a defect: on a device the real
    // constructor keeps the message, and on iOS the exception is a Kotlin class with
    // no android.jar involved. Do not "fix" the DAO because of a failure here.

    @Test
    fun WHEN_a_tombstone_is_stored_THEN_it_is_a_real_row_with_no_content() = runBlocking<Unit> {
        dao.upsertProfile(profile("EMPTY", description = null, fetchedAt = 5_000L))

        val stored = dao.observeProfile("EMPTY").first()
        stored.shouldNotBeNull() // the row EXISTS...
        stored.timelessDescription.shouldBeNull() // ...and says upstream had nothing
        dao.getProfileFetchedAt("EMPTY") shouldBe 5_000L // which is what gates the refetch
    }
}
