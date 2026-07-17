package app.stockpickers.kmp.features

import app.cash.turbine.test
import app.stockpickers.kmp.base.ViewModelTest
import app.stockpickers.kmp.base.awaitUntil
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.GetGeoCountsUseCase
import app.stockpickers.kmp.domain.GetMomentumLeadersUseCase
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.ObserveLastSyncedAtUseCase
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.domain.RefreshTickersUseCase
import app.stockpickers.kmp.fake.FakeTickerRepository
import app.stockpickers.kmp.modelcreators.TickerModelCreator
import app.stockpickers.kmp.presentation.MomentumLeadersViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MomentumLeadersViewModelTest : ViewModelTest() {

    private val repository = FakeTickerRepository()

    private fun createViewModel() = MomentumLeadersViewModel(
        getMomentumLeaders = GetMomentumLeadersUseCase(repository),
        getGeoCounts = GetGeoCountsUseCase(repository),
        observeLastSyncedAt = ObserveLastSyncedAtUseCase(repository),
        refreshTickers = RefreshTickersUseCase(repository),
    )

    @Test
    fun WHEN_cache_emits_THEN_state_moves_from_loading_to_data() = runTest(testDispatcher) {
        repository.leadersFlow.value = TickerModelCreator.list(3)
        val viewModel = createViewModel()

        // Before anyone collects, WhileSubscribed keeps the upstream cold, so the
        // exposed value is the loading initialValue. (Under UnconfinedTestDispatcher
        // the upstream settles synchronously on subscribe, so this loading frame is
        // conflated away for a collector — it must be read here, not via awaitItem.)
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.uiState.test {
            val loaded = awaitUntil { !it.isLoading }
            assertEquals(3, loaded.leaders.size)
            assertFalse(loaded.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun WHEN_selectSort_and_selectGeo_THEN_each_axis_preserves_the_other() = runTest(testDispatcher) {
        repository.leadersFlow.value = TickerModelCreator.list(2)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }.let {
                assertEquals(LeaderSort.STRENGTH, it.sort)
                assertEquals(GeoFilter.ALL, it.geo)
            }

            viewModel.selectSort(LeaderSort.ONE_MONTH)
            assertEquals(GeoFilter.ALL, awaitUntil { it.sort == LeaderSort.ONE_MONTH }.geo)

            viewModel.selectGeo(GeoFilter.ASIA)
            assertEquals(LeaderSort.ONE_MONTH, awaitUntil { it.geo == GeoFilter.ASIA }.sort)

            cancelAndIgnoreRemainingEvents()
        }

        // The DAO query reflects BOTH selections, not just the last one set.
        assertEquals(LeaderSort.ONE_MONTH, repository.lastLeadersQuery?.first)
        assertEquals(GeoFilter.ASIA, repository.lastLeadersQuery?.second)
    }

    /**
     * Regression guard for the "offline badge" bug: a failed refresh must set the
     * error/offline flags WITHOUT blanking the leaders already on screen.
     */
    @Test
    fun WHEN_refresh_fails_THEN_error_is_set_but_leaders_are_kept() = runTest(testDispatcher) {
        repository.leadersFlow.value = TickerModelCreator.list(4)
        repository.refreshResult = RefreshResult.Failed("network down")
        val viewModel = createViewModel() // init { refresh() } runs and fails

        viewModel.uiState.test {
            val state = awaitUntil { it.errorMessage != null }
            assertEquals("network down", state.errorMessage)
            assertTrue(state.isOffline)
            assertEquals(4, state.leaders.size) // cache survives the failed refresh
            assertFalse(state.isFatal) // we have cached data, so no fatal takeover
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun WHEN_constructed_THEN_it_refreshes_once_on_init() = runTest(testDispatcher) {
        createViewModel()
        assertEquals(1, repository.refreshCount) // the init { refresh() } fired exactly once
    }
}
