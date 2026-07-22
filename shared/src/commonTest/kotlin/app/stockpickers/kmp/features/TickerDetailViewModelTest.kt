package app.stockpickers.kmp.features

import app.cash.turbine.test
import app.stockpickers.kmp.base.ViewModelTest
import app.stockpickers.kmp.base.awaitUntil
import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.GetTickerDetailUseCase
import app.stockpickers.kmp.domain.ObservePriceSeriesUseCase
import app.stockpickers.kmp.domain.ObserveTickerProfileUseCase
import app.stockpickers.kmp.domain.RefreshPriceSeriesUseCase
import app.stockpickers.kmp.domain.RefreshTickerProfileUseCase
import app.stockpickers.kmp.domain.TickerRepository
import app.stockpickers.kmp.fake.FakeTickerRepository
import app.stockpickers.kmp.modelcreators.NextEarningsModelCreator
import app.stockpickers.kmp.modelcreators.TickerProfileModelCreator
import app.stockpickers.kmp.navigation.AppNavKey
import app.stockpickers.kmp.presentation.TickerDetailViewModel
import dev.mokkery.matcher.any
import dev.mokkery.spy
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

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

    // The fake drives state; the spy around it records the calls.
    private val fake = FakeTickerRepository()
    private val repository: TickerRepository = spy<TickerRepository>(fake)

    private fun createViewModel(ticker: String = "DAVE") = TickerDetailViewModel(
        navKey = AppNavKey.TickerDetail(ticker),
        getTickerDetail = GetTickerDetailUseCase(repository),
        observePriceSeries = ObservePriceSeriesUseCase(repository),
        observeTickerProfile = ObserveTickerProfileUseCase(repository),
        refreshPriceSeries = RefreshPriceSeriesUseCase(repository),
        refreshTickerProfile = RefreshTickerProfileUseCase(repository),
    )

    // Only the fields these tests assert on are named; the rest comes from the creator,
    // so a new field on TickerProfile does not break this file.
    private val profile = TickerProfileModelCreator.model.copy(
        ticker = "DAVE",
        timelessDescription = "Makes chips.",
        pros = listOf("Margins up"),
        nextEarnings = NextEarningsModelCreator.model.copy(daysAway = 18, consensus = "BUY"),
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
            state.profile.shouldBeNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun WHEN_the_cache_holds_a_profile_THEN_it_reaches_the_state() = runTest(testDispatcher) {
        fake.profileFlow.value = profile
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitUntil { it.profile != null }
            state.profile!!.timelessDescription shouldBe "Makes chips."
            state.profile.pros shouldContainExactly listOf("Margins up")
            state.profile.nextEarnings!!.daysAway shouldBe 18
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A profile arriving later must not disturb the rest of the screen. */
    @Test
    fun WHEN_a_profile_lands_after_first_render_THEN_it_appears_without_reloading() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }.profile.shouldBeNull()

            fake.profileFlow.value = profile

            val state = awaitUntil { it.profile != null }
            state.isLoading shouldBe false // no flicker back to the spinner
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun WHEN_constructed_THEN_it_refreshes_the_profile_once_for_that_ticker() = runTest(testDispatcher) {
        createViewModel(ticker = "NVDA")

        // One assertion replaces a counter AND a captured argument: it checks both that
        // the refresh happened exactly once and that it was for the right ticker.
        verifySuspend(exactly(1)) { repository.refreshProfile("NVDA") }
    }

    /** Non-regression: adding the profile must not disturb the chart wiring. */
    @Test
    fun WHEN_selectRange_THEN_the_chart_still_refreshes_for_the_new_range() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }
            viewModel.selectRange(ChartRange.ONE_YEAR)
            awaitUntil { it.selectedRange == ChartRange.ONE_YEAR }.selectedRange shouldBe ChartRange.ONE_YEAR
            cancelAndIgnoreRemainingEvents()
        }

        verifySuspend { repository.refreshPriceSeries(any(), ChartRange.ONE_YEAR) }
    }
}
