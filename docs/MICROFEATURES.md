# Micro-Feature architecture

> **Status: reference — not yet used in this codebase.**
> There are no UiModels here today. This page is the playbook for when a piece of
> stateful UI needs a *second* host; §9 names the first candidate and the trigger.
> Do not refactor toward it speculatively.

A pattern for building reusable, **host-agnostic** stateful UI components. Adapted
from the approach popularised by Delivery Hero Tech and carried over from a larger
Android codebase where it earned its place across dozens of screens.

---

## 1. What a micro-feature is

A self-contained UI component that:

- manages its own state independently,
- is **host-agnostic** — it does not know which screen is hosting it,
- talks to its host through side effects rather than callbacks-into-the-void,
- can be composed into any ViewModel or screen.

**Benefits:** reusability across screens, isolated state that is trivial to unit
test, and the ability to change component logic without touching hosts.

---

## 2. The four pieces

```
UiModel (interface)   contract: state + actions
    ↓
UiModelImpl           business logic
    ↓
Factory (class)       composable wrapper + side-effect handling
    ↓
Factory (interface)   DI seam for runtime parameters
```

---

## 3. UiModel

### Interface

```kotlin
interface PriceChartUiModel {
    val uiState: StateFlow<PriceChartUiState>
    val sideEffect: Flow<PriceChartSideEffect>

    fun onRangeSelected(range: ChartRange)
    fun onScrub(epochSeconds: Long?)
    fun retry()
}
```

### UiState

A single immutable data class with sensible defaults:

```kotlin
data class PriceChartUiState(
    val series: PriceSeries? = null,
    val selectedRange: ChartRange = ChartRange.DEFAULT,
    val isLoading: Boolean = false,
    val scrubbedPoint: PricePoint? = null,
)
```

### Side effects

A sealed interface, for type-safe one-shot events:

```kotlin
sealed interface PriceChartSideEffect {
    data class ShowMessage(val text: String) : PriceChartSideEffect
    data object ScrollToLatest : PriceChartSideEffect
}
```

### Implementation

Runtime parameters come in through the constructor (supplied by the factory);
everything else is injected.

```kotlin
class PriceChartUiModelImpl(
    // Runtime parameters, from the factory
    private val ticker: String,
    private val coroutineScope: CoroutineScope,
    private val coroutineDispatcher: CoroutineDispatcher,

    // Graph dependencies
    private val observePriceSeries: ObservePriceSeriesUseCase,
    private val refreshPriceSeries: RefreshPriceSeriesUseCase,
) : PriceChartUiModel {

    private val _uiState = MutableStateFlow(PriceChartUiState())
    override val uiState: StateFlow<PriceChartUiState> = _uiState

    private val _sideEffect = Channel<PriceChartSideEffect>(Channel.BUFFERED)
    override val sideEffect: Flow<PriceChartSideEffect> = _sideEffect.receiveAsFlow()

    override fun onRangeSelected(range: ChartRange) {
        _uiState.update { it.copy(selectedRange = range, isLoading = true) }
        coroutineScope.launch(coroutineDispatcher) {
            refreshPriceSeries(ticker, range)
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}
```

**Always receive the `CoroutineScope` as a parameter.** That is what makes the
component testable and lets the host decide its lifetime.

### Factory interface

```kotlin
interface PriceChartUiFactory {
    fun create(
        ticker: String,
        coroutineScope: CoroutineScope,
        coroutineDispatcher: CoroutineDispatcher,
    ): PriceChartUiModel
}
```

### Registration

With Koin Annotations (see [KOIN.md](KOIN.md)) the factory implementation is the
one place that is *not* a `@Single`:

```kotlin
@Factory(binds = [PriceChartUiFactory::class])
class PriceChartUiFactoryImpl(
    private val observePriceSeries: ObservePriceSeriesUseCase,
    private val refreshPriceSeries: RefreshPriceSeriesUseCase,
) : PriceChartUiFactory {
    override fun create(…) = PriceChartUiModelImpl(…)
}
```

Graph dependencies are injected into the *factory*; only the non-injectable
parameters (scope, ids, callbacks) go through `create()`. **Each host creates its
own instance — no shared state.**

---

## 4. Factory class

Wraps the UiModel and owns the composable surface, including any UI-only state.

```kotlin
class PriceChartFactory(
    private val uiModel: PriceChartUiModel,
    private val onShowMessage: suspend (String) -> Unit,
) {
    @Composable
    fun Create(modifier: Modifier = Modifier) {
        val state by uiModel.uiState.collectAsStateWithLifecycle()
        PriceChartCard(
            state = state,
            onRangeSelected = uiModel::onRangeSelected,
            modifier = modifier,
        )
    }

    @Composable
    fun HandleSideEffects() {
        HandlePriceChartSideEffects(uiModel.sideEffect, onShowMessage)
    }
}

@Composable
internal fun HandlePriceChartSideEffects(
    sideEffect: Flow<PriceChartSideEffect>,
    onShowMessage: suspend (String) -> Unit,
) {
    val currentOnShowMessage by rememberUpdatedState(onShowMessage)
    LaunchedEffect(sideEffect) {
        sideEffect.collectLatest { effect ->
            when (effect) {
                is PriceChartSideEffect.ShowMessage -> currentOnShowMessage(effect.text)
                PriceChartSideEffect.ScrollToLatest -> Unit
            }
        }
    }
}
```

