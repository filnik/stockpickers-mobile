package app.stockpickers.kmp.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.stockpickers.kmp.ui.navigation.Navigator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Navigation 3 is "you own the back stack", so the back stack is a plain list and
 * [Navigator] is testable without any Compose runtime — mirroring Wishew's
 * NavigatorTest. `NavBackStack` has a public vararg constructor; no serialization
 * setup is needed because these tests never persist the stack.
 */
class NavigatorTest {

    private fun navigator(): Navigator = Navigator(NavBackStack<NavKey>(AppNavKey.Leaders))

    @Test
    fun WHEN_created_THEN_leaders_is_the_root_and_back_is_unavailable() {
        val navigator = navigator()
        navigator.currentKey shouldBe AppNavKey.Leaders
        navigator.canGoBack shouldBe false
        navigator.backStack shouldHaveSize 1
    }

    @Test
    fun WHEN_goTo_TickerDetail_THEN_the_key_is_pushed_onto_the_back_stack() {
        val navigator = navigator()

        navigator.goTo(AppNavKey.TickerDetail("AAPL"))

        navigator.currentKey shouldBe AppNavKey.TickerDetail("AAPL")
        navigator.canGoBack shouldBe true
        navigator.backStack shouldHaveSize 2
    }

    @Test
    fun WHEN_goBack_from_detail_THEN_the_key_is_popped_and_it_returns_true() {
        val navigator = navigator()
        navigator.goTo(AppNavKey.TickerDetail("AAPL"))

        val handled = navigator.goBack()

        handled shouldBe true
        navigator.currentKey shouldBe AppNavKey.Leaders
        navigator.canGoBack shouldBe false
    }

    @Test
    fun WHEN_goBack_at_the_root_THEN_it_is_a_no_op_returning_false() {
        val navigator = navigator()

        val handled = navigator.goBack()

        handled shouldBe false // falls through to the platform instead of crashing
        navigator.currentKey shouldBe AppNavKey.Leaders
        navigator.backStack shouldHaveSize 1
    }
}
