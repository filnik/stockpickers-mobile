package app.stockpickers.kmp.repository

import app.cash.turbine.test
import app.stockpickers.kmp.data.remote.SupabaseScannerApi
import app.stockpickers.kmp.data.remote.TickerDto
import app.stockpickers.kmp.data.repository.TickerRepositoryImpl
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun WHEN_refresh_succeeds_THEN_rows_are_upserted_and_observable_from_the_dao() = runTest {
        val dao = FakeScannerDao()
        val repository = TickerRepositoryImpl(successApi(TickerDtoModelCreator.list(2)), dao)

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
        val repository = TickerRepositoryImpl(successApi(TickerDtoModelCreator.list(1)), dao)

        assertEquals(RefreshResult.Success, repository.refresh())

        repository.observeLastSyncedAt().test {
            assertTrue(awaitItem() != null) // a successful sync stamps the clock
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun WHEN_refresh_fails_THEN_the_cache_is_not_wiped() = runTest {
        val dao = FakeScannerDao()
        // Seed a warm cache first.
        dao.upsertAll(TickerEntityModelCreator.list(3))
        val repository = TickerRepositoryImpl(failingApi(), dao)

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
