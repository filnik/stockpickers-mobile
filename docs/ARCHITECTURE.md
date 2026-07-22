# Architecture

Clean Architecture + MVVM inside one module. Read before adding a layer, a use
case, or a ViewModel.

---

## 1. Layers

Everything lives in `:shared`. Dependencies point **inward only**:

```
ui / ui.navigation   Compose screens, NavDisplay wiring   → presentation, domain
presentation         ViewModels, UiState, side effects    → domain, navigation
domain               models, use cases, repository IFACES  → nothing
data                 remote (Ktor), local (Room), impls    → domain
navigation           NavKeys only                          → nothing
di                   Koin — the only place that knows all
```

Two rules that are load-bearing:

- **`domain` stays free of framework types.** No Room, no Ktor, no Compose, no
  `androidx.*` in a domain model or use case. That is what makes the domain testable
  with hand-written fakes in `commonTest`, on both platforms.
- **`navigation` sits outside `ui`** so `presentation` can depend on it — ViewModels
  take their NavKey as a constructor parameter. Putting the keys in `ui.navigation`
  would invert the layering.

### Enforcement

`:shared` is a **single Gradle module**, so module boundaries enforce nothing here;
the package is the only lever. Concretely:

- `presentation` must not import `data.remote` or `data.local`
- `domain` must not import `presentation` or `data`
- `ui` may depend on `presentation` and `domain`, never on `data`

Nothing checks this automatically today. It is prose, and it holds only as long as
reviewers hold it.

---

## 2. Use cases

- **One action, one use case.** `GetMomentumLeadersUseCase`, `GetTickerDetailUseCase`.
- **No use case chaining in ViewModels.** A ViewModel never calls several use cases
  in sequence or in callbacks. If several operations belong together, that is one
  orchestrating use case.
- **No direct repository calls from ViewModels.** No exceptions in this codebase —
  no ViewModel injects a repository. A one-line pass-through
  (`RefreshTickersUseCase`, `ObserveLastSyncedAtUseCase`) is the correct amount of
  ceremony, not boilerplate to skip: it keeps the ViewModel's dependencies a list of
  *capabilities* rather than a data source.
- Named `VerbNounUseCase`, with `operator fun invoke(...)`.

Because they are pass-throughs, they are **not tested individually** — the ViewModel
tests wire the real use cases over a fake repository. See [TESTING.md §5](TESTING.md).

---

## 3. The repository: offline-first

**Room is the single source of truth the UI observes. The network only ever writes
*into* Room.**

```
API → refresh() → Room → Flow → ViewModel → UiState → screen
```

The consequences, and they are the point:

- **Stale-while-revalidate for free.** A refresh writes into the same table the UI
  is already observing, so the screen re-renders with no extra wiring.
- **A failed refresh must never blank the cache.** It returns `RefreshResult.Failed`
  and Room is untouched. This is the property `TickerRepositoryImplTest` guards most
  heavily.
- **The exception to "never delete"** is the scanner sync, which prunes rows that
  upstream has deleted — but only after a complete, successful fetch. See
  [UPSTREAM_CONTRACT.md §4](UPSTREAM_CONTRACT.md).

### Error handling

Network failures **never escape the repository as exceptions**. They become either:

- `RefreshResult.Failed(reason, message)` — for the scanner sync, whose failure the
  user must see; or
- a silent no-op leaving the cache intact — for the chart and the profile, where a
  failure is not worth a message.

**`CancellationException` is caught first and rethrown, always.** A bare
`runCatching` (or a `catch (e: Throwable)` without that guard) around a suspend call
swallows cancellation and breaks structured concurrency. detekt's
`SuspendFunSwallowedCancellation` is enabled for exactly this.

Where an exception is deliberately dropped, name it `_`:

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    // graceful: whatever is cached survives
}
```

There is deliberately **no `Result<T>` wrapper and no `ApiException` hierarchy**.
Three small read-only clients do not need one — do not build one speculatively.

---

## 4. Presentation

### State

- **`StateFlow<XxxUiState>`** exposed by the ViewModel, collected with
  `collectAsStateWithLifecycle()`.
- UiState is a **single immutable data class** with *derived* properties
  (`isEmpty`, `isFatal`, `isMissing`, `isOffline`) rather than booleans computed in
  the UI. The screen reads a question, not an expression.
- Derived properties are where the subtle rules live — `isFatal` is the only case
  where an error takes over the whole screen, and its definition encodes the
  offline-first promise: never fatal if there is a cache to show.

### Side effects

**One-shot events go through a `Channel` + `receiveAsFlow()`**, never through state.
Navigation, snackbars, dialogs: they must fire exactly once, must not survive
recomposition, and must not replay.

```kotlin
private val _sideEffect = Channel<XxxSideEffect>(Channel.BUFFERED)
val sideEffect: Flow<XxxSideEffect> = _sideEffect.receiveAsFlow()
```

Collected in the stateful overload with `LaunchedEffect`. **A lambda referenced
inside such an effect must be wrapped in `rememberUpdatedState`** unless the effect
keys on it — otherwise a caller passing a fresh lambda leaves the collector invoking
a stale one for the rest of the screen's life. The Compose ktlint ruleset
(`lambda-param-in-effect`) checks this.

> Known deviation: `MomentumLeadersViewModel` still routes its snackbar through
> state (`errorMessage` + `dismissError()`) rather than a Channel, because
> `errorMessage` is also what `isFatal` reads. Fixing it means deriving `isFatal`
> from `refreshFailure` and mapping the fatal message to a localised string instead
> of a raw exception message — worth doing, not yet done.

### Screens

Every screen has **two overloads**:

- **stateful** — takes the ViewModel, collects state, drains side effects;
- **stateless** — takes `state` + lambdas, and is **free of Koin**.

The stateless one is what the screenshot tests render and what stays previewable.
Keep it that way.

---

## 5. Mappers

Private extension functions, in the data layer, one direction each:

```kotlin
private fun TickerDto.toEntity(): TickerEntity
private fun TickerEntity.toDomain(): Ticker
private fun TickerEntity.toDetail(): TickerDetail
```

Separate models per layer is the reason a nullable, ever-changing upstream payload
cannot leak into the UI. **Never pre-multiply or reformat in a mapper** — units are
upstream's and formatting is the UI's job (`ui/Formatting.kt`).

---

## 6. Shared UI

Most UI belongs next to the screen that renders it; `internal` keeps it inside the
module. A composable earns a place in `ui/Components.kt` once a **second** screen
needs it — and then it *moves* there rather than being copied.

Semantic colours (`PositiveGreen`, `NegativeRed`) live in `ui/Theme.kt`. Never use
`colorScheme.error` for a negative number: it means "something went wrong", not
"below zero".
