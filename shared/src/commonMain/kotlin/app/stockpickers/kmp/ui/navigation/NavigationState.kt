package app.stockpickers.kmp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.savedstate.serialization.SavedStateConfiguration
import app.stockpickers.kmp.navigation.AppNavKey
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Builds the app's single back stack and the [Navigator] that owns it.
 *
 * There is deliberately no multi-tab NavigationState here (as there is in Wishew):
 * this app has one stack, Leaders -> TickerDetail. When a bottom bar arrives, this
 * is the file that grows a `Map<Tab, NavBackStack>` + the "Exit Through Home"
 * rule — nothing else has to change, because nothing else touches the stack.
 */
@Composable
fun rememberNavigator(): Navigator {
    val backStack = rememberNavBackStack(NavSavedStateConfiguration, AppNavKey.Leaders)
    return remember(backStack) { Navigator(backStack) }
}

/**
 * Teaches the back stack how to serialize [AppNavKey] for persistence.
 *
 * MANDATORY on iOS. `rememberNavBackStack(vararg NavKey)` — the overload that
 * needs none of this — is declared in `RememberNavBackStack.android.kt` and is
 * ANDROID-ONLY: on the JVM it reflects over subclasses at runtime, which
 * Kotlin/Native cannot do. commonMain therefore only sees the overload taking a
 * [SavedStateConfiguration], and every key has to be registered by hand.
 *
 * Every subclass of [AppNavKey] MUST be listed below. A missing one throws
 * `SerializationException: Class 'X' is not registered for polymorphic
 * serialization` at runtime — on iOS only, never on Android, and only once the
 * stack is actually saved. Add a key in NavKeys.kt, add it here.
 */
// `internal`, not private, so AppNavKeySerializationTest can round-trip keys through
// THIS configuration rather than a copy of it — a copy would prove nothing about the
// one the app actually installs.
internal val NavSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(AppNavKey.Leaders::class, AppNavKey.Leaders.serializer())
            subclass(AppNavKey.TickerDetail::class, AppNavKey.TickerDetail.serializer())
        }
    }
}
