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
import app.stockpickers.kmp.domain.RefreshFailure
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.fake.FakeScannerDao
import app.stockpickers.kmp.modelcreators.TickerDtoModelCreator
import app.stockpickers.kmp.modelcreators.TickerEntityModelCreator
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
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

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

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

    /** Unreachable server: an IOException, exactly as both real engines report it. */
    private fun failingApi(): SupabaseScannerApi = scannerApi(MockEngine { throw IOException("network down") })

    /** Reached and refused — the case that must NOT read as offline. */
    private fun refusingApi(status: HttpStatusCode): SupabaseScannerApi =
        scannerApi(MockEngine { respond(content = "", status = status) })

    private fun scannerApi(engine: MockEngine): SupabaseScannerApi = SupabaseScannerApi(
        client = HttpClient(engine) { install(ContentNegotiation) { json(json) } },
        baseUrl = "https://test.local",
        anonKey = "anon",
    )

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
            awaitItem().shouldBeEmpty() // cache starts empty

            val result = repository.refresh()
            result.shouldBeInstanceOf<RefreshResult.Success>()

            awaitItem() shouldHaveSize 2 // the refresh landed in Room, UI re-rendered
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun WHEN_refresh_succeeds_THEN_last_synced_at_is_recorded() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(successApi(TickerDtoModelCreator.list(1)), dao)

        repository.refresh() shouldBe RefreshResult.Success

        repository.observeLastSyncedAt().test {
            awaitItem().shouldNotBeNull() // a successful sync stamps the clock
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

    /**
     * A daily response carrying BOTH series, with deliberately different numbers so a
     * test can tell which one was read. Yahoo's raw `quote.close` is unadjusted for
     * splits and dividends; `adjclose` is the one that agrees with the momentum and
     * clenow figures shown beside the chart.
     */
    private val adjustedChartFixture = """
        {"chart":{"result":[{"meta":{"currency":"USD","regularMarketPrice":110.0,"chartPreviousClose":100.0},
        "timestamp":[1700000000,1700086400],
        "indicators":{"quote":[{"close":[210.0,220.0]}],"adjclose":[{"adjclose":[105.0,110.0]}]}}],"error":null}}
    """.trimIndent()

    /** Intraday responses have no `adjclose` block at all — the fallback must hold. */
    private val intradayChartFixture = """
        {"chart":{"result":[{"meta":{"currency":"USD","regularMarketPrice":110.0,"chartPreviousClose":100.0},
        "timestamp":[1700000000,1700086400],"indicators":{"quote":[{"close":[210.0,220.0]}]}}],"error":null}}
    """.trimIndent()

    private fun chartApiReturning(body: String): YahooChartApi {
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return YahooChartApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } })
    }

    @Test
    fun WHEN_a_daily_response_carries_adjclose_THEN_the_adjusted_series_is_stored() = runTest {
        val dao = FakeScannerDao()
        val repository = TickerRepositoryImpl(
            api = failingApi(),
            dao = dao,
            chartApi = chartApiReturning(adjustedChartFixture),
            descriptionsApi = descriptionsApi("[]"),
            json = json,
        )

        repository.refreshPriceSeries("NVDA", ChartRange.ONE_YEAR)

        val closes = repository.observePriceSeries("NVDA", ChartRange.ONE_YEAR).first()!!.points.map { it.close }
        closes shouldContainExactly listOf(105.0, 110.0) // adjusted, NOT the raw 210/220
    }

    @Test
    fun WHEN_an_intraday_response_has_no_adjclose_THEN_it_falls_back_to_the_raw_close() = runTest {
        val dao = FakeScannerDao()
        val repository = TickerRepositoryImpl(
            api = failingApi(),
            dao = dao,
            chartApi = chartApiReturning(intradayChartFixture),
            descriptionsApi = descriptionsApi("[]"),
            json = json,
        )

        repository.refreshPriceSeries("NVDA", ChartRange.ONE_DAY)

        val closes = repository.observePriceSeries("NVDA", ChartRange.ONE_DAY).first()!!.points.map { it.close }
        closes shouldContainExactly listOf(210.0, 220.0)
    }

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
            chartApi = recordingChartApi { r, i ->
                lastRange = r
                lastInterval = i
                fetchCount++
            },
            descriptionsApi = descriptionsApi("[]"),
            json = json,
        )

        // Refreshing ANY daily range fetches the 1Y daily series ONCE and slices it
        // into all four daily rows — one network call instead of four.
        repository.refreshPriceSeries("DAVE", ChartRange.ONE_MONTH)

        fetchCount shouldBe 1 // a single Yahoo call...
        lastRange shouldBe "1y" // ...for the widest daily window,
        lastInterval shouldBe "1d" // at daily candles.

        // All four daily ranges are now cached (warmed by the same fetch). With the
        // 2-point fixture every window covers both points, so each carries the same
        // period change derived from the pre-window close (110 vs 100).
        for (range in listOf(
            ChartRange.ONE_MONTH,
            ChartRange.THREE_MONTHS,
            ChartRange.SIX_MONTHS,
            ChartRange.ONE_YEAR,
        )) {
            val cached = repository.observePriceSeries("DAVE", range).first()
            withClue("last for $range") { cached!!.last shouldBe 110.0 }
            withClue("periodChange for $range") { cached!!.periodChange shouldBe 10.0 }
            withClue("periodChangePercent for $range") { cached!!.periodChangePercent shouldBe 0.1 }
        }

        // An intraday range is a distinct cache key — untouched by the daily fetch.
        repository.observePriceSeries("DAVE", ChartRange.ONE_DAY).first().shouldBeNull()
    }

    @Test
    fun WHEN_the_range_is_intraday_THEN_an_intraday_interval_is_requested() = runTest {
        val dao = FakeScannerDao()
        var lastRange: String? = null
        var lastInterval: String? = null
        val repository = TickerRepositoryImpl(
            api = failingApi(),
            dao = dao,
            chartApi = recordingChartApi { r, i ->
                lastRange = r
                lastInterval = i
            },
            descriptionsApi = descriptionsApi("[]"),
            json = json,
        )

        repository.refreshPriceSeries("DAVE", ChartRange.ONE_DAY)

        lastRange shouldBe "1d" // ChartRange.ONE_DAY.yahooRange
        lastInterval shouldBe "5m" // intraday candles, NOT the daily "1d"
        ChartRange.ONE_DAY.isIntraday shouldBe true
    }

    @Test
    fun WHEN_refresh_fails_THEN_the_cache_is_not_wiped() = runTest {
        val dao = FakeScannerDao()
        // Seed a warm cache first.
        dao.upsertAll(TickerEntityModelCreator.list(3))
        val repository = repository(failingApi(), dao)

        repository.observeMomentumLeaders(LeaderSort.STRENGTH).test {
            awaitItem() shouldHaveSize 3 // warm cache

            val result = repository.refresh()
            result.shouldBeInstanceOf<RefreshResult.Failed>()
            result.message shouldBe "network down"
            result.reason shouldBe RefreshFailure.OFFLINE

            // The failed refresh must NOT emit an empty list — the cache survives.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        dao.count() shouldBe 3
    }

    /**
     * The failure kinds must stay apart. Reporting a 500 (or an undecodable body) as
     * OFFLINE tells the user to check a connection that is working perfectly.
     */
    @Test
    fun WHEN_the_server_refuses_THEN_the_failure_is_SERVER_not_OFFLINE() = runTest {
        val repository = repository(refusingApi(HttpStatusCode.InternalServerError), FakeScannerDao())

        val result = repository.refresh()

        result.shouldBeInstanceOf<RefreshResult.Failed>()
        result.reason shouldBe RefreshFailure.SERVER
    }

    @Test
    fun WHEN_the_payload_cannot_be_decoded_THEN_the_failure_is_UNKNOWN() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"not":"an array"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repository = repository(scannerApi(engine), FakeScannerDao())

        val result = repository.refresh()

        result.shouldBeInstanceOf<RefreshResult.Failed>()
        result.reason shouldBe RefreshFailure.UNKNOWN
    }

    // ---- ticker profile: tombstones, the two clocks, and the earnings countdown ----

    /** An ISO timestamp [days] in the past, so freshness fixtures need no injected clock. */
    @OptIn(ExperimentalTime::class)
    private fun daysAgo(days: Int): String = (Clock.System.now() - days.days).toString()

    /** An ISO date [days] in the future, in the device's own time zone. */
    @OptIn(ExperimentalTime::class)
    private fun dateInDays(days: Int): String = Clock.System.now().plus(days.days)
        .toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

    private fun profileBody(
        timeless: String = """{"description":"Makes chips.","updated_at":"${daysAgo(2)}","ttl_days":30}""",
        current: String = """{"description":"Beat estimates.","pro":["Margins up"],"con":["China exposure"],"updated_at":"${daysAgo(
            1,
        )}","ttl_days":7}""",
    ) = """[{"ticker":"DAVE","timeless":$timeless,"current":$current}]"""

    @Test
    fun WHEN_a_profile_is_refreshed_THEN_its_text_and_lists_are_observable() = runTest {
        val dao = FakeScannerDao()
        val repository = repository(failingApi(), dao, descriptionsApi(profileBody()))

        repository.refreshProfile("DAVE")

        val profile = repository.observeProfile("DAVE").first()
        profile!!.timelessDescription shouldBe "Makes chips."
        profile.currentDescription shouldBe "Beat estimates."
        profile.pros shouldContainExactly listOf("Margins up")
        profile.cons shouldContainExactly listOf("China exposure")
        profile.freshness shouldBe ContentFreshness.FRESH
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

        calls shouldBe 1 // the second call was suppressed by the TTL gate...
        repository.observeProfile("DAVE").first().shouldBeNull() // ...and shows nothing
        dao.profiles.value shouldContainKey "DAVE" // but the row IS there
    }

    @Test
    fun WHEN_the_cached_profile_is_still_fresh_THEN_no_network_call_is_made() = runTest {
        val dao = FakeScannerDao()
        var calls = 0
        val repository = repository(failingApi(), dao, descriptionsApi(profileBody()) { calls++ })

        repository.refreshProfile("DAVE")
        repository.refreshProfile("DAVE")

        calls shouldBe 1
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

        broken.observeProfile("DAVE").first()!!.timelessDescription shouldBe "Makes chips."
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
        repository.observeProfile("DAVE").first().shouldBeNull()
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
        profile!!.timelessFreshness shouldBe ContentFreshness.STALE
        profile.currentFreshness shouldBe ContentFreshness.FRESH
        // One stale block makes the whole card stale — the reader cannot tell which
        // sentence came from which block.
        profile.freshness shouldBe ContentFreshness.STALE
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
        profile!!.freshness shouldBe ContentFreshness.UNKNOWN
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

        val earnings = repository.observeProfile("DAVE").first()!!.nextEarnings
        earnings!!.daysAway shouldBe 12 // NOT the 99 upstream published
        earnings.consensus shouldBe "BUY"
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
                    current = """{"description":"Beat estimates.","updated_at":"${daysAgo(
                        1,
                    )}","next_earnings":{"date":"soon","days_away":12.0,"consensus":"HOLD"}}""",
                ),
            ),
        )

        repository.refreshProfile("DAVE")

        val earnings = repository.observeProfile("DAVE").first()!!.nextEarnings
        earnings!!.daysAway shouldBe 12
        earnings.consensus shouldBe "HOLD"
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
                    current = """{"description":"Beat estimates.","pro":["Scale"],"updated_at":"${daysAgo(
                        1,
                    )}","next_earnings":"Q2 2026 results expected around August 2026."}""",
                ),
            ),
        )

        repository.refreshProfile("DAVE")

        val profile = repository.observeProfile("DAVE").first()
        profile!!.currentDescription shouldBe "Beat estimates." // the row survived
        profile.pros shouldContainExactly listOf("Scale")
        profile.nextEarnings!!.consensus shouldBe "Q2 2026 results expected around August 2026."
        profile.nextEarnings!!.date.shouldBeNull() // prose only — no date to show
        profile.nextEarnings!!.daysAway.shouldBeNull()
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
                    current = """{"description":"Beat estimates.","updated_at":"${daysAgo(
                        1,
                    )}","next_earnings":[1,2,3]}""",
                ),
            ),
        )

        repository.refreshProfile("DAVE")

        val profile = repository.observeProfile("DAVE").first()
        profile!!.currentDescription shouldBe "Beat estimates."
        profile.nextEarnings.shouldBeNull()
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
                    current = """{"description":"Beat estimates.","updated_at":"${daysAgo(
                        1,
                    )}","next_earnings":{"days_away":99,"consensus":"BUY"}}""",
                ),
            ),
        )

        repository.refreshProfile("DAVE")

        val earnings = repository.observeProfile("DAVE").first()!!.nextEarnings
        earnings!!.daysAway.shouldBeNull() // upstream's 99 is NOT replayed
        earnings.consensus shouldBe "BUY" // but the consensus is a plain fact
    }

    // ---- converging on the published universe -------------------------------

    /**
     * Upstream HARD-DELETES rows every run. An upsert-only sync would keep a delisted
     * name in Room forever, and it would go on qualifying for the board.
     */
    @Test
    fun WHEN_a_ticker_disappears_upstream_THEN_the_next_refresh_removes_it_from_the_cache() = runTest {
        val dao = FakeScannerDao()
        val kept = TickerDtoModelCreator.model.copy(ticker = "KEEP")
        val dropped = TickerDtoModelCreator.model.copy(ticker = "GONE")

        repository(successApi(listOf(kept, dropped)), dao).refresh()
        dao.rows.value.map { it.ticker }.toSet() shouldBe setOf("KEEP", "GONE")

        // Second publish no longer contains GONE.
        repository(successApi(listOf(kept)), dao).refresh()
        dao.rows.value.map { it.ticker } shouldContainExactly listOf("KEEP")
    }

    /** The prune must never run against an incomplete list — that IS the offline-first promise. */
    @Test
    fun WHEN_the_refresh_fails_THEN_nothing_is_pruned() = runTest {
        val dao = FakeScannerDao()
        repository(successApi(TickerDtoModelCreator.list(3)), dao).refresh()
        val before = dao.rows.value.map { it.ticker }.toSet()

        val result = repository(failingApi(), dao).refresh()

        result.shouldBeInstanceOf<RefreshResult.Failed>()
        dao.rows.value.map { it.ticker }.toSet() shouldBe before
    }

    /**
     * An empty publish is a upstream fault, not a real empty universe — and acting on
     * it would prune everything. The cache must survive and the refresh must report failure.
     */
    @Test
    fun WHEN_upstream_publishes_no_rows_THEN_the_cache_survives_and_the_refresh_fails() = runTest {
        val dao = FakeScannerDao()
        repository(successApi(TickerDtoModelCreator.list(2)), dao).refresh()

        val result = repository(successApi(emptyList()), dao).refresh()

        result.shouldBeInstanceOf<RefreshResult.Failed>()
        dao.rows.value shouldHaveSize 2
    }

    // ---- unit conventions ----------------------------------------------------

    /**
     * `mom_12m` falls back to `ann_mom`, exactly as upstream's writer and web client do.
     * Both are DECIMAL FRACTIONS: upstream's own `scanner_cache.md` claims ann_mom is in
     * percent units, and following that doc would divide every fallback value by 100.
     */
    @Test
    fun WHEN_mom_12m_is_missing_THEN_ann_mom_stands_in_unscaled() = runTest {
        val dao = FakeScannerDao()
        val row = TickerDtoModelCreator.model.copy(ticker = "AMOM", mom12m = null, annMom = 0.45)

        repository(successApi(listOf(row)), dao).refresh()

        dao.rows.value.single().mom12m shouldBe 0.45
    }

    /** When upstream sends both, the explicit window wins — ann_mom is only a fallback. */
    @Test
    fun WHEN_both_mom_12m_and_ann_mom_are_present_THEN_mom_12m_wins() = runTest {
        val dao = FakeScannerDao()
        val row = TickerDtoModelCreator.model.copy(ticker = "BOTH", mom12m = 0.10, annMom = 0.99)

        repository(successApi(listOf(row)), dao).refresh()

        dao.rows.value.single().mom12m shouldBe 0.10
    }
}
