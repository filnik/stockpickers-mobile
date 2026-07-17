package app.stockpickers.kmp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stockpickers.kmp.domain.GetTickerDetailUseCase
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.navigation.AppNavKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TickerDetailUiState(
    val detail: TickerDetail? = null,
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
) : ViewModel() {

    private val _sideEffect = Channel<TickerDetailSideEffect>(Channel.BUFFERED)
    val sideEffect: Flow<TickerDetailSideEffect> = _sideEffect.receiveAsFlow()

    /**
     * Driven entirely by Room, so a background refresh started on the leaders
     * screen re-renders this one for free. No network call is made from here.
     */
    val uiState: StateFlow<TickerDetailUiState> = getTickerDetail(navKey.ticker)
        .map { detail -> TickerDetailUiState(detail = detail, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TickerDetailUiState(),
        )

    fun onBackClick() {
        viewModelScope.launch { _sideEffect.send(TickerDetailSideEffect.NavigateBack) }
    }
}
