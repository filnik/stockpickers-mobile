# Adding a screen

The complete list of files a new destination touches. Two of these steps fail
**only on iOS, only at runtime**, which is why the checklist exists rather than
living in someone's head.

---

## The checklist

### 1. NavKey — `navigation/NavKeys.kt`

```kotlin
@Serializable
data class Watchlist(val listId: String) : AppNavKey
```

`@Serializable` is not decoration: the back stack is persisted through it.

### 2. Register it in the `SerializersModule` — `ui/navigation/NavigationState.kt`

```kotlin
subclass(AppNavKey.Watchlist::class, AppNavKey.Watchlist.serializer())
```

**⚠️ Miss this and it compiles, passes Android, passes the iOS framework link, and
throws `SerializationException` on iOS at runtime the first time the stack is
saved.** `AppNavKeySerializationTest` stops compiling until you list the new key,
which is the nudge to come here. See [NAVIGATION.md §2](NAVIGATION.md).

### 3. ViewModel — `presentation/WatchlistViewModel.kt`

```kotlin
@KoinViewModel
class WatchlistViewModel(
    @InjectedParam private val navKey: AppNavKey.Watchlist,
    private val observeWatchlist: ObserveWatchlistUseCase,
) : ViewModel()
```

- `@KoinViewModel` from `org.koin.core.annotation`.
- `@InjectedParam` on the NavKey — without it the graph check looks for a binding
  that will never exist.
- **Take the whole NavKey**, never loose primitives.
- State is a single immutable `WatchlistUiState` with derived properties; one-shot
  events go through a `Channel`. See [ARCHITECTURE.md §4](ARCHITECTURE.md).

### 4. Use cases — `domain/`

One action each, `@Single`, `operator fun invoke(...)`. **No repository injected
into the ViewModel**, ever.

Nothing to register by hand: `@ComponentScan` picks them up.

### 5. Screen — `ui/WatchlistScreen.kt`

**Two overloads**, always:

```kotlin
@Composable
fun WatchlistScreen(viewModel: WatchlistViewModel, onBack: () -> Unit, modifier: Modifier = Modifier)

@Composable
fun WatchlistScreen(state: WatchlistUiState, onBack: () -> Unit, modifier: Modifier = Modifier)
```

The stateless one must be **free of Koin** — it is what the screenshot tests render.

If a composable already exists in `ui/Components.kt`, use it. If you are about to
copy one from another screen, move it there instead.

### 6. Entry — `ui/navigation/EntryProvider.kt`

```kotlin
entry<AppNavKey.Watchlist> { key ->
    val viewModel: WatchlistViewModel = koinViewModel { parametersOf(key) }
    WatchlistScreen(viewModel = viewModel, onBack = { navigator.goBack() })
}
```

**Resolve inside the `entry<>` block** — that is what scopes the ViewModel per
NavEntry. Never a defaulted `koinViewModel()` parameter on the screen.

### 7. Strings — **both** locales

`values/strings.xml` first (accessors are generated from the default only), then
`values-it/strings.xml`. Content descriptions get the `cd_` prefix. See
[I18N.md](I18N.md).

### 8. Navigate to it

```kotlin
navigator.goTo(AppNavKey.Watchlist(listId = "default"))
```

### 9. Tests

- ViewModel test in `commonTest`, extending `ViewModelTest`, real use cases over
  `FakeTickerRepository`. See [TESTING.md §5](TESTING.md).
- A Roborazzi baseline per branch of the screen's render `when` — at minimum
  loading, data, and empty.
- `AppNavKeySerializationTest` needs the new key added to its exhaustive list.

### 10. Verify — all four

```bash
./gradlew spotlessCheck detekt
./gradlew :composeApp:assembleDebug
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :shared:allTests
./gradlew :composeApp:recordRoborazziDebug   # then commit the new goldens
```

**The iOS link is not optional.** Android compiling proves nothing about
Kotlin/Native, and step 2's failure mode lives there.

---

## What has changed, and what has not

With Koin Annotations, a forgotten binding is now a **compile error**
(`KOIN-D001`), not a runtime crash — so step 3 and 4 are much harder to get wrong
than they used to be.

Step 2 has no such safety net. It remains the one place where a correct-looking
change ships a broken iOS app, and the only reason this file is a checklist rather
than a paragraph.
