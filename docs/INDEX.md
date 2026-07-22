# Documentation index

A routing table, not a table of contents. Every entry says what a file **decides**,
so you can skip the fourteen that do not apply. Read the one you need, not the set.

`CLAUDE.md` at the repo root stays loaded and carries the rules; these files carry
the detail behind them.

---

## Route by task

| I need to… | Read | Skip unless |
|---|---|---|
| Understand the layers, or place a new class | [ARCHITECTURE.md](ARCHITECTURE.md) | — |
| Add a screen end to end | [ADDING_A_SCREEN.md](ADDING_A_SCREEN.md) | It links out to the four below; start here rather than guessing which |
| Wire a destination, back stack, NavKey | [NAVIGATION.md](NAVIGATION.md) | Nav3 on CMP diverges from the AndroidX docs — do not trust a blog post over this |
| Register a dependency | [KOIN.md](KOIN.md) | Annotations + compiler plugin, **not** KSP. A `@KoinViewModel` copied from elsewhere will not compile |
| Slice a feature into modules | [MICROFEATURES.md](MICROFEATURES.md) | Only when adding a *feature*, not a class |
| Write or fix a test | [TESTING.md](TESTING.md) | The entry point for source sets, fakes, matchers, ViewModel and DAO tests |
| Touch a screenshot test | [SNAPSHOT_TESTING.md](SNAPSHOT_TESTING.md) | Its own file because the traps are non-obvious and expensive — see below |
| Add or change user-facing text | [I18N.md](I18N.md) | Also read it before hardcoding a string "just for now" |
| Call the scanner API, or change a query | [SUPABASE.md](SUPABASE.md) | The 1000-row cap alone justifies reading it |
| Understand what upstream owns | [UPSTREAM_CONTRACT.md](UPSTREAM_CONTRACT.md) | **Before changing any threshold or rule.** This app mirrors; it does not decide |
| Touch the price chart or market data | [YAHOO.md](YAHOO.md) | Unofficial endpoint with real constraints (UA, rate limits, caching) |
| Add an HTTP call | [NETWORKING.md](NETWORKING.md) | Shared client — the auth-header trap is in here |
| Write anything that crosses to Swift | [IOS_INTEROP.md](IOS_INTEROP.md) | `init*` mangling, boxed nullable primitives, SKIE |
| Understand a lint/detekt/spotless failure | [CODE_QUALITY.md](CODE_QUALITY.md) | — |
| Regenerate design assets | [STITCH_API.md](STITCH_API.md) | Rarely; tooling, not app code |

---

## Route by symptom

Error text first — that is what you will actually have in hand.

| Symptom | Where |
|---|---|
| `Unresolved reference` on iOS only, compiles on Android | [IOS_INTEROP.md](IOS_INTEROP.md) · CLAUDE.md *Common Issues* |
| `Class 'X' is not registered for polymorphic serialization` (iOS, runtime) | [NAVIGATION.md](NAVIGATION.md) — a NavKey missing from the `SerializersModule` |
| Swift cannot see a Kotlin function | [IOS_INTEROP.md](IOS_INTEROP.md) — `init*` prefix, or default arguments |
| iOS crashes at startup in `PlistSanityCheck` | CLAUDE.md — `CADisableMinimumFrameDurationOnPhone` |
| `java.io.EOFException` from `testDebugUnitTest`, no failing test | [SNAPSHOT_TESTING.md §6](SNAPSHOT_TESTING.md#6-when-the-suite-gets-slow) — the worker JVM died; read it as "something ran away" |
| Snapshot suite takes minutes instead of seconds | [SNAPSHOT_TESTING.md §2](SNAPSHOT_TESTING.md#2-animations-must-be-stopped-in-the-composable-not-in-the-test) |
| Two snapshot goldens share a checksum | [SNAPSHOT_TESTING.md §3](SNAPSHOT_TESTING.md#3-the-screenshot-is-the-viewport--mind-the-fold) — the subject is below the fold |
| `MissingResourceException` in a host test | [TESTING.md](TESTING.md) — the composeResources staging |
| Leaders board empty | [SUPABASE.md](SUPABASE.md) (1000-row truncation) or [UPSTREAM_CONTRACT.md](UPSTREAM_CONTRACT.md) (fail-safe quality gate) |
| Room generates nothing | CLAUDE.md — `androidx.room3`, and KSP per target |
| Chart empty or 429 | [YAHOO.md](YAHOO.md) |

---

## Reading order for a newcomer

[ARCHITECTURE.md](ARCHITECTURE.md) → [UPSTREAM_CONTRACT.md](UPSTREAM_CONTRACT.md) →
[ADDING_A_SCREEN.md](ADDING_A_SCREEN.md). The second is out of place only if you
assume this app decides anything about stock selection; it does not, and that is the
single most load-bearing fact in the repo.

---

## Keeping this honest

An index that lies is worse than none — it routes confidently to the wrong file.
When you add or rename a doc, add the row here in the same change. When a symptom
costs you more than an hour, add it to the symptom table: that hour is the evidence
it was not guessable.
