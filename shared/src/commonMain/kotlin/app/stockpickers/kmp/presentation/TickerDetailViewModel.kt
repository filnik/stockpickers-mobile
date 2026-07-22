package app.stockpickers.kmp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.GetTickerDetailUseCase
import app.stockpickers.kmp.domain.ObservePriceSeriesUseCase
import app.stockpickers.kmp.domain.ObserveTickerProfileUseCase
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.RefreshPriceSeriesUseCase
import app.stockpickers.kmp.domain.RefreshTickerProfileUseCase
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.domain.TickerProfile
import app.stockpickers.kmp.navigation.AppNavKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class TickerDetailUiState(
    val detail: TickerDetail? = null,
    /** Cached price history for the SELECTED range; null = uncached or no data → placeholder. */
    val priceSeries: PriceSeries? = null,
    /**
     * The written profile, or null when there is none to show — which is the ORDINARY
     * case, since upstream only covers part of the universe. There is deliberately no
     * companion loading flag: a spinner over a block of prose is noise, so the card
     * simply appears once the text lands.
     */
    val profile: TickerProfile? = null,
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
@KoinViewModel
class TickerDetailViewModel(
    // Handed over by the EntryProvider's `parametersOf(key)` rather than resolved
    // from the graph — @InjectedParam is what tells the processor not to look for
    // an AppNavKey.TickerDetail binding that will never exist.
    @InjectedParam private val navKey: AppNavKey.TickerDetail,
    getTickerDetail: GetTickerDetailUseCase,
    observePriceSeries: ObservePriceSeriesUseCase,
    observeTickerProfile: ObserveTickerProfileUseCase,
    private val refreshPriceSeries: RefreshPriceSeriesUseCase,
    private val refreshTickerProfile: RefreshTickerProfileUseCase,
) : ViewModel() {

    private val _sideEffect = Channel<TickerDetailSideEffect>(Channel.BUFFERED)
    val sideEffect: Flow<TickerDetailSideEffect> = _sideEffect.receiveAsFlow()

    /**
     * The chart's own state — the one part of this screen Room does NOT own. Window
     * and loader travel together because [selectRange] moves both at once: as two
     * separate flows they were two emissions, and the UI could render the frame in
     * between, flashing the "no data" placeholder for a range whose fetch had not
     * been marked in flight yet.
     */
    private val chartState = MutableStateFlow(ChartUiState())

    /**
     * Range changes only. Both consumers below re-run whole operations per range — a
     * Room re-subscription and a network fetch — and must NOT see the loader flipping;
     * [init]'s collector sets that flag itself, so without `distinctUntilChanged` it
     * would retrigger on its own write and never stop.
     */
    private val selectedRange: Flow<ChartRange> = chartState.map { it.range }.distinctUntilChanged()

    /**
     * Driven entirely by Room (the scanner row, the cached price series and the cached
     * profile), so a background refresh re-renders this screen for free. The price
     * series follows [selectedRange]: picking a chip re-points the observed Room row to
     * that range (network-free), while the fetch for it runs in [init].
     *
     * EVERY source here must emit without waiting for the network. `combine` produces
     * nothing until all four have emitted at least once, so a flow that only emitted
     * after a fetch would pin the whole screen on `isLoading = true` — forever, offline.
     * The three Room flows emit null immediately for an absent row and [chartState]
     * starts from a default; that is the property the screen's loading state rests on,
     * and it is what `TickerDetailViewModelTest` guards.
     */
    val uiState: StateFlow<TickerDetailUiState> = combine(
        getTickerDetail(navKey.ticker),
        selectedRange.flatMapLatest { range -> observePriceSeries(navKey.ticker, range) },
        observeTickerProfile(navKey.ticker),
        chartState,
    ) { detail, priceSeries, profile, chart ->
        TickerDetailUiState(
            detail = detail,
            priceSeries = priceSeries,
            profile = profile,
            selectedRange = chart.range,
            isLoading = false,
            isChartLoading = chart.isLoading,
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
                chartState.value = chartState.value.copy(isLoading = true)
                try {
                    refreshPriceSeries(navKey.ticker, range)
                } finally {
                    chartState.value = chartState.value.copy(isLoading = false)
                }
            }
        }

        // Warm the OTHER ranges in the background so switching chips is instant. The
        // default (daily) range's fetch above already warms all four daily ranges in
        // one call (1Y sliced), so here we only prefetch the two intraday ranges.
        // Fire-and-forget: failures are swallowed by the use case; the user sees a
        // loader only if they pick a range before its prefetch lands.
        for (range in ChartRange.entries.filter { it.isIntraday }) {
            viewModelScope.launch { refreshPriceSeries(navKey.ticker, range) }
        }

        // The written profile: once per screen, not per range. Fire-and-forget like the
        // chart — TTL-throttled and non-throwing in the repository, so offline simply
        // leaves whatever is cached (usually nothing) on screen.
        viewModelScope.launch { refreshTickerProfile(navKey.ticker) }
    }

    /**
     * Selects the chart's time window. Public + a plain verb-noun name so SwiftUI can
     * call it directly (the iOS segmented picker wires to this). No-ops on the current
     * range so re-tapping neither re-fetches nor flickers the loader.
     */
    fun selectRange(range: ChartRange) {
        if (chartState.value.range == range) return
        // Range and loader in ONE emission, synchronously: the switch must never show
        // a frame of the "no data" placeholder before the collector below reacts.
        chartState.value = ChartUiState(range = range, isLoading = true)
    }

    fun onBackClick() {
        viewModelScope.launch { _sideEffect.send(TickerDetailSideEffect.NavigateBack) }
    }

    /** The chart state this ViewModel owns, as opposed to what it observes from Room. */
    private data class ChartUiState(val range: ChartRange = ChartRange.DEFAULT, val isLoading: Boolean = false)
}
