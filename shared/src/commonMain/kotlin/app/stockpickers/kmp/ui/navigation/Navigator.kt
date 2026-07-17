package app.stockpickers.kmp.ui.navigation

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.stockpickers.kmp.navigation.AppNavKey

/**
 * Owns the back stack and is the ONLY thing allowed to mutate it.
 *
 * "You own the back stack" is Navigation 3's whole point, but that ownership has
 * to live somewhere specific or every screen starts calling `backStack.add(...)`
 * by hand. Screens get `goTo`/`goBack` and never see the list.
 *
 * Not part of the AndroidX library — a project pattern (same one as Wishew).
 */
@Stable
class Navigator(
    /** Exposed only so [Nav3Host] can hand it to NavDisplay to observe. */
    val backStack: NavBackStack<NavKey>,
) {
    /** Top of the stack — the destination currently on screen. */
    val currentKey: NavKey?
        get() = backStack.lastOrNull()

    /** False at the root, where back must fall through to the platform. */
    val canGoBack: Boolean
        get() = backStack.size > 1

    /** Push a destination. */
    fun goTo(key: AppNavKey) {
        backStack.add(key)
    }

    /**
     * Pop one destination.
     *
     * @return true if handled; false at the root, where the caller must let the
     *   platform act (Android: finish the Activity; iOS: nothing).
     */
    fun goBack(): Boolean {
        if (!canGoBack) return false
        backStack.removeLastOrNull()
        return true
    }
}
