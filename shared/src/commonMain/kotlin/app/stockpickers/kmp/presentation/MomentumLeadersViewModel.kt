package app.stockpickers.kmp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.GetGeoCountsUseCase
import app.stockpickers.kmp.domain.GetMomentumLeadersUseCase
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.ObserveLastSyncedAtUseCase
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.domain.RefreshTickersUseCase
import app.stockpickers.kmp.domain.Ticker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MomentumLeadersUiState(
    /** Ranking key. "Forza" (clenow) is the default, mirroring the web's first tab. */
    val sort: LeaderSort = LeaderSort.STRENGTH,
    val geo: GeoFilter = GeoFilter.ALL,
    /** Qualifying pool size per chip — NOT the length of [leaders], which is a top-N. */
    val counts: GeoCounts = GeoCounts(),
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
    private val getMomentumLeaders: GetMomentumLeadersUseCase,
    private val getGeoCounts: GetGeoCountsUseCase,
    private val observeLastSyncedAt: ObserveLastSyncedAtUseCase,
    private val refreshTickers: RefreshTickersUseCase,
) : ViewModel() {

    private val selection = MutableStateFlow(Selection())
    private val refreshState = MutableStateFlow(RefreshUiState())

    /**
     * Stale-while-revalidate: the state is driven by Room (cache shows instantly),
     * while [refresh] updates the same table in the background.
     *
     * The counts re-query on the SORT only — every chip must keep showing its own
     * bucket size while one of them is selected, so they cannot depend on the geo.
     */
    val uiState: StateFlow<MomentumLeadersUiState> = combine(
        selection,
        selection.flatMapLatest { (sort, geo) -> getMomentumLeaders(sort, geo) },
        selection.map { it.sort }.distinctUntilChanged().flatMapLatest { sort -> getGeoCounts(sort) },
        observeLastSyncedAt(),
        refreshState,
    ) { selected, leaders, counts, lastSyncedAt, refresh ->
        MomentumLeadersUiState(
            sort = selected.sort,
            geo = selected.geo,
            counts = counts,
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

    fun selectSort(sort: LeaderSort) {
        selection.value = selection.value.copy(sort = sort)
    }

    fun selectGeo(geo: GeoFilter) {
        selection.value = selection.value.copy(geo = geo)
    }

    fun refresh() {
        if (refreshState.value.isRefreshing) return
        viewModelScope.launch {
            refreshState.value = refreshState.value.copy(isRefreshing = true)
            refreshState.value = when (val result = refreshTickers()) {
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

    /** Tab + chip travel together so the board is ONE flatMapLatest, not two. */
    private data class Selection(
        val sort: LeaderSort = LeaderSort.STRENGTH,
        val geo: GeoFilter = GeoFilter.ALL,
    )

    private data class RefreshUiState(
        val isRefreshing: Boolean = false,
        /** Sticky: only a successful refresh clears it. */
        val isOffline: Boolean = false,
        /** One-shot, for the snackbar. */
        val lastFailure: String? = null,
    )
}
