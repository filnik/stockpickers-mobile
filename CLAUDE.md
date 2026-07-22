# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Stockpickers KMP is a Kotlin Multiplatform app (Android + iOS). It reads the `scanner_cache` table published by an upstream Python pipeline (via Supabase/PostgREST) and renders a **momentum leaders** board plus a **ticker detail** read-out.

Every screen is **Compose Multiplatform**, on both platforms, behind one Nav3 back stack. iOS keeps exactly **one native seam**: the price chart is Swift Charts, injected into the shared Compose screen. See [The native seam](#the-native-seam).

The client is **offline-first and read-only**: Room is the single source of truth the UI observes, and the network only ever writes *into* Room. It computes no investment logic of its own — see [Ownership of Business Logic](#ownership-of-business-logic).

**Module layout:**
- `:shared` — everything: data, domain, presentation, UI, navigation, DI. Compiles to an Android library and an iOS `Shared.framework`.
- `:composeApp` — the Android application shell (`MainActivity`, `StockpickersApp`). Thin by design.
- `iosApp/` — the Xcode app shell. `project.yml` is the source of truth (xcodegen); the `.xcodeproj` is generated and git-ignored.

## Documentation map

**This file holds what stops a wrong action. `docs/` hold what guides a right one** — read the relevant one when you reach that moment, not before.

| Read this | When |
|---|---|
| [docs/TESTING.md](docs/TESTING.md) | Writing or changing any test |
| [docs/NAVIGATION.md](docs/NAVIGATION.md) | Adding a destination or touching the back stack |
| [docs/IOS_INTEROP.md](docs/IOS_INTEROP.md) | Anything crossing Kotlin↔Swift, or the price chart |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Adding a layer, a use case, or a ViewModel |
| [docs/UPSTREAM_CONTRACT.md](docs/UPSTREAM_CONTRACT.md) | **Before touching the leaders SQL, any threshold, or any unit** |
| [docs/SUPABASE.md](docs/SUPABASE.md) | Changing what we read from `scanner_cache` / `descriptions_cache` |
| [docs/YAHOO.md](docs/YAHOO.md) | Changing the chart fetch, ranges, or caching |
| [docs/KOIN.md](docs/KOIN.md) | Adding a binding or a ViewModel |
| [docs/NETWORKING.md](docs/NETWORKING.md) | Adding an API client, auth, or retry |
| [docs/I18N.md](docs/I18N.md) | Adding, changing or deleting a string |
| [docs/ADDING_A_SCREEN.md](docs/ADDING_A_SCREEN.md) | Adding a screen — the whole checklist |
| [docs/CODE_QUALITY.md](docs/CODE_QUALITY.md) | Formatting, detekt, git hooks |
| [docs/MICROFEATURES.md](docs/MICROFEATURES.md) | Reference only — a pattern this codebase does not use yet |
| [docs/STITCH_API.md](docs/STITCH_API.md) | Generating UI mockups |

**Propose, never autonomously create,** new documentation files. If a change invalidates something here, fix it in the same commit.

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

# Tests. `allTests` covers BOTH :shared targets (testAndroidHostTest +
# iosSimulatorArm64Test); the snapshots live in :composeApp and are separate.
./gradlew :shared:allTests
./gradlew :composeApp:verifyRoborazziDebug

# Formatting + static analysis
./gradlew spotlessApply    # fix
./gradlew spotlessCheck detekt

# Clean
./gradlew clean
```

**Definition of done** — a change is "verified" when all four pass:

```bash
./gradlew spotlessCheck detekt
./gradlew :composeApp:assembleDebug
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :shared:allTests
```

There is **no CI**. The git hooks (`git config core.hooksPath .githooks`, see [docs/CODE_QUALITY.md](docs/CODE_QUALITY.md)) and the rules in this file are the only gate — nothing downstream will catch what they miss.

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
- **`navigation` (NavKeys) sits outside `ui`** so `presentation` can depend on it — ViewModels take their NavKey as a constructor parameter. Putting keys in `ui.navigation` would invert the layering.

### Ownership of Business Logic

**The scanner rules are owned by the upstream pipeline and mirrored by its web client. This app READS them; it does not invent them.**

**Read [docs/UPSTREAM_CONTRACT.md](docs/UPSTREAM_CONTRACT.md) before touching the leaders SQL, any threshold, or any unit.** The non-negotiables:

- **Do not "improve" a threshold locally.** Quality gate, Wyckoff phase, ADR dedup and the momentum windows are computed upstream; `ScannerDao.observeMomentumLeaders`'s SQL is a *port*. If upstream changes, port the change — never let them drift.
- **Read `passes_filters` as an opaque boolean.** Recomputing it locally reintroduces the exact bug the rule exists to prevent.
- **Fail-safe reading**: a missing quality verdict means UNKNOWN and must be EXCLUDED. `qualityPasses = 1` gives this for free (`NULL = 1` is NULL); `!= 0` would fail *open* and leak un-evaluated rows.
- **Units are upstream's.** `mom_*`, `ann_mom` and `roic` are **decimal fractions** (0.55 == +55%); `r2` and `peg` are plain ratios; **`clenow` is already ×100** — scaling it again gives 440 000. Never pre-multiply in domain/data: formatting is the UI's job (`ui/Formatting.kt`).
- **The country list in that SQL is a FOURTH copy** of the upstream buckets. Accepted debt: an offline-first client cannot call a source of truth it may not reach. Add a country upstream → add it here too. A fifth copy means publishing the buckets as data.
- **Our leaders legitimately differ from the published website**, which truncates at 1000 rows where we page. Do not align downward.

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

Full guide: [docs/NAVIGATION.md](docs/NAVIGATION.md). Adding a screen:
[docs/ADDING_A_SCREEN.md](docs/ADDING_A_SCREEN.md).

- **Only `Navigator` mutates the back stack.** Screens get `goTo`/`goBack`.
- **Resolve every ViewModel inside its `entry<>` block**, never via a defaulted `koinViewModel()` parameter — that is what scopes it per NavEntry. Pass the **whole NavKey**.
- **Add a NavKey → register it in the polymorphic `SerializersModule` in `NavigationState.kt`.** Miss it and iOS crashes at runtime, having passed every Android check and the framework link.

### Dependency Injection (Koin)

Full guide: [docs/KOIN.md](docs/KOIN.md).

- **Koin Annotations**, via the `io.insert-koin.compiler.plugin` **compiler plugin** — not the retired `koin-ksp-compiler`. KSP stays in the build for Room only.
- **Scope rule: everything is `@Single` except ViewModels (`@KoinViewModel`).** Singles are lazy and these are stateless collaborators.
- **`compileSafety = true`**: a missing binding is a build error on both targets, not an iOS crash. `strictSafety` is off — it adds no checking, and the pair being true makes `:shared` never up-to-date (~0.6s → ~20s per no-op build). See [docs/KOIN.md](docs/KOIN.md).

### Versions

Read `gradle/libs.versions.toml` — every pin there carries the reason it exists. The three that constrain everything else:

- **Kotlin is pinned to 2.4.0 for SKIE.** A newer Kotlin silently disables it.
- **compileSdk must be 37**, not 36 — `androidx.lifecycle` 2.11.0's AAR metadata demands it.
- **Nav3 is pinned to the exact JetBrains alpha** (`1.2.0-alpha02`); breaking changes land between alphas.

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
- **`CADisableMinimumFrameDurationOnPhone: true` is REQUIRED in `Info.plist`.** Without it `ComposeUIViewController`'s `PlistSanityCheck` throws at startup (`iosApp/project.yml`).
- The target list is intentionally `iosArm64 + iosSimulatorArm64` — CMP 1.11.1 publishes no `iosX64` artifacts, and declaring it breaks commonMain resolution.

### Navigation 3 on CMP
See [docs/NAVIGATION.md](docs/NAVIGATION.md) §5. The two that bite hardest:
- **`navigation3-ui` must come from the JetBrains port** — the AndroidX one publishes no iOS variants.
- **Do NOT add a `BackHandler` around `NavDisplay`.** It already wires the platform back signal on both platforms; adding one pops twice.

### The native seam
See [docs/IOS_INTEROP.md](docs/IOS_INTEROP.md).
- `MainViewController()` hosts **`StockpickersRoot` — the whole app**, Nav3 stack included, exactly as Android does. One navigation model, one copy of every screen and string.
- The **only** fork is the price chart: `ui/PlatformPriceChart.kt` is `expect`; Android draws Vico, iOS hands off to Swift Charts through `NativePriceChartRenderer`.
- **`renderer` is nullable and falls back to Vico** — forgetting to wire it costs a plainer chart, not a crash.
- **SKIE is why Kotlin is pinned to 2.4.0.** A newer Kotlin silently disables it.

### Market data (Yahoo)
See [docs/YAHOO.md](docs/YAHOO.md).
- A **browser `User-Agent` is required** (a default library agent gets 429); `query1` falls back to `query2`; failures are graceful and never retried in a loop.
- **Read `adjclose` when present**, falling back to `quote.close` for intraday. The raw series is unadjusted, so a split draws a cliff and the chart stops agreeing with the momentum figures beside it.
- **Never write a test that hits real Yahoo** — use a fixture.
- **Both charts auto-scale Y to the data**, not from zero; and both need the last x-label `offset` (an edge label otherwise ellipsises to `".."`).

### Room 3.0
- Room 3.0 is a **new package (`androidx.room3`) and a new Gradle extension (`room3 { }`)** — not `androidx.room` / `room { }` as in 2.x. Mixing them silently fails to generate code.
- The KSP processor must be added **per target** (`kspAndroid`, `kspIosArm64`, `kspIosSimulatorArm64`); a bare `ksp(...)` only wires Android.
- `@ConstructedBy` + `expect object : RoomDatabaseConstructor` is mandatory for KMP; Room's KSP generates the actuals.
- The cache is disposable: the builder uses `fallbackToDestructiveMigration(dropAllTables = true)`, so a schema change is a **version bump with no Migration**. Do not hand-write migrations for `tickers`/`sync_metadata`.
- **Re-upserting an existing PK always fails in `androidHostTest`** — an artefact of the stubbed `android.jar`, NOT a defect. Do not "fix" a DAO because of it; see [docs/TESTING.md](docs/TESTING.md) §6.

### AGP 9 + KMP
- KMP modules use **`com.android.kotlin.multiplatform.library`**; the classic `com.android.library` is no longer compatible with the KMP plugin.
- **Never apply `org.jetbrains.kotlin.android`** to `:composeApp`. AGP 9 ships built-in Kotlin support and rejects the standalone plugin.
- The Android target is configured **inside the `kotlin { androidLibrary { } }` block**, not in a top-level `android { }`.
- **compileSdk must be 37**, not 36: `androidx.lifecycle` 2.11.0's AAR metadata demands it and `checkDebugAarMetadata` fails otherwise. `targetSdk` stays 36.
- `-Xexpect-actual-classes` is required — `expect class`/`expect object` are still flagged Beta and warn on every compile.
- **`:shared`'s composeResources are NOT propagated** to the consumer APK or to host tests. Two `Copy` tasks in `composeApp/build.gradle.kts` stage them; without them the app crashes on the first `stringResource`. **Don't simplify them away** — see [docs/I18N.md](docs/I18N.md) §5.

### Supabase / PostgREST
Full guide: [docs/SUPABASE.md](docs/SUPABASE.md). The contract itself — units, the qualifying predicate, what NOT to align with the website — is [docs/UPSTREAM_CONTRACT.md](docs/UPSTREAM_CONTRACT.md).
- **A single response is capped at 1000 rows server-side** whatever `limit` says, and the table holds ~1800. Page with the `Range` header, or the board is silently corrupted.
- **Never put the Supabase auth headers in a `defaultRequest`.** The `HttpClient` is shared with `YahooChartApi` — a client-wide header ships the anon key to a third party.
- **Every field except `ticker` is nullable.** Never crash on a partial row.
- **Most tickers have no profile row.** Absence is the ordinary case; the card is simply omitted, never an error.

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

### Error Handling
- **Network failures never escape the repository as exceptions.** They become `RefreshResult.Failed(reason, message)` where the user must see it, or a silent no-op leaving the cache intact where they must not.
- **Catch `CancellationException` first and rethrow it**, before any broader catch. A bare `runCatching` around a suspend call swallows cancellation and breaks structured concurrency — detekt's `SuspendFunSwallowedCancellation` is on for exactly this.
- Where an exception is deliberately dropped, name it `_` (`catch (_: Throwable)`): that is what tells a reader, and detekt, that it is not an oversight.
- **Do not build a `Result<T>` wrapper or an `ApiException` hierarchy.** Three read-only clients do not need one; see [docs/NETWORKING.md](docs/NETWORKING.md) for when that changes.

### Debugging & Logging
- `android.util.Log` does not exist in commonMain. Use `println` with a grep-able prefix: `println("SP_DEBUG ClassName - method: message")`.
- **Never leave a debug log in a commit.**
- **Never remove the user's debug logs autonomously** — wait until they confirm the issue is resolved.

### Code Organization
- **Layering is enforced by package, not by module** — `:shared` is one Gradle module, so nothing checks this but review: `presentation` must not import `data.remote`/`data.local`; `domain` must not import `presentation` or `data`; `ui` never imports `data`.
- A composable repeated across screens **moves** into `ui/Components.kt`; it is never copied. Shared logic goes into a use case, never into a second ViewModel.

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
- Semantic colours shared across screens go in `ui/Theme.kt` (`PositiveGreen`/`NegativeRed`) — not `colorScheme.error`, which means "something went wrong", not "negative number".

### Testing

**Full guide: [docs/TESTING.md](docs/TESTING.md).** Three source sets, split by what each one is *able* to run:

| Source set | Runs on | Holds |
|---|---|---|
| `shared/src/commonTest` | JVM **and** iOS Native | ViewModels, repository, domain, navigation |
| `shared/src/androidHostTest` | JVM only | `ScannerDaoTest` — the real DAO SQL on real SQLite |
| `composeApp/src/test` | JVM (Robolectric) | Roborazzi snapshots of the shared CMP screens |

The rules that stop a wrong move:
- **No MockK, no Truth, no real Room in `commonTest`.** MockK and Truth publish **zero** Kotlin/Native artifacts — they are not awkward here, they are impossible. Use **Mokkery** (mocking) and **Kotest assertions** (matchers), both of which do. Room needs a platform driver.
- **Assertions are Kotest matchers on the `kotlin.test` runner.** `actual shouldBe expected` — note the operands swap versus `assertEquals`. Prefer a specific matcher (`shouldContain`, `shouldHaveSize`) over `shouldBe true`: a boolean assertion throws away every value on failure.
- **Fakes drive state; Mokkery verifies interactions.** Do not replace the fakes wholesale with mocks — assigning a `MutableStateFlow` beats stubbing six flows.
- **Never use optionals in test assertions**: `result!!.message`, not `result?.message` — tests must fail loudly.
- Test names are `WHEN_condition_THEN_expectation`. Snapshot tests are the documented exception: they name an image, not a behaviour.
- Build test data with the Model Creators in `modelcreators/`, not by hand.
- Don't "simplify away" the composeResources staging in `composeApp/build.gradle.kts` — it is what makes `stringResource` resolve in a host test.

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
