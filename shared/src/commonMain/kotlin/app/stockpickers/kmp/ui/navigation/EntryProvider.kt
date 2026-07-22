package app.stockpickers.kmp.ui.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.stockpickers.kmp.navigation.AppNavKey
import app.stockpickers.kmp.presentation.MomentumLeadersViewModel
import app.stockpickers.kmp.presentation.TickerDetailViewModel
import app.stockpickers.kmp.ui.MomentumLeadersScreen
import app.stockpickers.kmp.ui.TickerDetailScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Maps every [AppNavKey] to its screen. The single place that knows which
 * composable a destination renders.
 *
 * Each ViewModel is resolved INSIDE its `entry` block so the NavEntry-scoped
 * ViewModelStore installed by [Nav3Host]'s decorator picks it up: two different
 * TickerDetail keys therefore get two different ViewModels, and popping one
 * clears it.
 */
fun EntryProviderScope<NavKey>.appEntries(navigator: Navigator) {
    entry<AppNavKey.Leaders> {
        val viewModel: MomentumLeadersViewModel = koinViewModel()
        MomentumLeadersScreen(
            viewModel = viewModel,
            onTickerClick = { ticker -> navigator.goTo(AppNavKey.TickerDetail(ticker)) },
        )
    }

    entry<AppNavKey.TickerDetail> { key ->
        // The whole key is passed, not key.ticker: adding a field to the NavKey
        // then stays a compile-time change rather than a positional-argument bug.
        val viewModel: TickerDetailViewModel = koinViewModel { parametersOf(key) }
        TickerDetailScreen(
            viewModel = viewModel,
            onNavigateBack = { navigator.goBack() },
        )
    }
}
