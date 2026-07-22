# Stockpickers KMP

An offline-first stock-screener client built with **Kotlin Multiplatform** and **Compose Multiplatform**: one codebase, one navigation model, and exactly one place where a native renderer earns its keep.

It renders a momentum-leaders board and a per-ticker detail view from a read-only Supabase table, with price charts drawn natively on each platform.

> **Not investment advice.** This is a personal portfolio and learning project. It is not published on any app store, it makes no recommendations, and the figures it displays are precomputed values shown as-is.

## What's interesting about it

**Offline-first, with Room as the single source of truth.** The UI only ever observes Room; the network writes *into* Room in the background (stale-while-revalidate). Lose connectivity and the last synced board stays on screen with an explicit "last updated" marker — never an empty state, never a spinner over nothing.

**One shared app, one native seam — arrived at by reverting the maximalist version.** Every screen is Compose Multiplatform on both platforms, behind a single Navigation 3 back stack. The one exception is the price chart: Android draws it with Vico, iOS hands off to Swift Charts for 120 fps scrubbing.

The detail screen *was* once entirely native SwiftUI, observing the shared ViewModel from Swift through [SKIE](https://skie.touchlab.co/). It demonstrated more interop and cost two navigation stacks to keep in step plus a second copy of every screen and every string. Reverting it is the more interesting result than building it was — and the seam that survived is the one where the platform difference is real.

**The strategy lives upstream, and stays there.** This client reads precomputed columns — `clenow`, the momentum windows, the quality-gate verdict — and only sorts and filters them. No formula, threshold or weighting is reimplemented here. The one predicate the app owns is "trend is positive". That boundary is a design rule, not an accident, and the DAO documents it.

**Charts collapse closed-market time.** Both platforms plot by point index rather than by timestamp, so overnight and weekend gaps aren't drawn as interpolated price movement — the same choice TradingView makes. Axis label granularity is derived from the window's actual span.

## Stack

| | |
|---|---|
| Language / UI | Kotlin 2.4.0, Compose Multiplatform 1.11.1 |
| Persistence | Room 3.0 (`androidx.room3`), SQLite bundled |
| Network | Ktor 3.5.1 (OkHttp / Darwin) |
| DI | Koin 4.2.2 + Koin Annotations, graph verified at compile time |
| Navigation | Navigation 3 (JetBrains CMP port) |
| iOS interop | SKIE 0.10.13 — Swift-friendly enums and protocols over the Obj-C header |
| Charts | Swift Charts (iOS) · Vico 2.5.2 (Android) |
| Build | AGP 9.3.0, Gradle 9.5, KSP2 |
| Tests | kotlin.test + Turbine (shared, run on JVM *and* iOS), Roborazzi screenshots |
| Quality | Spotless (ktlint + Compose rules), detekt, git hooks — no CI |

Kotlin is pinned to 2.4.0 rather than 2.4.10 because SKIE hooks compiler internals and supports up to 2.4.0. On a KMP app the toolchain is only as new as its most critical iOS plugin.

## Running it

Requires JDK 17+, Android SDK (compileSdk 37), and Xcode for the iOS target.

Create `local.properties` with your own Supabase project:

```properties
SUPABASE_URL=https://<your-project>.supabase.co
SUPABASE_ANON_KEY=<your publishable key>
```

The file is git-ignored; the build generates a Kotlin config from it into `build/`. **No key is committed to this repository.** The table it expects is `scanner_cache`, read-only via PostgREST.

```bash
./gradlew :composeApp:assembleDebug          # Android
./gradlew :shared:allTests                   # JVM + iOS Native
./gradlew :composeApp:verifyRoborazziDebug   # screenshot tests
./gradlew spotlessCheck detekt               # formatting + static analysis
```

For iOS, run `cd iosApp && xcodegen generate` (the `.xcodeproj` is generated, not committed), then open it and run on a simulator.

Design notes and conventions live in [`docs/`](docs/); [`CLAUDE.md`](CLAUDE.md) is the index.

## A note on the price data

Charts come from Yahoo Finance's public `v8/finance/chart` endpoint, called directly from the client on user demand and cached in Room (6h for daily ranges, 5min for intraday). The endpoint is undocumented and unofficial, and this is a personal-scale client: it fetches one symbol when you open a chart and never loops. A shared proxy was considered and rejected — it would concentrate every user onto a few egress IPs, which is worse for throttling, not better. Anything beyond personal scale should use a proper licensed data source.

## License

[MIT](LICENSE).
