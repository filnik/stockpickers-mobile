package app.stockpickers.kmp.navigation

import androidx.navigation3.runtime.NavKey
import app.stockpickers.kmp.ui.navigation.NavSavedStateConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test

/**
 * Guards the one failure in this codebase that no other check can reach.
 *
 * `rememberNavBackStack(vararg NavKey)` — the overload needing no manual
 * registration — lives in `RememberNavBackStack.android.kt` and reflects over
 * subclasses at runtime, which Kotlin/Native cannot do. commonMain therefore only
 * sees the overload taking a `SavedStateConfiguration`, and every [AppNavKey]
 * subclass has to be listed by hand in `NavigationState.kt`.
 *
 * Forget one and it compiles, passes Android, passes even the iOS framework LINK,
 * then throws `SerializationException: Class 'X' is not registered for polymorphic
 * serialization` — on iOS only, at runtime, and only once the back stack is saved.
 * This runs on both targets, so it fails on the JVM long before a device sees it.
 *
 * Two mechanisms, deliberately:
 *  1. [allKeys] is closed by an exhaustive `when`, so **adding a subclass stops this
 *     file compiling** — whoever adds a key is forced to open it.
 *  2. Each listed key is looked up in the REAL module the app installs. That lookup
 *     is precisely the one that throws in production when a registration is missing.
 */
class AppNavKeySerializationTest {

    /**
     * One instance per destination. The `when` is what makes this exhaustive: it is
     * evaluated as an expression, so a new [AppNavKey] subclass is a compile error
     * here until it is listed — and listing it is what drags the author into the
     * registration in `NavigationState.kt`.
     */
    private val allKeys: List<AppNavKey> = listOf(
        AppNavKey.Leaders,
        AppNavKey.TickerDetail(ticker = "NVDA"),
    ).onEach { key ->
        when (key) {
            is AppNavKey.Leaders -> Unit
            is AppNavKey.TickerDetail -> Unit
        }
    }

    private val module = NavSavedStateConfiguration.serializersModule

    @Test
    fun WHEN_a_nav_key_is_looked_up_polymorphically_THEN_the_module_resolves_it() {
        allKeys.forEach { key ->
            withClue(
                "${key::class.simpleName} is missing from the polymorphic SerializersModule in " +
                    "NavigationState.kt. Android is unaffected; iOS throws SerializationException " +
                    "at runtime the first time the back stack is saved.",
            ) {
                module.getPolymorphic(NavKey::class, key).shouldNotBeNull()
            }
        }
    }

    private val json = Json { serializersModule = module }

    /**
     * The serializer is named EXPLICITLY rather than reified as `<NavKey>`.
     *
     * `NavKey` is a plain interface from navigation3, not `@Serializable`, so a
     * reified lookup resolves by reflection on the JVM and simply fails on
     * Kotlin/Native with "Serializer for class 'NavKey' is not found". Writing the
     * test the convenient way therefore passes on Android and fails on iOS — the
     * very asymmetry this file exists to catch.
     */
    private val polymorphic = PolymorphicSerializer(NavKey::class)

    /** The registration existing is not enough — the payload has to survive it too. */
    @Test
    fun WHEN_a_key_carries_arguments_THEN_they_survive_a_polymorphic_round_trip() {
        val key: NavKey = AppNavKey.TickerDetail(ticker = "2330.TW")

        val decoded = json.decodeFromString(polymorphic, json.encodeToString(polymorphic, key))

        (decoded as AppNavKey.TickerDetail).ticker shouldBe "2330.TW"
    }

    @Test
    fun WHEN_every_key_is_round_tripped_THEN_it_comes_back_equal() {
        allKeys.forEach { key ->
            val decoded = json.decodeFromString(polymorphic, json.encodeToString(polymorphic, key))
            withClue("${key::class.simpleName} did not survive a save/restore round trip") {
                decoded shouldBe key
            }
        }
    }
}
