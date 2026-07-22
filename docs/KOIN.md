# Dependency injection (Koin Annotations)

Read before adding a binding, an API class, a use case or a ViewModel.

---

## 1. TL;DR

| | |
|---|---|
| Library | Koin 4.2.2 + `koin-annotations` 4.2.2 |
| Processor | **`io.insert-koin.compiler.plugin` 1.0.2 — a K2 compiler plugin, not KSP** |
| Scope rule | **everything `@Single`, except ViewModels (`@KoinViewModel`)** |
| Graph entry | `AppModule` (`di/Modules.kt`) with `@ComponentScan` |
| Platform seam | `expect val platformModule`, whose actuals are annotated `@Module` classes |
| Compile check | `compileSafety = true` — a missing binding is a build error |

**KSP is still in the build — for Room only.** Guides that tell you to remove the
KSP plugin when adopting the Koin compiler plugin do not apply here.

> The old `io.insert-koin:koin-ksp-compiler` is frozen at 2.3.2-Beta1 and never
> followed `koin-annotations` into the 4.2.x line. If you find a tutorial using it,
> it is out of date.

---

## 2. The scope rule

**Everything is `@Single` except ViewModels.** That is the whole rule.

Koin singles are **lazy** unless `createdAtStart`, and everything in this graph is a
stateless collaborator holding one or two references. A `@Factory` would allocate a
new instance per injection and buy nothing.

> Some Koin guides list "using `single` for use cases" as a mistake. That is advice
> for large graphs with heavyweight objects; it does not transfer to nine one-line
> pass-throughs. Do not "fix" `AppModule` on the strength of it.

---

## 3. Where things are declared

### Annotated in place — most of the graph

`@Single` on the class itself: the three API clients, `TickerRepositoryImpl`, the
nine use cases. `@ComponentScan("app.stockpickers.kmp")` on `AppModule` finds them.

An interface binding is inferred: `TickerRepositoryImpl` implements
`TickerRepository`, and that is what gets bound.

### Declared by hand — types we do not own

Inside `AppModule`, as `@Single` functions: `Json`, `HttpClient`, the Room
`AppDatabase` and `ScannerDao`. These are third-party or builder-produced types with
nowhere to put an annotation.

### Constructor parameters that are not dependencies

`SupabaseScannerApi(client, baseUrl = SupabaseConfig.URL, anonKey = SupabaseConfig.ANON_KEY)`
takes two Strings that come from generated config, not from the graph.

**Nothing special is needed.** The processor's `skipDefaultValues` is on by default,
so parameters with defaults are left alone and only `client` is injected. The
secrets mechanism is untouched.

---

## 4. The platform seam

`expect val platformModule` binds what only a platform can provide — the Ktor engine
and the `DatabaseBuilderFactory`.

Each actual is an **annotated `@Module` class**, not a DSL module:

```kotlin
// androidMain
@Module
class AndroidPlatformModule {
    @Single fun httpClientEngine(): HttpClientEngine = OkHttp.create()

    @Single fun databaseBuilderFactory(scope: Scope): DatabaseBuilderFactory =
        DatabaseBuilderFactory(scope.androidContext())
}

actual val platformModule: KoinModule = AndroidPlatformModule().module()
```

Two things worth knowing:

- **Why annotated and not DSL.** A runtime DSL module is invisible to
  `compileSafety`, which then reports the engine and the factory as missing
  dependencies. Annotating them is what makes the compile-time check complete.
- **Why `scope: Scope` and not `context: Context`.** The Android `Context` is
  registered by `androidContext(...)` at startup, not by a binding the checker can
  see. Taking the `Scope` and calling `scope.androidContext()` avoids both the false
  positive and koin-annotations#37, which is triggered by putting a `Context` in a
  module class constructor.

`DatabaseBuilderFactory` itself cannot be annotated: its actuals have different
constructors, which `expect`/`actual` forbids.

---

## 5. ViewModels

```kotlin
@KoinViewModel
class TickerDetailViewModel(
    @InjectedParam private val navKey: AppNavKey.TickerDetail,
    getTickerDetail: GetTickerDetailUseCase,
    …
) : ViewModel()
```

- `@KoinViewModel` lives in **`org.koin.core.annotation`** in 4.2.x (it moved from
  `org.koin.android.annotation`; the two official doc pages disagree — the jar is
  the authority).
- `@InjectedParam` marks a parameter handed over at the call site rather than
  resolved from the graph. Without it the processor looks for an
  `AppNavKey.TickerDetail` binding that will never exist.
- `@InjectedParam` and `@Provided` ship in a separate `koin-core-annotations`
  artifact, same package, arriving transitively. Nothing to declare.

