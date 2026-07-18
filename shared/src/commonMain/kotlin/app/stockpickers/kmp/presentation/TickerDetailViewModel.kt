package app.stockpickers.kmp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stockpickers.kmp.domain.GetTickerDetailUseCase
import app.stockpickers.kmp.domain.ObservePriceSeriesUseCase
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.RefreshPriceSeriesUseCase
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.navigation.AppNavKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TickerDetailUiState(
    val detail: TickerDetail? = null,
    /** Cached price history for the chart; null = uncached or no data → placeholder. */
    val priceSeries: PriceSeries? = null,
    /** True until Room's first emission for this ticker arrives. */
    val isLoading: Boolean = true,
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
class TickerDetailViewModel(
    private val navKey: AppNavKey.TickerDetail,
    getTickerDetail: GetTickerDetailUseCase,
    observePriceSeries: ObservePriceSeriesUseCase,
    private val refreshPriceSeries: RefreshPriceSeriesUseCase,
) : ViewModel() {

    private val _sideEffect = Channel<TickerDetailSideEffect>(Channel.BUFFERED)
    val sideEffect: Flow<TickerDetailSideEffect> = _sideEffect.receiveAsFlow()

    /**
     * Driven entirely by Room (both the scanner row and the cached price series),
     * so a background refresh re-renders this screen for free. The scanner detail
     * itself is never fetched from here; only the chart's price series is, in
     * [init] below.
     */
    val uiState: StateFlow<TickerDetailUiState> = combine(
        getTickerDetail(navKey.ticker),
        observePriceSeries(navKey.ticker),
    ) { detail, priceSeries ->
        TickerDetailUiState(detail = detail, priceSeries = priceSeries, isLoading = false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TickerDetailUiState(),
    )

    init {
        // Fire-and-forget: the use case is TTL-throttled and never throws, so a
        // 429 / offline just leaves the chart on its cached (or empty) state.
        viewModelScope.launch { refreshPriceSeries(navKey.ticker) }
    }

    fun onBackClick() {
        viewModelScope.launch { _sideEffect.send(TickerDetailSideEffect.NavigateBack) }
    }
}
