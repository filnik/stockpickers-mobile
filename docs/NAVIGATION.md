# Navigation 3

Navigation 3 on Compose Multiplatform, using the "you own the back stack" model.
Read this before adding a destination — **one of the steps has a failure that only
appears on iOS, at runtime.**

Version: JetBrains CMP port `org.jetbrains.androidx.navigation3` 1.2.0-alpha02.

---

## 1. Adding a destination — the checklist

1. Add the key to `AppNavKey` (`navigation/NavKeys.kt`), `@Serializable`.
2. **Register it in the polymorphic `SerializersModule` in `NavigationState.kt`.**
   ← the one that fails on iOS only, see §2.
3. Add an entry in `EntryProvider.kt`, resolving the ViewModel **inside** the
   `entry<>` block.
4. Add the strings to **both** `values/` and `values-it/` — see [I18N.md](I18N.md).
5. Give the screen a stateful and a stateless overload.
6. Run `:composeApp:assembleDebug` **and**
   `:shared:linkDebugFrameworkIosSimulatorArm64`.

`AppNavKeySerializationTest` covers step 2: it stops compiling when a subclass is
added and unlisted, and fails on both platforms if the registration is missing.

---

## 2. The iOS-only crash

`rememberNavBackStack(vararg NavKey)` — the overload that needs no manual
registration — is declared in `RememberNavBackStack.android.kt`. It **reflects over
subclasses at runtime**, which Kotlin/Native cannot do.

commonMain therefore only sees the overload taking a `SavedStateConfiguration`, and
every `AppNavKey` subclass must be registered by hand:

```kotlin
internal val NavSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(AppNavKey.Leaders::class, AppNavKey.Leaders.serializer())
            subclass(AppNavKey.TickerDetail::class, AppNavKey.TickerDetail.serializer())
        }
    }
}
```

Forget one and it:

- compiles,
- passes every Android check,
- passes even `linkDebugFrameworkIosSimulatorArm64`,
- then throws `SerializationException: Class 'X' is not registered for polymorphic
  serialization` **on iOS, at runtime, only once the back stack is actually saved**.

That is the longest feedback loop in the project, which is why it has a dedicated
test.

> Related trap, same family: a reified `serializer<NavKey>()` resolves by reflection
> on the JVM and throws `Serializer for class 'NavKey' is not found` on Native.
> `NavKey` is a plain interface, not `@Serializable`. Name the serializer explicitly
> (`PolymorphicSerializer(NavKey::class)`) in any code that has to touch it.

---

## 3. The files

```
navigation/NavKeys.kt              AppNavKey — every destination
ui/navigation/Navigator.kt         owns the back stack; goTo / goBack
ui/navigation/NavigationState.kt   rememberNavigator() + the SerializersModule
ui/navigation/EntryProvider.kt     NavKey → composable
ui/navigation/Nav3Host.kt          NavDisplay + decorators
```

**`navigation` sits outside `ui` on purpose.** ViewModels take their NavKey as a
constructor parameter, so `presentation` must be able to depend on the keys. Putting
them in `ui.navigation` would invert the layering.

**Only `Navigator` mutates the back stack.** Screens get `goTo`/`goBack` and never
touch the list. `goBack` returns `false` at the root rather than throwing, so the
platform can handle it.

---

## 4. ViewModels

Resolve **inside** the `entry<>` block, always:

```kotlin
entry<AppNavKey.TickerDetail> { key ->
    val viewModel: TickerDetailViewModel = koinViewModel { parametersOf(key) }
    TickerDetailScreen(viewModel = viewModel, onNavigateBack = { navigator.goBack() })
}
```

That is what lets `rememberViewModelStoreNavEntryDecorator` scope the ViewModel per
NavEntry: two different `TickerDetail` keys get two different ViewModels, and
popping one clears it.

**Do not** give a screen a defaulted `viewModel: XxxViewModel = koinViewModel()`
parameter. It works, but it resolves outside the entry scope and quietly opts out of
that scoping.

**Pass the whole key**, never loose primitives. Adding a field to the destination
then stays a compile-time change instead of a silently reordered `parametersOf`
argument. On the ViewModel side the parameter is marked `@InjectedParam` — see
[KOIN.md](KOIN.md).

---

## 5. CMP-specific gotchas

- **`androidx.navigation3:navigation3-ui` is Android-only.** It publishes only
  `-android` and `*stubs` variants. The UI layer must come from the JetBrains port.
  (`navigation3-runtime` *is* multiplatform and arrives transitively — do not
  declare it separately.)
- **The entry DSL scope is `EntryProviderScope<T>`, not `EntryProviderBuilder<T>`.**
  Renamed; most docs and blog posts still say Builder.
- **The decorators** are `rememberSaveableStateHolderNavEntryDecorator()` (in
  `navigation3.runtime`) paired with `rememberViewModelStoreNavEntryDecorator()` (in
  `lifecycle.viewmodel.navigation3`).
- **Do NOT add a `BackHandler` around `NavDisplay`.** It already wires the platform
  back signal to `onBack` via `navigationevent-compose`, on Android *and* iOS.
  Adding one pops twice. (Some Android-only, Nav2-era codebases do add one — that
  habit does not port.)
- **Pin the exact alpha.** The JetBrains port is `1.2.0-alpha02` while AndroidX is
  stable at 1.1.4; breaking changes land between alphas.

---

## 6. Testing navigation

`Navigator` is plain list manipulation — no Compose runtime needed:

```kotlin
val navigator = Navigator(NavBackStack<NavKey>(AppNavKey.Leaders))
navigator.goTo(AppNavKey.TickerDetail("NVDA"))
assertTrue(navigator.canGoBack)
```

See `NavigatorTest` and `AppNavKeySerializationTest`.

---

## 7. When a bottom bar arrives

`NavigationState.kt` is the file that grows a `Map<Tab, NavBackStack>` and an
"exit through home" rule. Nothing else has to change, because nothing else touches
the stack. There is deliberately no multi-tab machinery today: this app has one
stack, Leaders → TickerDetail.
