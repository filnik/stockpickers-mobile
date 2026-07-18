package app.stockpickers.kmp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.GetTickerDetailUseCase
import app.stockpickers.kmp.domain.ObservePriceSeriesUseCase
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.RefreshPriceSeriesUseCase
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.navigation.AppNavKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TickerDetailUiState(
    val detail: TickerDetail? = null,
    /** Cached price history for the SELECTED range; null = uncached or no data → placeholder. */
    val priceSeries: PriceSeries? = null,
    /** The chart range chip currently selected (TradingView-style 1D…1Y). */
    val selectedRange: ChartRange = ChartRange.DEFAULT,
    /** True until Room's first emission for this ticker arrives. */
    val isLoading: Boolean = true,
    /**
     * A refresh for the selected range is in flight. Lets the chart show a soft
     * loader (instead of the "no data" placeholder) while a freshly-picked, not-yet
     * cached range loads — so switching chips does not flicker to the empty state.
     */
    val isChartLoading: Boolean = false,
) {
    /**
     * Cache miss: Room answered and the row is not there. Distinct from
     * [isLoading] — the screen must not show a spinner forever for a ticker that
     * was dropped by the last sync.
     */
    val isMissing: Boolean get() = !isLoading && detail == null
}

/** One-shot events. Never state: they must fire once, not survive recomposition. */
sealed interface TickerDetailSideEffect {
    data object NavigateBack : TickerDetailSideEffect
}

/**
 * @param navKey the whole navigation key, not a bare ticker string: adding a
 *   parameter to the destination then becomes a compile-time change instead of a
 *   silently-reordered `parametersOf` argument.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TickerDetailViewModel(
    private val navKey: AppNavKey.TickerDetail,
    getTickerDetail: GetTickerDetailUseCase,
    observePriceSeries: ObservePriceSeriesUseCase,
    private val refreshPriceSeries: RefreshPriceSeriesUseCase,
) : ViewModel() {

    private val _sideEffect = Channel<TickerDetailSideEffect>(Channel.BUFFERED)
    val sideEffect: Flow<TickerDetailSideEffect> = _sideEffect.receiveAsFlow()

    /** The chart's currently selected time window. Drives BOTH the observed Room
     *  series (via [flatMapLatest]) and the background refresh (in [init]). */
    private val selectedRange = MutableStateFlow(ChartRange.DEFAULT)

    /** True while the selected range is being refreshed from Yahoo. */
    private val isChartLoading = MutableStateFlow(false)

    /**
     * Driven entirely by Room (the scanner row and the cached price series), so a
     * background refresh re-renders this screen for free. The price series follows
     * [selectedRange]: picking a chip re-points the observed Room row to that range
     * (network-free), while the fetch for it runs in [init].
     */
    val uiState: StateFlow<TickerDetailUiState> = combine(
        getTickerDetail(navKey.ticker),
        selectedRange.flatMapLatest { range -> observePriceSeries(navKey.ticker, range) },
        selectedRange,
        isChartLoading,
    ) { detail, priceSeries, range, chartLoading ->
        TickerDetailUiState(
            detail = detail,
            priceSeries = priceSeries,
            selectedRange = range,
            isLoading = false,
            isChartLoading = chartLoading,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TickerDetailUiState(),
    )

    init {
        // Refresh the price series whenever the selected range changes — including the
        // initial default emission. `collectLatest` cancels an in-flight fetch when the
        // user re-taps quickly, so only the last-picked range is fetched. The use case
        // is TTL-throttled and never throws, so a 429 / offline just leaves the chart
        // on its cached (or empty) state.
        viewModelScope.launch {
            selectedRange.collectLatest { range ->
                isChartLoading.value = true
                try {
                    refreshPriceSeries(navKey.ticker, range)
                } finally {
                    isChartLoading.value = false
                }
            }
        }
    }

    /**
     * Selects the chart's time window. Public + a plain verb-noun name so SwiftUI can
     * call it directly (the iOS segmented picker wires to this). No-ops on the current
     * range so re-tapping neither re-fetches nor flickers the loader.
     */
    fun selectRange(range: ChartRange) {
        if (selectedRange.value == range) return
        // Flip the loader up-front (synchronously) so the range switch never shows a
        // frame of the "no data" placeholder before the collector reacts.
        isChartLoading.value = true
        selectedRange.value = range
    }

    fun onBackClick() {
        viewModelScope.launch { _sideEffect.send(TickerDetailSideEffect.NavigateBack) }
    }
}
