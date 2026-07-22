# iOS interop

Everything that crosses Kotlin↔Swift, and the one place this app is not shared
code. Read before touching the price chart, SKIE, or the Xcode shell.

---

## 1. The shape: one app, one seam

`MainViewController()` (iosMain) hosts **`StockpickersRoot` — the whole app**, Nav3
back stack included, exactly as Android does. `ContentView.swift` is a bare
`UIViewControllerRepresentable` with no SwiftUI `NavigationStack`.

One navigation model, one copy of every screen and every string.

The single exception is the price chart.

### Why the chart, and why only the chart

Charts are where a native renderer earns its keep: Swift Charts gives 120 fps
scrubbing and platform-idiomatic interaction that a Skia-drawn chart would have to
reimplement. Everything else on the screen is text and layout, where sharing wins.

### The dependency runs upwards

This is the part worth understanding before changing it:

- `ui/PlatformPriceChart.kt` (commonMain) declares an `expect` composable.
  Android's actual is Vico; iOS's actual is a `UIKitViewController` hosting a
  SwiftUI view.
- **The SwiftUI view lives in the Xcode target, which depends on the framework** —
  so the framework cannot reference it. iosMain declares the
  `NativePriceChartRenderer` interface and a `NativePriceChart.renderer` slot;
  `iOSApp.init` fills it in with `PriceChartRenderer`
  (`iosApp/iosApp/PriceChartView.swift`).
- **`renderer` is nullable and the composable falls back to Vico.** An inverted
  dependency normally leaves a hole only the app can fill, and forgetting to fill it
  fails at runtime. Here, forgetting costs a plainer chart and nothing else.
- The renderer has **two methods, not one**: `makeController()` then
  `update(controller:points:positive:)`. Rebuilding the controller on every data
  change would restart the chart — losing the scrub selection and replaying the
  entry animation on every range tap. Swift keeps an `ObservableObject` on a
  `UIHostingController` subclass and mutates it.
- The composable takes **`positive: Boolean`, not a `Color`**. Each platform applies
  its own palette to the same semantic fact.

### The experiment that was reverted

The whole detail screen was once native SwiftUI, observing the shared
`TickerDetailViewModel` through SKIE (`for await state in vm.uiState`). It
demonstrated more interop — and cost two navigation stacks to keep in step plus a
second copy of every screen and every string.

Reverted deliberately. The reasoning is worth knowing; the code is in the history.
**"One Compose app with one native seam" is the conclusion, not the starting point.**

---

## 2. Kotlin/Native → Obj-C export

### Names starting with `init` are mangled

The exporter treats them as the Obj-C initializer family, so `initKoin()` reaches
Swift as `doInitKoin()`. The iOS entry point is therefore named **`startKoinIos()`**.

**Never expose an `init*` top-level function to Swift.**

### Default arguments do not survive

`initKoin(appDeclaration: KoinApplication.() -> Unit = {})` forces Swift to pass a
lambda. `startKoinIos()` wraps it so Swift calls a bare function — the wrapper
exists for *two* reasons, not one.

### Nullable primitives cross boxed

`KotlinDouble?` / `KotlinInt?`, read with `.doubleValue` / `.intValue`. SKIE fixes
Flow, suspend, sealed classes and enums — not the primitive boxing of arbitrary
data-class properties. That is a broader Kotlin↔Obj-C limit.

### JVM-only APIs are invisible on Native

The recurring shape of "compiles on Android, breaks at link":

| Don't | Do |
|---|---|
| `Dispatchers.IO` in commonMain | the project's `expect val ioDispatcher` |
| Koin `GlobalContext` | `org.koin.mp.KoinPlatform` |
| reified `serializer<SomeInterface>()` | an explicit `PolymorphicSerializer(...)` |

---

## 3. SKIE

Still on, and it is **why Kotlin is pinned to 2.4.0**: SKIE hooks compiler
internals and supports Kotlin up to 2.4.0, so a newer Kotlin would silently disable
it. On a KMP app the toolchain is only as new as its critical iOS plugin.

What it buys today: exported enums and the `NativePriceChartRenderer` protocol are
pleasant to implement in Swift instead of being Obj-C-shaped.

Note it no longer bridges any `Flow` to Swift — no `StateFlow` crosses the boundary
since the SwiftUI screen was reverted. Any README or doc claiming otherwise is
stale.

---

## 4. Compose Multiplatform on iOS

- **`CADisableMinimumFrameDurationOnPhone: true` is REQUIRED in `Info.plist`.**
  Without it `ComposeUIViewController`'s `PlistSanityCheck` throws at startup. It
  lives in the `info:` block of `iosApp/project.yml`.
- **CMP 1.11.1 publishes no `iosX64` artifacts** (the last that did was
  1.11.0-alpha01). Declaring the Intel-simulator target breaks commonMain
  dependency resolution. The target list is intentionally
  `iosArm64 + iosSimulatorArm64`.
- Set `binaryOption("bundleId", …)` on the framework, or the linker warns it cannot
  infer one.

---

## 5. The Xcode loop

`iosApp/project.yml` is the **source of truth**; the `.xcodeproj` is generated and
git-ignored.

```bash
cd iosApp && xcodegen generate          # after editing project.yml

cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
xcrun simctl install booted <path>/iosApp.app
xcrun simctl launch booted app.stockpickers.kmp.ios
```

A `preBuildScript` runs `:shared:embedAndSignAppleFrameworkForXcode`, so the
framework is rebuilt as part of the Xcode build.

**Link the framework for every commonMain change**, not just before a release:

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

Android compiling proves almost nothing about Kotlin/Native. expect/actual gaps,
Native-only API restrictions and Obj-C export problems appear only here.

---

## 6. Keeping the two chart renderers in step

They render the same `PriceSeries`, so a change to one usually needs the other.

**The last x-axis label needs an `offset`, on both charts.** Labels start half a
step in (`spacing / 2`) so none lands on the first or last point; an edge label then
has only half a slot of width and gets ellipsised — the symptom was a final tick
reading `".."` instead of `"Jul"`. Vico's `addExtremeLabelPadding` alone was **not**
enough: it reserves room, but not enough of it.

**Both charts auto-scale Y to the data, not from zero**, or an intraday range looks
flat.

Neither is covered by the screenshot tests — the chart is deliberately excluded
there (see [TESTING.md §7](TESTING.md)). **Verify chart changes on a simulator.**
