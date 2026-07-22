# Testing

How tests are organised here and, above all, **which source set a new test belongs
in**. Read this before writing one: the three sets differ in what they are *able*
to run, and putting a test in the wrong one breaks a platform you were not looking at.

Run everything:

```bash
./gradlew :shared:allTests                  # JVM + iOS Native
./gradlew :composeApp:verifyRoborazziDebug  # screenshots
```

---

## 1. Where a test goes

| Source set | Runs on | Use it for | Cannot use |
|---|---|---|---|
| `shared/src/commonTest` | **JVM and iOS Native** | ViewModels, repository, domain, navigation, pure functions | MockK, Truth, real Room, Robolectric, anything JVM-only |
| `shared/src/androidHostTest` | JVM only | Real DAO SQL against real SQLite | — |
| `composeApp/src/test` | JVM (Robolectric) | Screenshots of the shared CMP screens | — |

**Default to `commonTest`.** It is the only set that proves a thing on both
platforms, and this project's whole risk profile is "green on Android, broken on
iOS". Drop to a platform set only when the test genuinely needs something
commonTest cannot have.

### The rule that decides it

`commonTest` compiles for Kotlin/Native. So:

- **No MockK, no Truth** — both are JVM-only, and not marginally so: MockK is built
  on JVM bytecode instrumentation (its `mockk-common` artifact has been frozen since
  2022), and Truth is a Java library on Guava that publishes **zero** Kotlin/Native
  artifacts. Neither can be made to work here. The multiplatform equivalents this
  project uses are **Mokkery** and **Kotest assertions** — see §3.
- **No real Room** — the driver is platform-specific. DAO behaviour is tested in
  `androidHostTest` against real SQLite; `commonTest` uses `FakeScannerDao`.
- **No Robolectric, no `androidx.test`.**

Breaking this compiles fine and passes `:composeApp:assembleDebug`. It fails at
`:shared:iosSimulatorArm64Test` — or, worse, only when the framework links.

> A live example of exactly this trap is preserved in `AppNavKeySerializationTest`:
> a reified `serializer<NavKey>()` resolves by reflection on the JVM and throws
> `Serializer for class 'NavKey' is not found` on Native. The test passed on
> Android and failed on iOS until it named the serializer explicitly.

---

## 2. Naming

`WHEN_condition_THEN_expectation`, as an identifier with underscores:

```kotlin
@Test
fun WHEN_refresh_fails_THEN_the_cache_is_not_wiped() { … }
```

**Documented exception — screenshot tests** use `screenName_scenario`
(`momentumLeadersScreen_withData`). A snapshot names an *image*, not a behaviour,
and the golden file is named after the method. Don't "fix" these for consistency.

### Assertions — Kotest matchers, `kotlin.test` runner

```kotlin
import io.kotest.matchers.shouldBe
import kotlin.test.Test          // the runner stays kotlin.test

@Test
fun WHEN_x_THEN_y() {
    result shouldBe expected
    leaders shouldHaveSize 3
    leaders shouldContainExactly listOf("HI", "MID", "LO")
}
```

`kotest-assertions-core` publishes real iOS klibs and pulls **no framework engine**,
so the matchers travel to Native while `@Test` stays exactly as it was. Do not
introduce a Kotest spec class — the runner is not changing.

**Mind the argument order.** `assertEquals(expected, actual)` becomes
`actual shouldBe expected`. The operands swap.

**Reach for the specific matcher, not `shouldBe true`.** This is the whole reason
the matchers are here — a boolean assertion throws away every value:

| Instead of | Write | Because on failure you get |
|---|---|---|
| `assertTrue(list.contains(x))` | `list shouldContain x` | the whole collection, not "expected true" |
| `assertTrue(list.isEmpty())` | `list.shouldBeEmpty()` | what was actually in it |
| `assertEquals(n, list.size)` | `list shouldHaveSize n` | the size *and* the contents |
| `assertEquals(listOf(…), actual)` | `actual shouldContainExactly listOf(…)` | which elements were missing/unexpected |
| `assertNull(x)` | `x.shouldBeNull()` | the value that was not null |

Honest limit: on **data classes** `shouldBe` gives much the same dump as
`assertEquals`. The win is concentrated in collections and in replacing
`assertTrue(predicate)`.

Use `withClue("…") { … }` where a bare failure would not say which iteration or
which case broke.

