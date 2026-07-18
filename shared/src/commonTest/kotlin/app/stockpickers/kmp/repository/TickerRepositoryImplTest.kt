package app.stockpickers.kmp.repository

import app.cash.turbine.test
import app.stockpickers.kmp.data.remote.SupabaseScannerApi
import app.stockpickers.kmp.data.remote.TickerDto
import app.stockpickers.kmp.data.remote.YahooChartApi
import app.stockpickers.kmp.data.repository.TickerRepositoryImpl
import app.stockpickers.kmp.domain.ChartRange
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun repository(api: SupabaseScannerApi, dao: FakeScannerDao) =
        TickerRepositoryImpl(api = api, dao = dao, chartApi = unusedChartApi(), json = json)

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
    fun WHEN_refreshing_a_range_THEN_it_maps_to_yahoo_range_interval_and_caches_under_that_range() = runTest {
        val dao = FakeScannerDao()
        var lastRange: String? = null
        var lastInterval: String? = null
        val repository = TickerRepositoryImpl(
            api = failingApi(),
            dao = dao,
            chartApi = recordingChartApi { r, i -> lastRange = r; lastInterval = i },
            json = json,
        )

        repository.refreshPriceSeries("DAVE", ChartRange.ONE_MONTH)

        // The enum's Yahoo tokens reached the query (daily candles for 1M).
        assertEquals("1mo", lastRange)
        assertEquals("1d", lastInterval)

        // Cached under ONE_MONTH — with the period change derived from the window's
        // previous close (110 vs 100).
        val cached = repository.observePriceSeries("DAVE", ChartRange.ONE_MONTH).first()
        assertEquals(110.0, cached?.last)
        assertEquals(10.0, cached?.periodChange)
        assertEquals(0.1, cached?.periodChangePercent)

        // A DIFFERENT range is a distinct cache key — untouched by the 1M fetch.
        assertNull(repository.observePriceSeries("DAVE", ChartRange.SIX_MONTHS).first())
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
}
