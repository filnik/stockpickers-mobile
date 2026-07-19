package app.stockpickers.kmp

import app.stockpickers.kmp.domain.GetTickerDetailUseCase
import app.stockpickers.kmp.domain.ObservePriceSeriesUseCase
import app.stockpickers.kmp.domain.ObserveTickerProfileUseCase
import app.stockpickers.kmp.domain.RefreshPriceSeriesUseCase
import app.stockpickers.kmp.domain.RefreshTickerProfileUseCase
import app.stockpickers.kmp.navigation.AppNavKey
import app.stockpickers.kmp.presentation.TickerDetailViewModel
import org.koin.mp.KoinPlatform

/**
 * Factory for the SHARED [TickerDetailViewModel], called from SwiftUI.
 *
 * With SKIE enabled, Swift observes `viewModel.uiState` directly as an
 * `AsyncSequence` (`for await state in vm.uiState`) and reads `.value` for the
 * first frame — no hand-written Flow collector needed. The ViewModel's
 * `stateIn(started = WhileSubscribed(5s))` tears down the upstream Room flow once
 * SwiftUI stops iterating (its Task is cancelled in `.onDisappear`).
 *
 * NOT named `init*`: the Kotlin/Native Obj-C exporter mangles that function family
 * (it would reach Swift as `doTickerDetailViewModel`). A plain verb-noun name and
 * class constructors both cross unmangled.
 */
fun tickerDetailViewModel(ticker: String): TickerDetailViewModel {
    val koin = KoinPlatform.getKoin()
    return TickerDetailViewModel(
        navKey = AppNavKey.TickerDetail(ticker),
        getTickerDetail = koin.get<GetTickerDetailUseCase>(),
        observePriceSeries = koin.get<ObservePriceSeriesUseCase>(),
        observeTickerProfile = koin.get<ObserveTickerProfileUseCase>(),
        refreshPriceSeries = koin.get<RefreshPriceSeriesUseCase>(),
        refreshTickerProfile = koin.get<RefreshTickerProfileUseCase>(),
    )
}