### The call site

**Always inside the `entry<>` block:**

```kotlin
entry<AppNavKey.TickerDetail> { key ->
    val viewModel: TickerDetailViewModel = koinViewModel { parametersOf(key) }
    …
}
```

That is what lets the Nav3 decorator scope the ViewModel per NavEntry. **Do not**
give a screen a defaulted `viewModel: XxxViewModel = koinViewModel()` parameter —
it works, but it resolves outside the entry scope and silently opts out of the
scoping. See [NAVIGATION.md §4](NAVIGATION.md).

**Pass the whole NavKey**, never loose primitives.

---

## 6. Starting Koin

One graph, two entry points.

```kotlin
fun initKoin(appDeclaration: KoinApplication.() -> Unit = {}) = startKoin {
    appDeclaration()
    modules(platformModule, AppModule().module())
}
```

**Android** — `StockpickersApp.onCreate`:

```kotlin
initKoin {
    androidLogger()
    androidContext(this@StockpickersApp)
}
```

**iOS** — `startKoinIos()`, called from `iOSApp`:

- It must **not** be named `init*`: the Obj-C exporter mangles that family, so
  `initKoin` would reach Swift as `doInitKoin()`.
- Default arguments do not survive into the Obj-C header, so a wrapper is needed
  anyway for Swift to call it bare.
- It is **idempotent** — starting Koin twice throws
  `KoinAppAlreadyStartedException`.
- Guard with `KoinPlatform.getKoinOrNull()`. **`GlobalContext` is JVM-only** and
  does not resolve on Native.

---

## 7. Compile-time verification

```kotlin
koinCompiler {
    compileSafety = true
    strictSafety = false
}
```

**`compileSafety`** resolves the whole graph at compile time, on both targets. A
missing binding becomes:

```
e: [Koin][KOIN-D001] Missing dependency: app.stockpickers.kmp.domain.GetGeoCountsUseCase
```

This is the main prize of using annotations here. Without it, a missing binding
surfaces on iOS as a crash inside `ComposeUIViewController` with a nearly useless
stack trace — and only when that screen is opened.

**`strictSafety` is off — and the two are NOT mutually exclusive.** Both *can* be
true; it compiles fine. What matters is what it costs and what it does not buy.

`strictSafety` is **not a compiler option**. The plugin passes only `compileSafety`,
`skipDefaultValues`, `unsafeDslChecks`, the `*Logs` flags and `aiAssist` — so it
enables no additional checking whatsoever. Its entire effect is on the Gradle side:

```kotlin
upToDateWhen { !(strictSafety && compileSafety) }   // and the same spec for cacheIf
```

So the penalty comes from the **pair**, not from `strictSafety` alone: with both
true, `:shared` is never up-to-date and never cacheable, and the graph check re-runs
even when Gradle knows nothing changed.

Measured here, a no-op `compileKotlinIosSimulatorArm64`:

| Setting | No-op build |
|---|---|
| `compileSafety = true`, `strictSafety = false` | **~0.6 s** |
| both `true` | **~18–29 s** |

A 30–50× tax on every no-op build, in exchange for zero extra verification. The
graph is derived from this module's own sources, so "Gradle says nothing changed"
already implies the graph cannot have changed. Turn `strictSafety` on only to
investigate a suspected stale-graph result, then turn it back off.

---

## 8. Testing

**Tests do not use Koin at all.** They construct ViewModels directly, wiring real
use cases over hand-written fakes — which is why the domain layer is framework-free
in the first place.

The screenshot tests go further and actively *avoid* Koin:
`@Config(application = Application::class)` uses the plain `Application` rather than
`StockpickersApp`, whose `onCreate` would call `initKoin()` per test and throw
`KoinApplicationAlreadyStarted` on the second.

There is no runtime `checkModules()` test — `compileSafety` covers the same ground
earlier and on both platforms.

---

## 9. Common mistakes

| Mistake | What happens |
|---|---|
| Resolving a ViewModel via a defaulted screen parameter | Works, but escapes NavEntry scoping |
| Forgetting `@InjectedParam` on a NavKey | `compileSafety` reports a missing binding for the key type |
| Putting a platform binding in a DSL module | `compileSafety` reports it missing — it cannot see runtime modules |
| `Context` in a `@Module` class constructor | koin-annotations#37 |
| `GlobalContext` on iOS | Unresolved on Native — use `KoinPlatform` |
| Naming a Swift-facing starter `init*` | Reaches Swift as `doInit*` |
| Reaching for `koin-ksp-compiler` | Frozen at 2.3.2-Beta1; wrong processor |