**Never use optionals in an assertion.** `result!!.message`, not `result?.message`:
a test must fail loudly on the line where the assumption broke, not silently pass
because a null short-circuited the comparison.

---

## 3. Fakes, mocks and MockEngine

Three tools, and the choice between them is not a preference:

**Driving state → a hand-written fake.** `FakeTickerRepository`, `FakeScannerDao`.
They expose their state as public `MutableStateFlow` fields, which is far more
readable for *setting up a world* than stubbing a flow:

```kotlin
val repository = FakeTickerRepository()
repository.leadersFlow.value = TickerModelCreator.list(3)
repository.refreshResult = RefreshResult.Failed(RefreshFailure.OFFLINE, "network down")
```

**Verifying interactions → Mokkery.** Call counts, captured arguments and "this was
*not* called" are where a hand-written fake turns into bookkeeping. Mokkery is the
KMP-native mocking library (a compiler plugin, so it works on Kotlin/Native where
MockK structurally cannot).

Wrap the fake in a **`spy`** and you keep both halves — the fake drives the flows,
the spy records the calls:

```kotlin
private val fake = FakeTickerRepository()
private val repository: TickerRepository = spy<TickerRepository>(fake)

// setup goes through the fake…
fake.leadersFlow.value = TickerModelCreator.list(3)

// …verification through the spy
verifySuspend(exactly(1)) { repository.refreshProfile("NVDA") }
verify { repository.observeMomentumLeaders(LeaderSort.ONE_MONTH, GeoFilter.ASIA, DEFAULT_LIMIT) }
```

**Name the interface explicitly** — `spy<TickerRepository>(fake)`, not `spy(fake)`.
Type inference picks the concrete fake, which is `final`, and Mokkery cannot subclass
it: *"Type 'FakeTickerRepository' is final and cannot be used with 'spy'"*.

That single `verifySuspend` replaced a counter **and** a captured-argument field: it
checks both that the call happened exactly once and that it carried the right ticker.
`FakeTickerRepository` used to hand-maintain four counters and three "last call"
fields for this; it now records nothing.

**Do not replace the fakes wholesale with mocks.** Stubbing six flows to stand a
ViewModel up is worse than one line assigning a `MutableStateFlow`.

**HTTP we do not own → Ktor's `MockEngine`.** `SupabaseScannerApi`,
`SupabaseDescriptionsApi` and `YahooChartApi` are final classes, so there is no
seam to substitute — and MockK, which could fake them on the JVM, is unavailable
here. Faking at the *engine* level is the least invasive option and it exercises
the real DTO deserialization and pagination as a bonus.

```kotlin
val engine = MockEngine { respond(content = fixtureJson, status = HttpStatusCode.OK, …) }
SupabaseScannerApi(HttpClient(engine) { … }, baseUrl = "https://test.local", anonKey = "anon")
```

**Never write a test that reaches the real Yahoo or Supabase.** Use a fixture.

---

## 4. Model Creators

Test data comes from `modelcreators/`, not from inline construction, so that adding
a constructor argument is a one-line change instead of a sweep across files.

Two interfaces (`base/ModelCreators.kt`):

- `SingleModelCreator<T>` — one fully-populated `model`.
- `MultipleModelsCreator<T>` — adds `list(count)`, whose items are *distinct*
  (descending `clenow`, so ordering is assertable).

Derive variants with `copy`:

```kotlin
val row = TickerEntityModelCreator.model.copy(qualityPasses = null)
```

`TickerEntityModelCreator.model` is deliberately a **fully-qualifying** row, so
every gate test carves out exactly one reason to be excluded.

**Module boundary:** creators live in `:shared/commonTest`, which
`:composeApp/src/test` cannot see without test fixtures. The snapshot fixtures are
therefore local to `ScreenSnapshotTest` on purpose — and their string lengths are
load-bearing (taken from real rows so text wrapping is actually exercised).

---

## 5. ViewModel tests

Extend `ViewModelTest`. It installs `Dispatchers.setMain` with an
`UnconfinedTestDispatcher` and tears it down after.

**Why Unconfined and not Standard:** these ViewModels do work in `init { }`. With
`Unconfined` that work runs eagerly and the `StateFlow` has settled by the time the
test looks, so no `advanceUntilIdle()` dance is needed.

