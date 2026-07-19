package app.stockpickers.kmp.repository

import app.cash.turbine.test
import app.stockpickers.kmp.data.remote.SupabaseDescriptionsApi
import app.stockpickers.kmp.data.remote.SupabaseScannerApi
import app.stockpickers.kmp.data.remote.TickerDto
import app.stockpickers.kmp.data.remote.YahooChartApi
import app.stockpickers.kmp.data.repository.TickerRepositoryImpl
import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.ContentFreshness
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.fake.FakeScannerDao
import app.stockpickers.kmp.modelcreators.TickerDtoModelCreator
import app.stockpickers.kmp.modelcreators.TickerEntityModelCreator
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * Offline-first, stale-while-revalidate contract of [TickerRepositoryImpl]:
 * the UI observes Room only, `refresh()` writes INTO Room, and a failed refresh
 * leaves the cache on screen untouched.
 *
 * The "fake API" is the real [SupabaseScannerApi] over a Ktor [MockEngine] — the
 * least-invasive way to fake a final class, and a bonus check of the real DTO
 * deserialization + pagination.
 */
class TickerRepositoryImplTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun successApi(rows: List<TickerDto>): SupabaseScannerApi {
        val engine = MockEngine {
            respond(
                content = json.encodeToString(rows),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return SupabaseScannerApi(
            client = HttpClient(engine) { install(ContentNegotiation) { json(json) } },
            baseUrl = "https://test.local",
            anonKey = "anon",
        )
    }

    private fun failingApi(): SupabaseScannerApi {
        val engine = MockEngine { throw RuntimeException("network down") }
        return SupabaseScannerApi(
            client = HttpClient(engine) { install(ContentNegotiation) { json(json) } },
            baseUrl = "https://test.local",
            anonKey = "anon",
        )
    }

    // These tests exercise the scanner cache, not the chart. A chart API whose
    // every request 404s keeps the constructor happy without touching Yahoo.
    private fun unusedChartApi(): YahooChartApi {
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }
        return YahooChartApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } })
    }

    /**
     * A descriptions API over a MockEngine that replies with [body] and counts calls.
     * [body] is the raw PostgREST array, so a test can hand over `[]` (no such
     * profile) or a row with deliberately malformed blocks.
     */
    private fun descriptionsApi(body: String, onRequest: () -> Unit = {}): SupabaseDescriptionsApi {
        val engine = MockEngine {
            onRequest()
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return SupabaseDescriptionsApi(
            client = HttpClient(engine) { install(ContentNegotiation) { json(json) } },
            baseUrl = "https://test.local",
            anonKey = "anon",
        )
    }

    private fun repository(
        api: SupabaseScannerApi,
        dao: FakeScannerDao,
        descriptionsApi: SupabaseDescriptionsApi = descriptionsApi("[]"),
    ) = TickerRepositoryImpl(
        api = api,
        dao = dao,
        chartApi = unusedChartApi(),
        descriptionsApi = descriptionsApi,
        json = json,
    )

    @Test
    fun WHEN_refresh_succeeds_THEN_rows_are_upserted_and_observable_from_the_dao() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(successApi(TickerDtoModelCreator.list(2)), dao)

        repository.observeMomentumLeaders(LeaderSort.STRENGTH).test {
            assertEquals(emptyList(), awaitItem()) // cache starts empty

            val result = repository.refresh()
            assertTrue(result is RefreshResult.Success)

            assertEquals(2, awaitItem().size) // the refresh landed in Room, UI re-rendered
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun WHEN_refresh_succeeds_THEN_last_synced_at_is_recorded() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(successApi(TickerDtoModelCreator.list(1)), dao)

        assertEquals(RefreshResult.Success, repository.refresh())

        repository.observeLastSyncedAt().test {
            assertTrue(awaitItem() != null) // a successful sync stamps the clock
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- price series: per-range cache + range→(range,interval) mapping ----

    // A minimal Yahoo chart body (last 110, close-before-window 100 → +10 / +10%),
    // enough to exercise the DTO parse and the period-change fields. NEVER hits Yahoo.
    private val chartFixture = """
        {"chart":{"result":[{"meta":{"currency":"USD","regularMarketPrice":110.0,"chartPreviousClose":100.0},
        "timestamp":[1700000000,1700086400],"indicators":{"quote":[{"close":[105.0,110.0]}]}}],"error":null}}
    """.trimIndent()

    /** A chart API over a MockEngine that records the range/interval it was asked for. */
    private fun recordingChartApi(record: (range: String?, interval: String?) -> Unit): YahooChartApi {
        val engine = MockEngine { request ->
            record(request.url.parameters["range"], request.url.parameters["interval"])
            respond(
                content = chartFixture,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return YahooChartApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } })
    }

    @Test
    fun WHEN_refreshing_a_daily_range_THEN_ONE_1y_fetch_warms_all_four_daily_ranges() = runTest {
        val dao = FakeScannerDao()
        var lastRange: String? = null
        var lastInterval: String? = null
        var fetchCount = 0
        val repository = TickerRepositoryImpl(
            api = failingApi(),
            dao = dao,
            chartApi = recordingChartApi { r, i -> lastRange = r; lastInterval = i; fetchCount++ },
            descriptionsApi = descriptionsApi("[]"),
            json = json,
        )

        // Refreshing ANY daily range fetches the 1Y daily series ONCE and slices it
        // into all four daily rows — one network call instead of four.
        repository.refreshPriceSeries("DAVE", ChartRange.ONE_MONTH)

        assertEquals(1, fetchCount)      // a single Yahoo call...
        assertEquals("1y", lastRange)    // ...for the widest daily window,
        assertEquals("1d", lastInterval) // at daily candles.

        // All four daily ranges are now cached (warmed by the same fetch). With the
        // 2-point fixture every window covers both points, so each carries the same
        // period change derived from the pre-window close (110 vs 100).
        for (range in listOf(
            ChartRange.ONE_MONTH, ChartRange.THREE_MONTHS,
            ChartRange.SIX_MONTHS, ChartRange.ONE_YEAR,
        )) {
            val cached = repository.observePriceSeries("DAVE", range).first()
            assertEquals(110.0, cached?.last, "last for $range")
            assertEquals(10.0, cached?.periodChange, "periodChange for $range")
            assertEquals(0.1, cached?.periodChangePercent, "periodChangePercent for $range")
        }

        // An intraday range is a distinct cache key — untouched by the daily fetch.
        assertNull(repository.observePriceSeries("DAVE", ChartRange.ONE_DAY).first())
    }

    @Test
    fun WHEN_the_range_is_intraday_THEN_an_intraday_interval_is_requested() = runTest {
        val dao = FakeScannerDao()
        var lastRange: String? = null
        var lastInterval: String? = null
        val repository = TickerRepositoryImpl(
            api = failingApi(),
            dao = dao,
            chartApi = recordingChartApi { r, i -> lastRange = r; lastInterval = i },
            descriptionsApi = descriptionsApi("[]"),
            json = json,
        )

        repository.refreshPriceSeries("DAVE", ChartRange.ONE_DAY)

        assertEquals("1d", lastRange)   // ChartRange.ONE_DAY.yahooRange
        assertEquals("5m", lastInterval) // intraday candles, NOT the daily "1d"
        assertTrue(ChartRange.ONE_DAY.isIntraday)
    }

    @Test
    fun WHEN_refresh_fails_THEN_the_cache_is_not_wiped() = runTest {
        val dao = FakeScannerDao()
        // Seed a warm cache first.
        dao.upsertAll(TickerEntityModelCreator.list(3))
        val repository = repository(failingApi(), dao)

        repository.observeMomentumLeaders(LeaderSort.STRENGTH).test {
            assertEquals(3, awaitItem().size) // warm cache

            val result = repository.refresh()
            assertTrue(result is RefreshResult.Failed)
            assertEquals("network down", result.message)

            // The failed refresh must NOT emit an empty list — the cache survives.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(3, dao.count())
    }

    // ---- ticker profile: tombstones, the two clocks, and the earnings countdown ----

    /** An ISO timestamp [days] in the past, so freshness fixtures need no injected clock. */
    @OptIn(ExperimentalTime::class)
    private fun daysAgo(days: Int): String = (Clock.System.now() - days.days).toString()

    /** An ISO date [days] in the future, in the device's own time zone. */
    @OptIn(ExperimentalTime::class)
    private fun dateInDays(days: Int): String =
        Clock.System.now().plus(days.days)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

    private fun profileBody(
        timeless: String = """{"description":"Makes chips.","updated_at":"${daysAgo(2)}","ttl_days":30}""",
        current: String = """{"description":"Beat estimates.","pro":["Margins up"],"con":["China exposure"],"updated_at":"${daysAgo(1)}","ttl_days":7}""",
    ) = """[{"ticker":"DAVE","timeless":$timeless,"current":$current}]"""

    @Test
    fun WHEN_a_profile_is_refreshed_THEN_its_text_and_lists_are_observable() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(failingApi(), dao, descriptionsApi(profileBody()))

        repository.refreshProfile("DAVE")

        val profile = repository.observeProfile("DAVE").first()
        assertEquals("Makes chips.", profile?.timelessDescription)
        assertEquals("Beat estimates.", profile?.currentDescription)
        assertEquals(listOf("Margins up"), profile?.pros)
        assertEquals(listOf("China exposure"), profile?.cons)
        assertEquals(ContentFreshness.FRESH, profile?.freshness)
    }

    /**
     * The behaviour that matters most here: MOST tickers have no profile, so "upstream
     * said no" has to be remembered. Without the tombstone this screen would re-hit
     * the network on every single visit for the majority of the universe.
     */
    @Test
    fun WHEN_upstream_has_no_profile_THEN_a_tombstone_stops_the_next_fetch() = runTest {
        val dao = FakeScannerDao()
        var calls = 0
        val repository = repository(failingApi(), dao, descriptionsApi("[]") { calls++ })

        repository.refreshProfile("DAVE")
        repository.refreshProfile("DAVE")

        assertEquals(1, calls) // the second call was suppressed by the TTL gate...
        assertNull(repository.observeProfile("DAVE").first()) // ...and shows nothing
        assertTrue(dao.profiles.value.containsKey("DAVE")) // but the row IS there
    }

    @Test
    fun WHEN_the_cached_profile_is_still_fresh_THEN_no_network_call_is_made() = runTest {
        val dao = FakeScannerDao()
        var calls = 0
        val repository = repository(failingApi(), dao, descriptionsApi(profileBody()) { calls++ })

        repository.refreshProfile("DAVE")
        repository.refreshProfile("DAVE")

        assertEquals(1, calls)
    }

    @Test
    fun WHEN_the_profile_fetch_fails_THEN_it_neither_throws_nor_wipes_the_cache() = runTest {
        val dao = FakeScannerDao()
        val good = repository(failingApi(), dao, descriptionsApi(profileBody()))
        good.refreshProfile("DAVE")

        // A second repository over the same DAO, this time with a broken endpoint.
        val brokenEngine = MockEngine { throw RuntimeException("network down") }
        val broken = repository(
            failingApi(),
            dao,
            SupabaseDescriptionsApi(
                client = HttpClient(brokenEngine) { install(ContentNegotiation) { json(json) } },
                baseUrl = "https://test.local",
                anonKey = "anon",
            ),
        )
        broken.refreshProfile("OTHER") // must not throw

        assertEquals("Makes chips.", broken.observeProfile("DAVE").first()?.timelessDescription)
    }

    @Test
    fun WHEN_the_blocks_are_empty_objects_THEN_there_is_nothing_to_show() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(
            failingApi(),
            dao,
            descriptionsApi(profileBody(timeless = "{}", current = "{}")),
        )

        repository.refreshProfile("DAVE")

        // Decodes cleanly (no crash) and collapses to null: the card is simply absent.
        assertNull(repository.observeProfile("DAVE").first())
    }

    @Test
    fun WHEN_a_block_is_older_than_its_ttl_THEN_the_profile_reads_stale() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(
            failingApi(),
            dao,
            descriptionsApi(
                profileBody(
                    timeless = """{"description":"Makes chips.","updated_at":"${daysAgo(40)}","ttl_days":30}""",
                    current = """{"description":"Beat estimates.","updated_at":"${daysAgo(1)}","ttl_days":7}""",
                ),
            ),
        )

        repository.refreshProfile("DAVE")

        val profile = repository.observeProfile("DAVE").first()
        assertEquals(ContentFreshness.STALE, profile?.timelessFreshness)
        assertEquals(ContentFreshness.FRESH, profile?.currentFreshness)
        // One stale block makes the whole card stale — the reader cannot tell which
        // sentence came from which block.
        assertEquals(ContentFreshness.STALE, profile?.freshness)
    }

    @Test
    fun WHEN_a_timestamp_is_missing_THEN_freshness_is_unknown_and_never_fresh() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(
            failingApi(),
            dao,
            descriptionsApi(profileBody(timeless = """{"description":"Makes chips."}""", current = "{}")),
        )

        repository.refreshProfile("DAVE")

        val profile = repository.observeProfile("DAVE").first()
        assertEquals(ContentFreshness.UNKNOWN, profile?.freshness)
    }

    /**
     * Upstream's `days_away` is a snapshot that was right the day it was written and
     * drifts by a day every day after — for weeks, if the cache is read offline. The
     * countdown the UI shows must come from the DATE, not from that number.
     */
    @Test
    fun WHEN_upstream_days_away_is_stale_THEN_the_countdown_is_recomputed_from_the_date() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(
            failingApi(),
            dao,
            descriptionsApi(
                profileBody(
                    current = """{"description":"Beat estimates.","updated_at":"${daysAgo(1)}",
                        "next_earnings":{"date":"${dateInDays(12)}","days_away":99,"consensus":"BUY"}}"""
                        .trimIndent().replace("\n", ""),
                ),
            ),
        )

        repository.refreshProfile("DAVE")

        val earnings = repository.observeProfile("DAVE").first()?.nextEarnings
        assertEquals(12, earnings?.daysAway) // NOT the 99 upstream published
        assertEquals("BUY", earnings?.consensus)
    }

    /**
     * Two things at once: `days_away` is typed Double because a pandas/numpy pipeline
     * writes `12.0` as readily as `12` (typed Int it would fail to decode and take the
     * whole row with it), and an unparseable date is where upstream's snapshot is
     * allowed to stand in for the computed countdown.
     */
    @Test
    fun WHEN_the_date_is_unparseable_THEN_the_float_days_away_stands_in() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(
            failingApi(),
            dao,
            descriptionsApi(
                profileBody(
                    current = """{"description":"Beat estimates.","updated_at":"${daysAgo(1)}","next_earnings":{"date":"soon","days_away":12.0,"consensus":"HOLD"}}""",
                ),
            ),
        )

        repository.refreshProfile("DAVE")

        val earnings = repository.observeProfile("DAVE").first()?.nextEarnings
        assertEquals(12, earnings?.daysAway)
        assertEquals("HOLD", earnings?.consensus)
    }

    /**
     * The bug that shipped to the emulator and made the card vanish.
     *
     * `next_earnings` is polymorphic upstream — an object on 85 live rows, a bare
     * string on 63. Typed as an object only, those 63 threw during decoding, which took
     * the WHOLE row with them: no profile, and not even a tombstone. The string form
     * carries the same prose the object's `consensus` does, so it lands there.
     */
    @Test
    fun WHEN_next_earnings_is_a_bare_string_THEN_the_profile_still_loads() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(
            failingApi(),
            dao,
            descriptionsApi(
                profileBody(
                    current = """{"description":"Beat estimates.","pro":["Scale"],"updated_at":"${daysAgo(1)}","next_earnings":"Q2 2026 results expected around August 2026."}""",
                ),
            ),
        )

        repository.refreshProfile("DAVE")

        val profile = repository.observeProfile("DAVE").first()
        assertEquals("Beat estimates.", profile?.currentDescription) // the row survived
        assertEquals(listOf("Scale"), profile?.pros)
        assertEquals("Q2 2026 results expected around August 2026.", profile?.nextEarnings?.consensus)
        assertNull(profile?.nextEarnings?.date) // prose only — no date to show
        assertNull(profile?.nextEarnings?.daysAway)
    }

    /** An unexpected shape must cost the earnings block, never the whole profile. */
    @Test
    fun WHEN_next_earnings_has_an_unknown_shape_THEN_only_that_block_is_dropped() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(
            failingApi(),
            dao,
            descriptionsApi(
                profileBody(
                    current = """{"description":"Beat estimates.","updated_at":"${daysAgo(1)}","next_earnings":[1,2,3]}""",
                ),
            ),
        )

        repository.refreshProfile("DAVE")

        val profile = repository.observeProfile("DAVE").first()
        assertEquals("Beat estimates.", profile?.currentDescription)
        assertNull(profile?.nextEarnings)
    }

    /** With no date there is nothing to anchor a countdown to, so none is claimed. */
    @Test
    fun WHEN_there_is_no_earnings_date_THEN_no_countdown_is_shown() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(
            failingApi(),
            dao,
            descriptionsApi(
                profileBody(
                    current = """{"description":"Beat estimates.","updated_at":"${daysAgo(1)}","next_earnings":{"days_away":99,"consensus":"BUY"}}""",
                ),
            ),
        )

        repository.refreshProfile("DAVE")

        val earnings = repository.observeProfile("DAVE").first()?.nextEarnings
        assertNull(earnings?.daysAway) // upstream's 99 is NOT replayed
        assertEquals("BUY", earnings?.consensus) // but the consensus is a plain fact
    }
}
