package app.stockpickers.kmp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

/**
 * The app's NavDisplay: the only composable that renders the back stack.
 *
 * Both decorators are load-bearing:
 *  - saveable state holder — keeps each entry's `rememberSaveable` state (scroll
 *    position, selected tab) across an Android configuration change.
 *  - ViewModel store — gives every NavEntry its own ViewModelStore. Without it
 *    ViewModels fall back to the Activity/UIViewController scope, so two
 *    TickerDetail entries would share one ViewModel and popping would never call
 *    `onCleared`.
 *
 * No BackHandler here (unlike Wishew's Nav3Host on Android): NavDisplay wires the
 * platform back signal to [onBack] itself via navigationevent-compose, on Android
 * AND on iOS. Adding a BackHandler on top would pop twice.
 */
@Composable
fun Nav3Host(navigator: Navigator, modifier: Modifier = Modifier) {
    NavDisplay(
        backStack = navigator.backStack,
        onBack = { navigator.goBack() },
        modifier = modifier,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider { appEntries(navigator) },
    )
}
