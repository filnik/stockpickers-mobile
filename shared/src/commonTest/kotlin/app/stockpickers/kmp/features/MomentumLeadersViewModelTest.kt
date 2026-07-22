package app.stockpickers.kmp.features

import app.cash.turbine.test
import app.stockpickers.kmp.base.ViewModelTest
import app.stockpickers.kmp.base.awaitUntil
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.GetGeoCountsUseCase
import app.stockpickers.kmp.domain.GetMomentumLeadersUseCase
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.ObserveLastSyncedAtUseCase
import app.stockpickers.kmp.domain.RefreshFailure
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.domain.RefreshTickersUseCase
import app.stockpickers.kmp.domain.TickerRepository
import app.stockpickers.kmp.fake.FakeTickerRepository
import app.stockpickers.kmp.modelcreators.TickerModelCreator
import app.stockpickers.kmp.presentation.MomentumLeadersViewModel
import dev.mokkery.spy
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MomentumLeadersViewModelTest : ViewModelTest() {

    // The fake drives state; the spy around it records the calls. Setup goes through
    // `fake` (settable flows), verification through `repository` (the spy).
    private val fake = FakeTickerRepository()
    private val repository: TickerRepository = spy<TickerRepository>(fake)

    private fun createViewModel() = MomentumLeadersViewModel(
        getMomentumLeaders = GetMomentumLeadersUseCase(repository),
        getGeoCounts = GetGeoCountsUseCase(repository),
        observeLastSyncedAt = ObserveLastSyncedAtUseCase(repository),
        refreshTickers = RefreshTickersUseCase(repository),
    )

    @Test
    fun WHEN_cache_emits_THEN_state_moves_from_loading_to_data() = runTest(testDispatcher) {
        fake.leadersFlow.value = TickerModelCreator.list(3)
        val viewModel = createViewModel()

        // Before anyone collects, WhileSubscribed keeps the upstream cold, so the
        // exposed value is the loading initialValue. (Under UnconfinedTestDispatcher
        // the upstream settles synchronously on subscribe, so this loading frame is
        // conflated away for a collector — it must be read here, not via awaitItem.)
        viewModel.uiState.value.isLoading shouldBe true

        viewModel.uiState.test {
            val loaded = awaitUntil { !it.isLoading }
            loaded.leaders shouldHaveSize 3
            loaded.isEmpty shouldBe false
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun WHEN_selectSort_and_selectGeo_THEN_each_axis_preserves_the_other() = runTest(testDispatcher) {
        fake.leadersFlow.value = TickerModelCreator.list(2)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }.let {
                it.sort shouldBe LeaderSort.STRENGTH
                it.geo shouldBe GeoFilter.ALL
            }

            viewModel.selectSort(LeaderSort.ONE_MONTH)
            awaitUntil { it.sort == LeaderSort.ONE_MONTH }.geo shouldBe GeoFilter.ALL

            viewModel.selectGeo(GeoFilter.ASIA)
            awaitUntil { it.geo == GeoFilter.ASIA }.sort shouldBe LeaderSort.ONE_MONTH

            cancelAndIgnoreRemainingEvents()
        }

        // The DAO query reflects BOTH selections, not just the last one set — and the
        // limit is a live contract too: the board shows a top-N, and silently widening
        // it would render the whole ~1800-row universe with no error.
        verify {
            repository.observeMomentumLeaders(
                LeaderSort.ONE_MONTH,
                GeoFilter.ASIA,
                GetMomentumLeadersUseCase.DEFAULT_LIMIT,
            )
        }
    }

    /**
     * Regression guard for the "offline badge" bug: a failed refresh must set the
     * error/offline flags WITHOUT blanking the leaders already on screen.
     */
    @Test
    fun WHEN_refresh_fails_THEN_error_is_set_but_leaders_are_kept() = runTest(testDispatcher) {
        fake.leadersFlow.value = TickerModelCreator.list(4)
        fake.refreshResult = RefreshResult.Failed(RefreshFailure.OFFLINE, "network down")
        val viewModel = createViewModel() // init { refresh() } runs and fails

        viewModel.uiState.test {
            val state = awaitUntil { it.errorMessage != null }
            state.errorMessage shouldBe "network down"
            state.isOffline shouldBe true
            state.leaders shouldHaveSize 4 // cache survives the failed refresh
            state.isFatal shouldBe false // we have cached data, so no fatal takeover
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The offline badge must mean offline. A server-side failure is still stale
     * cache, but blaming the user's connection for it would send them chasing a
     * problem that is not theirs.
     */
    @Test
    fun WHEN_the_failure_is_not_offline_THEN_only_isStale_is_set() = runTest(testDispatcher) {
        fake.leadersFlow.value = TickerModelCreator.list(4)
        fake.refreshResult = RefreshResult.Failed(RefreshFailure.SERVER, "Supabase returned 500")
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitUntil { it.errorMessage != null }
            state.isStale shouldBe true
            state.isOffline shouldBe false
            state.refreshFailure shouldBe RefreshFailure.SERVER
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun WHEN_constructed_THEN_it_refreshes_once_on_init() = runTest(testDispatcher) {
        createViewModel()

        verifySuspend(exactly(1)) { repository.refresh() } // init { refresh() } fired once, not twice
    }
}
