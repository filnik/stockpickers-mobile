package app.stockpickers.kmp.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.stockpickers.kmp.ui.navigation.Navigator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Navigation 3 is "you own the back stack", so the back stack is a plain list and
 * [Navigator] is testable without any Compose runtime — mirroring Wishew's
 * NavigatorTest. `NavBackStack` has a public vararg constructor; no serialization
 * setup is needed because these tests never persist the stack.
 */
class NavigatorTest {

    private fun navigator(): Navigator =
        Navigator(NavBackStack<NavKey>(AppNavKey.Leaders))

    @Test
    fun WHEN_created_THEN_leaders_is_the_root_and_back_is_unavailable() {
        val navigator = navigator()
        assertEquals(AppNavKey.Leaders, navigator.currentKey)
        assertFalse(navigator.canGoBack)
        assertEquals(1, navigator.backStack.size)
    }

    @Test
    fun WHEN_goTo_TickerDetail_THEN_the_key_is_pushed_onto_the_back_stack() {
        val navigator = navigator()

        navigator.goTo(AppNavKey.TickerDetail("AAPL"))

        assertEquals(AppNavKey.TickerDetail("AAPL"), navigator.currentKey)
        assertTrue(navigator.canGoBack)
        assertEquals(2, navigator.backStack.size)
    }

    @Test
    fun WHEN_goBack_from_detail_THEN_the_key_is_popped_and_it_returns_true() {
        val navigator = navigator()
        navigator.goTo(AppNavKey.TickerDetail("AAPL"))

        val handled = navigator.goBack()

        assertTrue(handled)
        assertEquals(AppNavKey.Leaders, navigator.currentKey)
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun WHEN_goBack_at_the_root_THEN_it_is_a_no_op_returning_false() {
        val navigator = navigator()

        val handled = navigator.goBack()

        assertFalse(handled) // falls through to the platform instead of crashing
        assertEquals(AppNavKey.Leaders, navigator.currentKey)
        assertEquals(1, navigator.backStack.size)
    }
}
