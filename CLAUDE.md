# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Stockpickers KMP is a Kotlin Multiplatform app (Android + iOS). It reads the `scanner_cache` table published by the Python investing pipeline (via Supabase/PostgREST) and renders a **momentum leaders** board plus a **ticker detail** read-out.

The UI is **hybrid**: the leaders board is Compose Multiplatform shared across both platforms; the detail screen is Compose on Android but **native SwiftUI on iOS**, both driven by the *same* shared `TickerDetailViewModel`. This is deliberate — it mirrors the "70% CMP + native where it earns it" model and exercises the Kotlin↔Swift interop. See [Hybrid UI & iOS interop](#hybrid-ui--ios-interop).

The client is **offline-first and read-only**: Room is the single source of truth the UI observes, and the network only ever writes *into* Room. It computes no investment logic of its own — see [Ownership of Business Logic](#ownership-of-business-logic).

**Module layout:**
- `:shared` — everything: data, domain, presentation, UI, navigation, DI. Compiles to an Android library and an iOS `Shared.framework`.
- `:composeApp` — the Android application shell (`MainActivity`, `StockpickersApp`). Thin by design.
- `iosApp/` — the Xcode app shell. `project.yml` is the source of truth (xcodegen); the `.xcodeproj` is generated and git-ignored.

## Build & Development Commands

```bash
# Android: assemble the debug APK (primary Android check)
./gradlew :composeApp:assembleDebug

# iOS: link the simulator framework (primary iOS check — do this for every
# commonMain change; Android compiling proves NOTHING about Kotlin/Native)
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64

# iOS device framework
./gradlew :shared:linkDebugFrameworkIosArm64

# Regenerate the Xcode project after editing iosApp/project.yml
cd iosApp && xcodegen generate

# Build + run the iOS app on a simulator
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
xcrun simctl install booted <path>/iosApp.app
xcrun simctl launch booted app.stockpickers.kmp.ios

# Tests
./gradlew :shared:allTests        # all targets
./gradlew :shared:iosSimulatorArm64Test

# Clean
./gradlew clean
```

**Always verify BOTH targets.** The Android task is fast and proves almost nothing about iOS: expect/actual gaps, Kotlin/Native-only API restrictions and Obj-C export problems only appear at `link*IosSimulatorArm64`.

## Architecture & Code Structure

### Clean Architecture + MVVM

Layers inside `:shared`, dependencies pointing inward only:

```
ui / ui.navigation   → Compose screens, NavDisplay wiring     (depends on presentation, domain)
presentation         → ViewModels, UiState, side effects      (depends on domain, navigation)
domain               → models, use cases, repository INTERFACES (depends on nothing)
data                 → remote (Ktor), local (Room), repository IMPLs (depends on domain)
navigation           → NavKeys only                           (depends on nothing)
di                   → Koin modules, the only place that knows every layer
```

- **`domain` must stay free of framework types.** No Room, no Ktor, no Compose, no `androidx.*` in a domain model or use case.
- **`navigation` (NavKeys) sits outside `ui`** so `presentation` can depend on it — ViewModels take their NavKey as a constructor parameter. Putting keys in `ui.navigation` would invert the layering. (Wishew solves the same problem by placing NavKeys in `core/common`.)

### Ownership of Business Logic

**The scanner rules are owned by the Python pipeline and mirrored by the web client (`investing/web`). This app READS them; it does not invent them.**

- Quality gate, Wyckoff phase, ADR dedup and the momentum windows are all computed upstream.
- The qualifying rules live in `ScannerDao.observeMomentumLeaders`'s SQL, ported from `investing/web/lib/queries.ts::getMomentumLeaders`. If upstream changes, port the change across — do not let them drift, and do not "improve" a threshold locally.
- **The board's tabs and chips mirror `investing/web/lib/picks-filters.ts`**: tabs `Forza · 1M · 2M · 3M` (`AcceleratingSort`/`SORT_KEY` — `Forza` == the web's `aggregate`, ranked by `clenow` DESC, the default) and chips `Tutti · 🇺🇸 USA · 🇮🇹 ITA · 🇯🇵 ASIA` (`GeoBucket`, ASIA = Japan+South Korea+Taiwan). The gate is identical in every tab×chip combination; only the ranking key and the country predicate change.
- **The country list in that SQL is a FOURTH copy** of Python's `_PE_SWITCH_BUCKET_COUNTRIES` / `queries.ts::BUCKET_COUNTRIES` / `picks-filters.ts` (which already asks to "keep the three in sync"). Accepted debt, documented at the DAO: an offline-first client cannot call a source of truth it may not be able to reach. Add a country upstream → add it here too. A fifth copy means it is time to publish the buckets as data.
- **No merge pass, unlike the web.** `getMomentumLeaders` + `getCountryLeaders` + `getCountryMomentumLeaders` are stitched together there only because a server-side top-N is dominated by US/Taiwan and would leave the ITA/ASIA chips empty. Room holds the whole universe (~1804 rows), so the chip is a plain `WHERE` applied *before* the `LIMIT` — one query, same semantics. Do not port the merge.
- Unit conventions are also upstream's: `mom_*` and `roic` are **decimal fractions** (0.55 == +55%), `r2` and `peg` are plain ratios. Never pre-multiply in domain/data — formatting is the UI's job (`ui/Formatting.kt`).
- **Fail-safe reading**: a missing quality verdict means UNKNOWN and must be EXCLUDED from the board, never assumed to pass. `qualityPasses = 1` in SQL gives this for free (`NULL = 1` is NULL). Using `!= 0` would fail *open* and leak un-evaluated rows.

### Use Case Rules

- **One action, one use case.** Each use case does one thing (`GetMomentumLeadersUseCase`, `GetTickerDetailUseCase`).
- **No use case chaining in ViewModels.** A ViewModel never calls several use cases in sequence or in callbacks.
- **Use case internal orchestration is OK.** A use case may call other use cases when they belong to one cohesive operation.
- **No direct repository calls from ViewModels.** Always go through a use case — no exceptions in this codebase: no ViewModel injects a repository. A one-line pass-through use case (`RefreshTickersUseCase`, `ObserveLastSyncedAtUseCase`) is the correct amount of ceremony, not boilerplate to skip.
- **Orchestration belongs in the domain layer**, not spread across the ViewModel.

### State Management

- **State: `StateFlow<XxxUiState>`** exposed by the ViewModel, collected with `collectAsStateWithLifecycle()`.
- **Side effects: `Channel` + `receiveAsFlow()`** — one-shot events (navigate, show snackbar) that must fire exactly once and must NOT survive recomposition or be replayed. Never model them as state.
- UiState is a single immutable `data class` with derived properties (`isEmpty`, `isFatal`, `isMissing`) rather than booleans computed in the UI.
- Stale-while-revalidate: state is driven by the Room Flow; `refresh()` writes into the same table and the UI re-renders for free. A failed refresh must never blank the cache already on screen.
- Every screen has a **stateful** overload (takes the ViewModel, collects state) and a **stateless** overload (takes `state` + lambdas). Keep the stateless one free of Koin so it stays previewable and testable.

### Navigation 3

Nav3 with the "you own the back stack" model. Files:

```
navigation/NavKeys.kt              # AppNavKey sealed interface — all destinations
ui/navigation/Navigator.kt         # owns the back stack; goTo / goBack
ui/navigation/NavigationState.kt   # rememberNavigator() + SavedStateConfiguration
ui/navigation/EntryProvider.kt     # NavKey → composable
ui/navigation/Nav3Host.kt          # NavDisplay + decorators
```

- **Only `Navigator` mutates the back stack.** Screens get `goTo`/`goBack` and never touch the list.
- ViewModels receive the **whole NavKey** via `koinViewModel { parametersOf(key) }`, not loose primitives — adding a parameter then stays a compile-time change.
- Resolve ViewModels **inside** the `entry<>` block so `rememberViewModelStoreNavEntryDecorator` scopes them per NavEntry.
- **Adding a destination:** add it to `AppNavKey` → register it in the polymorphic `SerializersModule` in `NavigationState.kt` (**iOS crashes at runtime otherwise**) → add an `entry<>` in `EntryProvider.kt` → navigate with `navigator.goTo(...)`.

See the KMP-specific Nav3 gotchas below — several are not in the AndroidX docs.

### Dependency Injection (Koin)

- Classic DSL, **no annotations / no KSP** (`di/Modules.kt`). Don't introduce `@KoinViewModel` here without migrating the whole graph.
- `single { }` for repositories, API clients, DB, use cases. `viewModel { }` for ViewModels.
- ViewModels needing a NavKey: `viewModel { params -> XxxViewModel(navKey = params.get(), ...) }`.
- `expect val platformModule: Module` binds the platform pieces (Ktor engine, `DatabaseBuilderFactory`).
- Android starts Koin in `StockpickersApp.onCreate`; iOS in `startKoinIos()` (see gotchas).

### Key Technologies

| Concern | Choice |
|---|---|
| UI | Compose Multiplatform 1.11.1, Material 3 |
| Language | Kotlin 2.4.0 (pinned for SKIE — see below) |
| Navigation | Navigation 3 (`org.jetbrains.androidx.navigation3` 1.2.0-alpha02) |
| DB | Room 3.0 (`androidx.room3`) + `sqlite-bundled` |
| Network | Ktor 3.5.1 (OkHttp on Android, Darwin on iOS) |
| DI | Koin 4.2.2, classic DSL |
| ViewModel | `org.jetbrains.androidx.lifecycle` 2.11.0 |
| Charts | **Vico 2.5.2** (Android) · **Swift Charts** (iOS) — same shared `PriceSeries`, see below |
| Market data | Yahoo Finance v8 `chart` (direct, browser UA, Room-cached) — see below |
| Build | AGP 9.3.0, compileSdk 37 / targetSdk 36 / minSdk 29 |

## KMP / iOS Gotchas

Hard-won on THIS project. Most cost a build failure or a runtime crash — read before touching the shared module.

### Coroutines
- **`Dispatchers.IO` does not exist in commonMain.** It is a JVM-only extension. Use the project's `expect val ioDispatcher` (`util/Dispatchers.kt`). On Native the actual is `Dispatchers.IO` too, but it needs `import kotlinx.coroutines.IO` — a different, K/N-specific extension with the same name.

### Koin
- **`GlobalContext` is JVM-only.** On Native use `org.koin.mp.KoinPlatform` (e.g. `KoinPlatform.getKoinOrNull()`).

### Kotlin/Native → Obj-C export
- **The exporter mangles names starting with `init`.** It treats them as the ObjC initializer family, so `initKoin()` reaches Swift as `doInitKoin()`. The iOS entry point is therefore named **`startKoinIos()`**. Never expose an `init*` top-level function to Swift.
- **Default arguments do not survive into the ObjC header.** `initKoin(appDeclaration: KoinApplication.() -> Unit = {})` forces Swift to pass a lambda; `startKoinIos()` wraps it so Swift calls a bare function.
- Starting Koin twice throws `KoinAppAlreadyStartedException` — `startKoinIos()` is idempotent by design.

### Compose Multiplatform
- **`CADisableMinimumFrameDurationOnPhone: true` is REQUIRED in `Info.plist`.** Without it `ComposeUIViewController`'s `PlistSanityCheck` throws at startup. It lives in the `info:` block of `iosApp/project.yml`.
- **CMP 1.11.1 publishes no `iosX64` artifacts** (the last that did was 1.11.0-alpha01). Declaring the Intel-simulator target breaks commonMain dependency resolution. Apple Silicon uses `iosSimulatorArm64`; the target list is intentionally `iosArm64 + iosSimulatorArm64`.
- Set `binaryOption("bundleId", ...)` on the framework or the linker warns it cannot infer one.

### Navigation 3 on CMP
- **`androidx.navigation3:navigation3-ui` is Android-only** — it publishes only `-android` and `*stubs` variants, no iOS. The UI layer must come from the JetBrains port, `org.jetbrains.androidx.navigation3:navigation3-ui`. (`androidx.navigation3:navigation3-runtime` *is* multiplatform and arrives transitively — don't declare it separately.)
- **`rememberNavBackStack(vararg NavKey)` is Android-only.** That overload lives in `RememberNavBackStack.android.kt` and reflects over subclasses at runtime, which Kotlin/Native cannot do. commonMain only sees the overload taking a `SavedStateConfiguration`, so **every NavKey subclass must be registered by hand** in a polymorphic `SerializersModule` (`NavigationState.kt`). Forgetting one compiles fine and throws `SerializationException: Class 'X' is not registered for polymorphic serialization` **on iOS only, at runtime**.
- **The entry DSL scope is `EntryProviderScope<T>`, not `EntryProviderBuilder<T>`** (renamed; most docs and blog posts still say Builder).
- The decorator is `rememberSaveableStateHolderNavEntryDecorator()` (in `navigation3.runtime`), paired with `rememberViewModelStoreNavEntryDecorator()` (in `lifecycle.viewmodel.navigation3`).
- **Do NOT add a `BackHandler` around `NavDisplay`.** It already wires the platform back signal to `onBack` via `navigationevent-compose`, on Android *and* iOS. Adding one pops twice. (Wishew's Nav3Host does add one — that is an Android-only, Nav2-era habit; don't port it.)
- The JetBrains port is **alpha** (`1.2.0-alpha02`) while AndroidX is 1.1.4 stable. Pin the exact alpha: breaking changes land between alphas. Stable JetBrains builds (1.1.0/1.1.1) exist but target the older CMP/navigationevent line.

### Hybrid UI & iOS interop
The iOS detail screen is **native SwiftUI** (`iosApp/iosApp/TickerDetailView.swift`) observing the shared Kotlin `TickerDetailViewModel`. How the pieces fit:
- **`MainViewController(onTickerSelected:)`** (iosMain) hosts ONLY the Compose leaders list — not the Nav3 back stack. A row tap invokes the Swift callback, which does `path.append(ticker)` on a SwiftUI `NavigationStack` → native detail push. Android keeps the full Nav3 stack in Compose (`StockpickersRoot`). So navigation is *native on iOS, Compose on Android*: the deliberate cost of "whole native screens" is coordinating the two.
- **SKIE observes the shared ViewModel.** `IosViewModels.kt` exposes `tickerDetailViewModel(ticker)` (resolves the ViewModel from Koin); SKIE turns its `StateFlow<TickerDetailUiState>` into a Swift `AsyncSequence`, so `TickerDetailModel` (SwiftUI) does `for await state in vm.uiState` directly, with `.value` for the first frame. No hand-written Flow bridge. The `for await` Task is cancelled in `.onDisappear`, dropping the last subscriber so the ViewModel's `WhileSubscribed(5s)` tears down the Room flow.
- **SKIE is why Kotlin is pinned to 2.4.0.** SKIE (a compiler plugin) supports Kotlin up to 2.4.0; a newer Kotlin (e.g. 2.4.10) would disable it until Touchlab ships a matching build. Keeping SKIE on is worth the pin — it's the whole point of the native-Swift interop. `skie { isEnabled = true }`.
- **Nullable Doubles still cross as `KotlinDouble?`**, not `Double?` — read them in Swift with `.doubleValue` (and `KotlinBoolean?.boolValue`). SKIE fixes Flow/suspend/sealed/enum, but not the primitive-boxing of arbitrary data-class properties; that's a broader Kotlin↔Obj-C limit.
- A Swift-exposed factory must not be named `init*` (the Obj-C exporter mangles that function family); `tickerDetailViewModel(...)` and class constructors cross unmangled.

### Price chart & market data (Yahoo, hybrid render)
The detail screen shows a 6-month daily close chart. Same shared data, two native renderers — the chart is the hybrid thesis made literal:
- **Data**: `YahooChartApi` (commonMain, Ktor) calls Yahoo's unofficial `v8/finance/chart/{symbol}` **directly** (our tickers ARE Yahoo symbols — no mapping). Normalized to the shared `PriceSeries(currency, last, previousClose, points: List<PricePoint>)`; cached in Room (`PriceSeriesEntity`, points as JSON). `TickerDetailViewModel` exposes `uiState.priceSeries` and fires `RefreshPriceSeriesUseCase` in `init` (fire-and-forget; UI shows cache meanwhile).
- **Render**: Android = **Vico** line+area in `ui/PriceChart.kt` (the CMP `TickerDetailScreen`); iOS = **Swift Charts** in `iosApp/.../TickerDetailView.swift` (`PriceSection`, with `.chartXSelection` scrubbing), reading `state.priceSeries` via SKIE.
- **Why no proxy Worker**: Yahoo bans by *per-IP frequency* (see `../investing/tech-docs/reference/yfinance_access.md`). A client calling once-per-ticker from its own IP, Room-cached (6h freshness), is low-frequency and fine. A shared Worker would CONCENTRATE all users onto a few egress IPs — worse for the ban, not better. Mitigation is caching, not a proxy. **Required**: a browser `User-Agent` (else 429); fallback query1→query2; failures are graceful (keep cache / "chart unavailable", never retry-spam). Don't add tests that hit real Yahoo — use a fixture.
- **ToS caveat**: the v8 endpoint is unofficial (personal-use). For a store/commercial release, front it with a swappable server-side source; for this portfolio app, direct is fine.

### Room 3.0
- Room 3.0 is a **new package (`androidx.room3`) and a new Gradle extension (`room3 { }`)** — not `androidx.room` / `room { }` as in 2.x. Mixing them silently fails to generate code.
- The KSP processor must be added **per target** (`kspAndroid`, `kspIosArm64`, `kspIosSimulatorArm64`); a bare `ksp(...)` only wires Android.
- `@ConstructedBy` + `expect object : RoomDatabaseConstructor` is mandatory for KMP; Room's KSP generates the actuals.
- The cache is disposable: the builder uses `fallbackToDestructiveMigration(dropAllTables = true)`, so a schema change is a **version bump with no Migration**. Do not hand-write migrations for `tickers`/`sync_metadata`.

### AGP 9 + KMP
- KMP modules use **`com.android.kotlin.multiplatform.library`**; the classic `com.android.library` is no longer compatible with the KMP plugin.
- **Never apply `org.jetbrains.kotlin.android`** to `:composeApp`. AGP 9 ships built-in Kotlin support and rejects the standalone plugin.
- The Android target is configured **inside the `kotlin { androidLibrary { } }` block**, not in a top-level `android { }`.
- **compileSdk must be 37**, not 36: `androidx.lifecycle` 2.11.0's AAR metadata demands it and `checkDebugAarMetadata` fails otherwise. `targetSdk` stays 36.
- `-Xexpect-actual-classes` is required — `expect class`/`expect object` are still flagged Beta and warn on every compile.
- **`:shared`'s `composeResources` are NOT propagated to the consumer APK.** A plain `assembleDebug` ships ZERO composeResources → the app crashes on the first `stringResource`. `composeApp/build.gradle.kts::stageComposeResForApp` copies them into the app's assets in the package-qualified layout (`assets/composeResources/<packageOfResClass>/…`) so the app launches. Same root cause as the host-test staging; remove both when the KMP androidLibrary plugin propagates composeResources to consumers.

### Supabase / PostgREST
- **A single response is capped at 1000 rows server-side**, whatever `limit` says (the table holds ~1800). A naive GET silently truncates the universe and corrupts the board. Page with the `Range` header until a short page comes back (`SupabaseScannerApi`).
- The `data` JSONB payload is flattened by the `select` (`clenow:data->clenow`). Keep `SELECT` in sync with the web client.
- **Every field except `ticker` is nullable.** The pipeline publishes rows in varying completeness; the client must never crash on a partial row. (As of writing, `price_eur` is null for **every** row upstream — the UI's "—" there is correct, not a bug.)

### Secrets
- `SUPABASE_URL` / `SUPABASE_ANON_KEY` live in **`local.properties`, which is git-ignored**. A Gradle task generates `SupabaseConfig.kt` into `build/`.
- **Never commit the anon key**, never move it into a tracked file, never paste it into a doc or a test fixture.

## Development Guidelines

### Core Principles
- **Minimize changes**: do the least that solves the problem; preserve the current implementation.
- **Study existing code first**: find the nearest similar code and match its style before writing new.
- **Clean up**: after changes, remove dead code and unused resources.
- **Quality over token savings**: don't cut corners on exploration or verification.

### Comments & Documentation
- **Avoid comments unless genuinely important** — prefer self-documenting names.
- When a comment IS warranted, explain **why**, not what: the fail-safe null handling, the 1000-row cap, the `init`-prefix mangling. These are the comments that earn their place here.
- **All comments and documentation in English.**
- **Keep documentation updated**: if a decision invalidates this file, update it in the same change.
- **Propose, never autonomously create,** new documentation files.

### Naming
- Screens: `XxxScreen` (stateful + stateless overloads). ViewModels: `XxxViewModel`. State: `XxxUiState`. Side effects: `XxxSideEffect`.
- Use cases: `VerbNounUseCase` with `operator fun invoke(...)`.
- Repositories: `XxxRepository` (interface, domain) / `XxxRepositoryImpl` (data).
- NavKeys: nested in `AppNavKey`, named after the destination (`Leaders`, `TickerDetail`).
- Mappers: private extension functions in the data layer (`TickerDto.toEntity()`, `TickerEntity.toDomain()`).
- `camelCase` for dimensions, `SCREAMING_SNAKE_CASE` for compile-time constants.
- **Prefer short imports over fully qualified names.**

### Dimensions & Constants (Compose UI only)
- These rules apply to Compose UI / presentation code. Non-UI constants (API, data layer) stay in their class or companion object — e.g. `PAGE_SIZE` belongs in `SupabaseScannerApi`.
- Keep UI dp values and UI constants near their screen; use `internal` to hold module boundaries.
- Semantic colours shared across screens go in `ui/Colors.kt` (`PositiveGreen`/`NegativeRed`) — not `colorScheme.error`, which means "something went wrong", not "negative number".

### Testing
Two source sets by capability (run: `./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test`):
- **`shared/src/commonTest`** — framework-free, runs on **both** JVM and iOS Native: `MomentumLeadersViewModelTest`, `TickerRepositoryImplTest` (offline-first: a failed refresh must not blank the cache), `NavigatorTest`. `kotlin.test` + Turbine + `kotlinx-coroutines-test` + hand-written fakes. **No MockK** (JVM-only) and **no real Room** (needs a platform driver) here — that keeps the suite green on Native too.
- **`shared/src/androidHostTest`** — JVM-only, needs a real SQLite engine or Robolectric. `ScannerDaoTest` runs the DAO's real leaders SQL against in-memory Room (`sqlite-bundled-jvm` supplies the host native the Android AAR lacks) with 13 cases covering the fail-safe gate, sort, and geo predicates — the port's correctness proof. Enabled via `androidLibrary { withHostTest { } }` (the AGP9 KMP name for `androidUnitTest`).
- Prefer testing ViewModels/use cases with fakes of the repository interface; the domain layer is framework-free precisely so this stays trivial.
- Navigation is testable as plain list manipulation — assert on `Navigator.backStack`, no Compose needed.
- **Never use optionals in test assertions**: `result!!.message`, not `result?.message` — tests must fail loudly.
- Model Creators (`modelcreators/`, `base/ModelCreators.kt`) build test data, per the Wishew convention.
- **`composeApp/src/test`** — Roborazzi screenshot tests of the shared CMP screens, run on the host JVM (`:composeApp:recordRoborazziDebug` / `verifyRoborazziDebug`). They live in the app module, NOT :shared, on purpose: capturing a CMP screen that uses `composeResources` (`stringResource`) needs the compiled `.cvr` on the test classpath, and the AGP9 KMP `androidLibrary` does not propagate them to a host test. The `stageComposeResForTest` Copy task (composeApp `build.gradle.kts`) stages :shared's prepared composeResources into the unit-test classpath in the package-qualified layout the runtime expects; `PreviewContextConfigurationEffect()` then reads them from the classpath. `@Config(application = Application::class)` avoids the app's Koin start firing per test. This was the one genuinely hard piece of test infra — don't "simplify" the staging away.

### Git & Version Control
- **NEVER commit changes autonomously** — finish without committing.
- Only stage/commit when the user explicitly asks. Let the user decide what and when.
- **NEVER run `git checkout`** unless explicitly requested.
- **NEVER perform destructive git operations** (reset, revert, clean) that could lose work, even with permissions granted.
- **Before running a bash command that could touch many files**, especially with many modified files present, STOP and ask.
- **Use `git mv`** to move/rename files, never delete-and-recreate.
- When in doubt about a destructive operation, do nothing and explain what you would do.

## Common Issues & Solutions

- **`Unresolved reference` only on iOS** → JVM-only API in commonMain. Check the `expect`/`actual` (`Dispatchers.IO`, Koin `GlobalContext`, `rememberNavBackStack`).
- **`checkDebugAarMetadata` fails** → compileSdk drifted below 37.
- **Swift can't see a Kotlin function** → name starts with `init`, or it has default arguments. Wrap it.
- **iOS crashes at startup in `PlistSanityCheck`** → `CADisableMinimumFrameDurationOnPhone` missing from `project.yml`; re-run `xcodegen generate`.
- **`Class 'X' is not registered for polymorphic serialization` on iOS** → a NavKey is missing from the `SerializersModule` in `NavigationState.kt`.
- **Empty leaders board** → the sync truncated at 1000 rows, or a quality verdict is null and correctly fail-safed out. Check `ScannerDao` before "fixing" the SQL.
- **Room generates nothing** → `androidx.room` used instead of `androidx.room3`, or the KSP processor not added for that target.
- **Stale dependency resolution after a version bump** → `./gradlew clean` and drop the configuration cache.
