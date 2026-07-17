package app.stockpickers.kmp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stockpickers.kmp.domain.GetMomentumLeadersUseCase
import app.stockpickers.kmp.domain.MomentumWindow
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.domain.TickerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MomentumLeadersUiState(
    val window: MomentumWindow = MomentumWindow.ONE_MONTH,
    val leaders: List<Ticker> = emptyList(),
    /** True until the first cache emission arrives. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /** Last refresh failed → we are serving cache only. */
    val isOffline: Boolean = false,
    val lastSyncedAt: Long? = null,
    val errorMessage: String? = null,
) {
    /** Nothing cached AND nothing loading → genuine empty state. */
    val isEmpty: Boolean get() = !isLoading && leaders.isEmpty()
    /** Never synced and no data: the only case where an error should take over the screen. */
    val isFatal: Boolean get() = isEmpty && lastSyncedAt == null && errorMessage != null
}

@OptIn(ExperimentalCoroutinesApi::class)
class MomentumLeadersViewModel(
    private val repository: TickerRepository,
    private val getMomentumLeaders: GetMomentumLeadersUseCase,
) : ViewModel() {

    private val selectedWindow = MutableStateFlow(MomentumWindow.ONE_MONTH)
    private val refreshState = MutableStateFlow(RefreshUiState())

    /**
     * Stale-while-revalidate: the state is driven by Room (cache shows instantly),
     * while [refresh] updates the same table in the background.
     */
    val uiState: StateFlow<MomentumLeadersUiState> = combine(
        selectedWindow,
        selectedWindow.flatMapLatest { window -> getMomentumLeaders(window) },
        repository.observeLastSyncedAt(),
        refreshState,
    ) { window, leaders, lastSyncedAt, refresh ->
        MomentumLeadersUiState(
            window = window,
            leaders = leaders,
            isLoading = false,
            isRefreshing = refresh.isRefreshing,
            isOffline = refresh.isOffline,
            lastSyncedAt = lastSyncedAt,
            errorMessage = refresh.lastFailure,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MomentumLeadersUiState(),
    )

    init {
        refresh()
    }

    fun selectWindow(window: MomentumWindow) {
        selectedWindow.value = window
    }

    fun refresh() {
        if (refreshState.value.isRefreshing) return
        viewModelScope.launch {
            refreshState.value = refreshState.value.copy(isRefreshing = true)
            refreshState.value = when (val result = repository.refresh()) {
                is RefreshResult.Success ->
                    RefreshUiState(isRefreshing = false, isOffline = false, lastFailure = null)
                is RefreshResult.Failed ->
                    RefreshUiState(isRefreshing = false, isOffline = true, lastFailure = result.message)
            }
        }
    }

    /**
     * Dismisses the one-shot error message only. [RefreshUiState.isOffline] is
     * deliberately NOT cleared here: the banner is transient, but the offline
     * badge must stay up until a sync actually succeeds.
     */
    fun dismissError() {
        refreshState.value = refreshState.value.copy(lastFailure = null)
    }

    private data class RefreshUiState(
        val isRefreshing: Boolean = false,
        /** Sticky: only a successful refresh clears it. */
        val isOffline: Boolean = false,
        /** One-shot, for the snackbar. */
        val lastFailure: String? = null,
    )
}
