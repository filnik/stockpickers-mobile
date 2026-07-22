# Code quality

Formatting, static analysis and the git hooks. **There is no CI on this project** —
the hooks and the rules in `CLAUDE.md` are the entire gate, so it is worth knowing
what each one does and when bypassing it is legitimate.

---

## 1. One-time setup

```bash
git config core.hooksPath .githooks
```

That is the whole installation. No Gradle plugin installs hooks here — see §5 for
why.

---

## 2. Commands

```bash
./gradlew spotlessApply          # format
./gradlew spotlessCheck detekt   # verify
```

Definition of done for any change:

```bash
./gradlew spotlessCheck detekt
./gradlew :composeApp:assembleDebug
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :shared:allTests
```

---

## 3. Spotless (formatting)

Applied **once, at the root** — it targets file globs rather than each module's
Kotlin source sets, so one block covers `:shared` (five source sets) and
`:composeApp` without the convention-plugin layer this project deliberately does not
have.

That file-glob approach is also *why* Spotless rather than ktlint-gradle: it is
structurally immune to the plugin-detection breakage that AGP 9 + KMP caused for
source-set-driven formatters.

- ktlint **1.8.0**, plus the Compose ruleset `io.nlopez.compose.rules` 0.6.3.
- `**/build/**` excluded — Room's KSP output, the Compose Resources accessors and
  the generated `SupabaseConfig.kt` all live there.

### Where the rules live

`.editorconfig` holds what **every** tool and the IDE should know: indent, charset,
the 120-column limit, trailing commas, import layout.

**Per-rule ktlint toggles live in the `spotless` block of the root
`build.gradle.kts`**, not in `.editorconfig` — Spotless does not reliably resolve
`ktlint_*` properties out of the file's glob section, and each toggle carries its
reason inline.

### Two decisions worth knowing

**`max-line-length` is deliberately ON.** Turning it off does not merely stop the
length check: the wrapping rules read the same budget, so with it off they collapse
multi-line signatures and expression bodies onto single very long lines.

**Four Compose rules are relaxed, one is allowlisted:**

| Rule | Why |
|---|---|
| `standard:filename` | does not understand KMP — wants `DatabaseBuilder.kt` renamed after its class, breaking the `.android.kt` / `.ios.kt` pairing |
| `standard:function-naming` | composables are PascalCase; tests are `WHEN_x_THEN_y` |
| `standard:property-naming` | Compose UI constants are top-level PascalCase vals |
| `compose:parameter-naming` | wants `onSortSelect`; `onXxxSelected` is the prevailing convention and is used consistently |
| `compose:compositionlocal-allowlist` | **allowlisted, not disabled** — `LocalMonoFamily` is deliberate, and any NEW CompositionLocal still has to justify itself |

The rest of the Compose ruleset is on, and it earns it: it caught two lambdas
captured by a `LaunchedEffect` that did not key on them (a real staleness bug) and
three composables emitting sibling content at the top level.

---

## 4. detekt (bug hunting)

`dev.detekt` **2.0.0-alpha.5**, root-only, `buildUponDefaultConfig = false` — so
`config/detekt/detekt.yml` **is** the entire ruleset.

### Why this version

The widely-used `io.gitlab.arturbosch.detekt` 1.23.x line stops at Kotlin 2.0.21
and floods a Kotlin 2.4 codebase with false positives (issue #8865, closed "not
planned"). Only the `dev.detekt` 2.x line parses Kotlin 2.4. It is alpha, accepted
deliberately — the same trade-off already made for Nav3.

**Rules were renamed in 2.x.** A 1.23 config fails hard rather than ignoring unknown
keys: `UnusedImports` → `UnusedImport`, and `UnusedPrivateMember` split into
`UnusedPrivateClass` / `UnusedPrivateFunction` / `UnusedPrivateProperty`.

### Why root-only, and why no type resolution

detekt's per-module task defaults `source` to `src/main/kotlin`, which in a KMP
module means it analyses **nothing**. One root task pointed at the source
directories sidesteps that entirely.

**Type resolution is off.** It does not cover `commonMain` — this project's entire
codebase (detekt#5961, still open) — and in a KMP build it compiles every Android
variant to build a classpath it then cannot use. Rules needing it are absent from
the config on purpose; do not add them.

### What it is for

**Bugs, not style.** Style is ktlint's job, and the two overlapping would produce
contradictory advice on the same line. Complexity, naming, formatting and comment
rulesets are absent deliberately — do not "complete" the config by enabling them.

The rule that matters most here is `SuspendFunSwallowedCancellation`: the repository
catches `Throwable` by design to keep the offline-first cache intact, and swallowing
`CancellationException` there would break structured concurrency.

### Suppressions

**No suppression without a written reason.** Prefer expressing intent in the code
over annotating around the rule — a deliberately-dropped exception is
`catch (_: Throwable)`, which both a reader and detekt understand, rather than a
`@Suppress`.

---

## 5. Git hooks

Committed in `.githooks/`, activated with `core.hooksPath`. **No plugin**: the
maintained one installs hooks at configuration time, which is unreliable with the
configuration cache this project has enabled, and the common `Copy`-task recipe
broke in Gradle 9 when `fileMode` was removed.

### `pre-commit` — seconds

Formats the staged Kotlin files and re-stages them.

It **refuses** when a staged file also has unstaged edits: `spotlessApply` rewrites
the whole working-tree file, so re-staging it would sweep in changes you chose to
leave out. It tells you and stops rather than silently widening the commit.

Kept fast on purpose. A slow pre-commit is one people bypass.

### `pre-push` — minutes, and that is fine

`spotlessCheck` + `detekt` + `:shared:testAndroidHostTest` +
`:shared:iosSimulatorArm64Test` + the iOS framework link +
`:composeApp:verifyRoborazziDebug`.

This is the real gate, standing in for the CI this project does not have. The iOS
link is included because Android compiling proves almost nothing about
Kotlin/Native.

**When the screenshot step fails, look at the diff before re-recording.**
`build/outputs/roborazzi/` holds a `*_compare.png` for every mismatch. A failure is
information: either the UI changed on purpose, or it changed by accident. Only once
you have decided it was on purpose:

```bash
./gradlew :composeApp:recordRoborazziDebug   # then commit the updated images
```

Re-recording reflexively turns the whole suite into a rubber stamp.

> **Robolectric's first run downloads Android SDK jars** and can take several
> minutes. That cost is once per machine, not once per push — but it is worth
> running `verifyRoborazziDebug` manually after a fresh clone so the first `git
> push` is not mistaken for a hang.

### Bypassing

```bash
git commit --no-verify
git push --no-verify
```

Legitimate: a WIP commit on your own branch, or pushing a branch you know is red and
are still working on.

**Not legitimate on `main`.** Nothing downstream catches what you skip.

---

## 6. What is deliberately absent

- **No CI.** A conscious trade-off for a solo portfolio project — and the reason the
  hooks are stricter here than they would be otherwise.
- **No Android lint configuration.** It runs with stock defaults on `:composeApp`
  and nothing enforces it. Worth adding if the app ever ships.
- **No code-coverage tool.** Coverage on a KMP project reports 0% for iOS code and
  would be actively misleading here.
- **No dependency or module-graph analysis.** Two modules; the graph fits in a
  sentence.