> `rememberUpdatedState` is not optional: the effect keys on `sideEffect`, so a host
> passing a fresh lambda would otherwise leave it invoking a stale one. The Compose
> ktlint rule `lambda-param-in-effect` enforces this.

---

## 5. Side effects

**Use them for:** navigation, snackbars, sharing, and one-shot UI actions (scroll
to a position, focus a field).

**Never model a one-shot event as state.** Emit through a `Channel`, collect in a
`LaunchedEffect`. This is the same rule the ViewModels already follow — see
[ARCHITECTURE.md §4](ARCHITECTURE.md), which is the normative version. This page
only shows how it looks *inside* a UiModel.

**Never navigate from a UiModel.** Emit a side effect and let the host navigate.

---

## 6. Composition

UiModels can consume other UiModels, combining their state:

```kotlin
class TickerDetailUiModelImpl(
    private val priceChartUiModel: PriceChartUiModel,
    private val watchlistUiModel: WatchlistUiModel,
    …
) : TickerDetailUiModel {

    private val combined = combine(
        priceChartUiModel.uiState,
        watchlistUiModel.uiState,
    ) { chart, watchlist -> … }
}
```

Created in the ViewModel, which owns their scope:

```kotlin
@KoinViewModel
class TickerDetailViewModel(
    @InjectedParam private val navKey: AppNavKey.TickerDetail,
    priceChartUiFactory: PriceChartUiFactory,
) : ViewModel() {

    val priceChartUiModel = priceChartUiFactory.create(
        ticker = navKey.ticker,
        coroutineScope = viewModelScope,
        coroutineDispatcher = ioDispatcher,
    )
}
```

**Expose the UiModel directly.** Do not add ViewModel methods that only delegate:

```kotlin
// BAD — pure passthrough
fun onRangeSelected(range: ChartRange) = priceChartUiModel.onRangeSelected(range)

// GOOD — the screen calls the UiModel
viewModel.priceChartUiModel.onRangeSelected(range)
```

A wrapper *is* justified when the ViewModel adds real logic (analytics, coordinating
a second component).

---

## 7. Testing

Same rules as everything else here ([TESTING.md](TESTING.md)) plus one trap.

**Long-running flows need `backgroundScope`, not `this`.** A UiModel that receives a
`CoroutineScope` and launches collectors that never complete will hang a `runTest`
block if given the `TestScope` itself.

```kotlin
@Test
fun WHEN_a_range_is_selected_THEN_the_series_is_refreshed() = runTest {
    val uiModel = createUiModel()          // extension on TestScope
    uiModel.onRangeSelected(ChartRange.ONE_YEAR)
    uiModel.uiState.test { … }
}

private fun TestScope.createUiModel() = PriceChartUiModelImpl(
    ticker = "NVDA",
    coroutineScope = backgroundScope,      // NOT `this`
    coroutineDispatcher = testDispatcher,
    …
)
```

Making the factory function an **extension on `TestScope`** is what gives access to
`backgroundScope`.

Use hand-written fakes for the dependencies — MockK is JVM-only and unavailable in
`commonTest`.

---

## 8. Fakes for previews and snapshots

```kotlin
fun fakePriceChartUiModel(
    state: PriceChartUiState = PriceChartUiState(),
) = object : PriceChartUiModel {
    override val uiState = MutableStateFlow(state)
    override val sideEffect = emptyFlow<PriceChartSideEffect>()
    override fun onRangeSelected(range: ChartRange) {}
    override fun onScrub(epochSeconds: Long?) {}
    override fun retry() {}
}
```

Note this project's screens currently get the same benefit from **stateless
overloads** taking a plain `UiState`, which is simpler. A fake UiModel only becomes
necessary once the component owns its own state.

---

## 9. Best practices

1. **One UiModel, one responsibility.**
2. **Always define an interface** — it is what makes fakes and swapping possible.
3. **Side effects for navigation**, never a direct call.
4. **Inject the `CoroutineScope`**, never create one internally.
5. **Factory for runtime parameters** (scope, ids, callbacks); everything else via DI.
6. **Fakes for previews and snapshots.**
7. **`backgroundScope` in tests** for anything long-running.
8. **Expose the UiModel, do not wrap it** in passthrough methods.

---

## 10. When to adopt this here

Today the pattern would be over-engineering: two screens, two ViewModels, one
repository, and no stateful UI shared between them.

**The one real candidate is the price chart.** `TickerDetailViewModel` already
devotes roughly a quarter of its lines to it — `selectedRange`, `isChartLoading`,
the `flatMapLatest` branch, the refresh collector, the intraday prefetch — and that
cluster is cohesive and self-contained.

**The trigger: adopt it when the chart gains a second host.** A comparison screen,
a watchlist sparkline, or a return of the native SwiftUI detail screen would each
qualify. Until then, extracting it costs roughly +100 lines across 4 new files to
relocate 40, and splits the five-way `combine` in `TickerDetailViewModel` into two
independently-subscribed `stateIn` flows — a lifecycle that was already the fiddly
part on iOS.

Write the second host first. Then extract.
