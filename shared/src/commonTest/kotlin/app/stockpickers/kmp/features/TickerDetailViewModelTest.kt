package app.stockpickers.kmp.features

import app.cash.turbine.test
import app.stockpickers.kmp.base.ViewModelTest
import app.stockpickers.kmp.base.awaitUntil
import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.ContentFreshness
import app.stockpickers.kmp.domain.GetTickerDetailUseCase
import app.stockpickers.kmp.domain.NextEarnings
import app.stockpickers.kmp.domain.ObservePriceSeriesUseCase
import app.stockpickers.kmp.domain.ObserveTickerProfileUseCase
import app.stockpickers.kmp.domain.RefreshPriceSeriesUseCase
import app.stockpickers.kmp.domain.RefreshTickerProfileUseCase
import app.stockpickers.kmp.domain.TickerProfile
import app.stockpickers.kmp.fake.FakeTickerRepository
import app.stockpickers.kmp.navigation.AppNavKey
import app.stockpickers.kmp.presentation.TickerDetailViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the detail screen's loading contract.
 *
 * The state comes from a five-way `combine`, which emits NOTHING until every source
 * has emitted at least once. That makes each added source a chance to strand the
 * whole screen on `isLoading = true` — a failure that is invisible in a unit test of
 * the repository and, on iOS, only shows up as a spinner on a real device. Hence the
 * emphasis below on the empty cases rather than the happy path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TickerDetailViewModelTest : ViewModelTest() {

    private val repository = FakeTickerRepository()

    private fun createViewModel(ticker: String = "DAVE") = TickerDetailViewModel(
        navKey = AppNavKey.TickerDetail(ticker),
        getTickerDetail = GetTickerDetailUseCase(repository),
        observePriceSeries = ObservePriceSeriesUseCase(repository),
        observeTickerProfile = ObserveTickerProfileUseCase(repository),
        refreshPriceSeries = RefreshPriceSeriesUseCase(repository),
        refreshTickerProfile = RefreshTickerProfileUseCase(repository),
    )

    private val profile = TickerProfile(
        ticker = "DAVE",
        timelessDescription = "Makes chips.",
        currentDescription = "Beat estimates.",
        pros = listOf("Margins up"),
        cons = listOf("China exposure"),
        nextEarnings = NextEarnings(date = "2026-08-05", daysAway = 18, consensus = "BUY"),
        timelessFreshness = ContentFreshness.FRESH,
        currentFreshness = ContentFreshness.FRESH,
    )

    /**
     * THE regression guard. Most tickers have no profile, so the null case is the
     * common one — if a null profile could hold `isLoading` high, the majority of the
     * universe would show a permanent spinner instead of a screen.
     */
    @Test
    fun WHEN_there_is_no_profile_THEN_the_screen_still_finishes_loading() = runTest(testDispatcher) {
        val viewModel = createViewModel() // every flow starts null

        viewModel.uiState.test {
            val state = awaitUntil { !it.isLoading }
            assertNull(state.profile)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun WHEN_the_cache_holds_a_profile_THEN_it_reaches_the_state() = runTest(testDispatcher) {
        repository.profileFlow.value = profile
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitUntil { it.profile != null }
            assertEquals("Makes chips.", state.profile?.timelessDescription)
            assertEquals(listOf("Margins up"), state.profile?.pros)
            assertEquals(18, state.profile?.nextEarnings?.daysAway)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A profile arriving later must not disturb the rest of the screen. */
    @Test
    fun WHEN_a_profile_lands_after_first_render_THEN_it_appears_without_reloading() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()

            viewModel.uiState.test {
                assertTrue(awaitUntil { !it.isLoading }.profile == null)

                repository.profileFlow.value = profile

                val state = awaitUntil { it.profile != null }
                assertTrue(!state.isLoading) // no flicker back to the spinner
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun WHEN_constructed_THEN_it_refreshes_the_profile_once_for_that_ticker() =
        runTest(testDispatcher) {
            createViewModel(ticker = "NVDA")

            assertEquals(1, repository.profileRefreshCount)
            assertEquals("NVDA", repository.lastProfileRefresh)
        }

    /** Non-regression: adding the profile must not disturb the chart wiring. */
    @Test
    fun WHEN_selectRange_THEN_the_chart_still_refreshes_for_the_new_range() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()

            viewModel.uiState.test {
                awaitUntil { !it.isLoading }
                viewModel.selectRange(ChartRange.ONE_YEAR)
                assertEquals(ChartRange.ONE_YEAR, awaitUntil { it.selectedRange == ChartRange.ONE_YEAR }.selectedRange)
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(ChartRange.ONE_YEAR, repository.lastPriceSeriesRefresh?.second)
        }
}
