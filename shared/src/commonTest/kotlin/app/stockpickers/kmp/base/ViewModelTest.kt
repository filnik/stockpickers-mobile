package app.stockpickers.kmp.base

import app.cash.turbine.ReceiveTurbine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Base class for ViewModel tests — the multiplatform equivalent of Wishew's
 * `UnitTest`. These ViewModels do NOT inject a `DispatcherProvider`; they use
 * `viewModelScope`, which runs on `Dispatchers.Main`. So the only setup needed is
 * to swap Main for a [TestDispatcher] and reset it afterwards.
 *
 * [UnconfinedTestDispatcher] (not Standard) so coroutines launched in
 * `viewModelScope` — including the `init { refresh() }` — run eagerly, which is
 * what makes the `StateFlow` settle without manual `advanceUntilIdle()`. Each test
 * calls `runTest(testDispatcher)` so the test body and `viewModelScope` share one
 * virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTest {

    protected val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUpMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }
}

/**
 * Awaits emissions, discarding intermediates, until [predicate] holds. `combine`
 * over several flows can emit transient states while the axes settle; this makes
 * an assertion depend on the state it cares about rather than on the exact number
 * of intermediate frames.
 */
suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
