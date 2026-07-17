package app.stockpickers.kmp.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every destination in the app, as type-safe serializable keys.
 *
 * Grouped under one sealed interface so `when` over a key is exhaustive and
 * adding a destination is a compile error everywhere it must be handled.
 *
 * DELIBERATELY OUTSIDE `ui.navigation`, in a package the presentation layer may
 * depend on: ViewModels receive their whole NavKey as a constructor parameter
 * (see TickerDetailViewModel). Keeping the keys next to the NavDisplay wiring
 * would force `presentation` to depend on `ui` and invert the layering. Same
 * reason Wishew puts NavKeys in `core/common` rather than in the app module.
 *
 * `@Serializable` is not decoration: `rememberNavigator` persists the back stack
 * through `rememberNavBackStack`, and on non-JVM targets (iOS) each subclass must
 * ALSO be registered in the polymorphic SerializersModule in NavigationState.kt.
 * Adding a key here without registering it there compiles fine and fails at
 * runtime on iOS only.
 */
@Serializable
sealed interface AppNavKey : NavKey {

    /** Start destination: the momentum leaders board. */
    @Serializable
    data object Leaders : AppNavKey

    /**
     * Read-out of a single scanner row.
     *
     * Carries only the primary key, never a whole [app.stockpickers.kmp.domain.Ticker]:
     * the row is re-read from Room by the ViewModel, so the screen stays correct
     * after a background sync instead of rendering a frozen copy.
     */
    @Serializable
    data class TickerDetail(val ticker: String) : AppNavKey
}
