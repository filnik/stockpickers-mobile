package app.stockpickers.kmp.presentation

import app.stockpickers.kmp.domain.RefreshFailure
import app.stockpickers.kmp.modelcreators.TickerModelCreator
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The derived properties on the UiStates — the small booleans that decide what the
 * screen actually does.
 *
 * `isFatal` matters most: it is the only condition that replaces the whole board
 * with an error, so getting it wrong either hides a real failure or throws away a
 * perfectly good cache. It is the offline-first promise expressed as a boolean, and
 * it is testable on a plain state object with no ViewModel involved.
 */
class UiStateDerivationsTest {

    // ---- MomentumLeadersUiState ----------------------------------------------

    @Test
    fun WHEN_the_cache_has_not_answered_yet_THEN_the_board_is_not_empty() {
        val state = MomentumLeadersUiState(isLoading = true, leaders = emptyList())

        // Loading and empty are different questions: an empty state here would flash
        // "no results" before Room's first emission.
        state.isEmpty shouldBe false
    }

    @Test
    fun WHEN_room_answered_with_nothing_THEN_the_board_is_empty() {
        val state = MomentumLeadersUiState(isLoading = false, leaders = emptyList())

        state.isEmpty shouldBe true
    }

    @Test
    fun WHEN_there_has_never_been_a_sync_and_nothing_is_cached_THEN_the_error_takes_over() {
        val state = MomentumLeadersUiState(
            isLoading = false,
            leaders = emptyList(),
            lastSyncedAt = null,
            errorMessage = "network down",
        )

        state.isFatal shouldBe true
    }

    /** The offline-first promise: a stale board beats an error screen. */
    @Test
    fun WHEN_a_refresh_fails_but_a_cache_exists_THEN_it_is_never_fatal() {
        val state = MomentumLeadersUiState(
            isLoading = false,
            leaders = TickerModelCreator.list(3),
            lastSyncedAt = null,
            errorMessage = "network down",
        )

        state.isFatal shouldBe false
    }

    /** Having synced before is itself proof the failure is transient. */
    @Test
    fun WHEN_a_previous_sync_succeeded_THEN_a_later_failure_is_never_fatal() {
        val state = MomentumLeadersUiState(
            isLoading = false,
            leaders = emptyList(),
            lastSyncedAt = 1_700_000_000_000L,
            errorMessage = "network down",
        )

        state.isFatal shouldBe false
    }

    /**
     * `isOffline` drives a badge that tells the user to check THEIR connection.
     * Reporting an upstream fault that way sends them to fix a network that works,
     * so it must never stand in for the general "we are serving cache" question.
     */
    @Test
    fun WHEN_the_failure_was_not_a_connectivity_one_THEN_it_is_stale_but_not_offline() {
        val serverFault = MomentumLeadersUiState(refreshFailure = RefreshFailure.SERVER)

        serverFault.isStale shouldBe true
        serverFault.isOffline shouldBe false

        val offline = MomentumLeadersUiState(refreshFailure = RefreshFailure.OFFLINE)

        offline.isStale shouldBe true
        offline.isOffline shouldBe true
    }

    @Test
    fun WHEN_the_last_refresh_succeeded_THEN_the_board_is_neither_stale_nor_offline() {
        val state = MomentumLeadersUiState(refreshFailure = null)

        state.isStale shouldBe false
        state.isOffline shouldBe false
    }

    // ---- TickerDetailUiState --------------------------------------------------

    /**
     * A ticker dropped by the last sync must not spin forever: Room answered, the row
     * is simply gone. Since `refresh()` now prunes deleted rows, this is a state the
     * user can actually reach.
     */
    @Test
    fun WHEN_room_answered_and_the_row_is_absent_THEN_the_detail_is_missing_not_loading() {
        val state = TickerDetailUiState(isLoading = false, detail = null)

        state.isMissing shouldBe true
    }

    @Test
    fun WHEN_the_detail_has_not_loaded_yet_THEN_it_is_not_reported_missing() {
        val state = TickerDetailUiState(isLoading = true, detail = null)

        state.isMissing shouldBe false
    }
}
