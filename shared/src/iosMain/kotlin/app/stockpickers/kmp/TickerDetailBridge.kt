package app.stockpickers.kmp

import app.stockpickers.kmp.domain.GetTickerDetailUseCase
import app.stockpickers.kmp.navigation.AppNavKey
import app.stockpickers.kmp.presentation.TickerDetailUiState
import app.stockpickers.kmp.presentation.TickerDetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Swift-facing bridge for the SHARED [TickerDetailViewModel].
 *
 * Normally SKIE would turn `StateFlow<T>` into a Swift `AsyncSequence` and this
 * class would be unnecessary. But SKIE 0.10.13 does not yet support Kotlin 2.4.10
 * (see `shared/build.gradle.kts`), so the Flow -> Swift observation is hand-rolled:
 * it collects the ViewModel's `uiState` on the main dispatcher and pushes each
 * value to a Swift closure. This is exactly what SKIE / KMP-NativeCoroutines do
 * under the hood.
 *
 * Naming note: this is a class, not a function — the Objective-C exporter only
 * mangles the `init*` *function* family, not constructors, so `TickerDetailBridge`
 * crosses to Swift as `TickerDetailBridge(ticker:)` unchanged.
 *
 * Lifecycle: [cancel] (called from SwiftUI `.onDisappear`) stops the collection.
 * The ViewModel's own `stateIn(started = WhileSubscribed(5s))` then tears down the
 * upstream Room flow once this last subscriber is gone; the ViewModel instance is
 * released by ARC/Kotlin-Native GC when Swift drops its reference. (A ViewModelStore
 * would call `onCleared` deterministically — overkill for a single detail screen.)
 */
class TickerDetailBridge(ticker: String) {

    private val viewModel = TickerDetailViewModel(
        navKey = AppNavKey.TickerDetail(ticker),
        getTickerDetail = KoinPlatform.getKoin().get<GetTickerDetailUseCase>(),
    )

    private val scope = CoroutineScope(Dispatchers.Main)

    /** Current value, so SwiftUI can render synchronously on the first frame. */
    val current: TickerDetailUiState get() = viewModel.uiState.value

    /** Start pushing every [TickerDetailUiState] emission to [onState] (main thread). */
    fun observe(onState: (TickerDetailUiState) -> Unit) {
        scope.launch {
            viewModel.uiState.collect { onState(it) }
        }
    }

    /** Stop observing. Call from SwiftUI `.onDisappear`. */
    fun cancel() {
        scope.cancel()
    }
}