**Why the loading frame is read with `.value` and not `awaitItem()`:** the state is
`stateIn(WhileSubscribed)`, so the upstream flow is cold until someone collects.
The initial `isLoading = true` frame therefore exists *before* Turbine subscribes,
and asking Turbine for it waits forever.

```kotlin
assertTrue(viewModel.uiState.value.isLoading)   // before collecting
viewModel.uiState.test {
    val settled = awaitUntil { !it.isLoading }  // discards intermediate frames
    …
}
```

`awaitUntil` (in `ViewModelTest.kt`) exists because a `combine`-based state emits
transient frames as its sources arrive; asserting on the first one is flaky by
construction.

**Wire real use cases over the fake repository.** The use cases are one-line
pass-throughs, so testing them separately would assert nothing that the ViewModel
test does not already cover — which is why there is no `usecases/` package here,
unlike the layout this convention came from.

---

## 6. DAO tests (`androidHostTest`)

`ScannerDaoTest` runs the **real leaders SQL** against in-memory Room with the
`BundledSQLiteDriver`. This is the correctness proof for a query ported from
upstream, so it is worth the JVM-only confinement.

`sqlite-bundled-jvm` supplies the host native that the Android AAR lacks. Enabled
by `androidLibrary { withHostTest { } }` — the AGP 9 KMP name for `androidUnitTest`.

### The `@Upsert` artefact — not a defect

Re-upserting an existing primary key **always fails in this source set**. Room
INSERTs and, on a uniqueness violation, decides to UPDATE by string-matching the
exception message. `isReturnDefaultValues = true` stubs the
`android.database.SQLException` constructor, so the message never reaches the
object and Room rethrows.

It is an artefact of the test environment: on a device the real constructor keeps
the message, and on iOS no `android.jar` is involved. **Do not "fix" a DAO because
of a failure here, and do not add a re-upsert test to this source set.**

---

## 7. Screenshot tests

Roborazzi + Robolectric, in `:composeApp` — **not** in `:shared`, and not by choice:
capturing a CMP screen that calls `stringResource` needs the compiled `.cvr` files
on the test classpath, and the AGP 9 KMP `androidLibrary` plugin does not propagate
composeResources to a consumer's host test. The `stageComposeResForTest` Copy task
stages them in the package-qualified layout the runtime expects, and
`PreviewContextConfigurationEffect()` switches CMP to the classpath reader.

**This is the one genuinely hard piece of test infrastructure here. Don't simplify
the staging away.**

```bash
./gradlew :composeApp:recordRoborazziDebug   # after an intentional UI change
./gradlew :composeApp:verifyRoborazziDebug   # check
```

Goldens live in `composeApp/src/test/snapshots/images/` and **are committed**. They
were previously written under `build/`, which is git-ignored and wiped by `clean` —
so on a fresh checkout there was no baseline and every snapshot test quietly
degraded into "the screen composes without throwing".

Two things to know:

- `@Config(application = Application::class)` — the plain one, not
  `StockpickersApp`, whose `onCreate` calls `initKoin()` and would throw
  `KoinApplicationAlreadyStarted` on the second test.
- Goldens are **host-dependent** (JVM version, OS font rendering). Fine for a solo
  project; a CI runner on a different image would need its own baselines.

### Traps that cost hours — see SNAPSHOT_TESTING.md

Screenshot tests have failure modes the rest of the suite does not, and two of them
produce **green tests that assert nothing**. All of it, with the diagnosis steps, is
in **[SNAPSHOT_TESTING.md](SNAPSHOT_TESTING.md)**:

- animated components must be stopped in the composable, never from the test harness
- anything below the first screenful is not in the image (watch for duplicate checksums)
- fixture determinism: no wall clock, no in-flight snackbar, no chart points
- what to do when the suite goes from seconds to minutes

### The chart is deliberately not captured

`PriceChart` feeds Vico from a `LaunchedEffect` that suspends on `runTransaction`,
and that race against the capture is lost roughly one run in three. Whichever state
a baseline is recorded in, the other then fails. The fixtures therefore pass an
empty point list and the chart falls back to its placeholder. Nothing is lost — no
baseline ever asserted anything about the plot area. **Verify the chart on a device
or simulator.**

---

## 8. What is deliberately not tested

- **Use cases**, individually — see §5.
- **The Compose UI**, beyond screenshots. There is no `runComposeUiTest` suite; the
  screens are thin and the stateless overloads make the state transitions
  assertable at the ViewModel level instead.
- **Anything hitting a real network.**
