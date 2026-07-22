# Snapshot testing

Roborazzi + Robolectric, capturing the shared Compose Multiplatform screens on the
host JVM. Nineteen baselines, one per branch of the two screens' render logic.

**Read this before adding a snapshot, changing an animated component, or debugging a
slow / failing / suspiciously-green snapshot suite.** For everything else about
testing — source sets, fakes, ViewModel tests, DAO tests — see
[TESTING.md](TESTING.md).

```bash
./gradlew :composeApp:recordRoborazziDebug   # after an INTENDED UI change
./gradlew :composeApp:verifyRoborazziDebug   # the gate (also runs in .githooks/pre-push)
```

Both finish in **~10-20 seconds**. If they don't, something regressed — jump to
[When the suite gets slow](#when-the-suite-gets-slow).

---

## 1. The one rule that matters

> **A snapshot renders ONE static frame of a real viewport.**

Almost every trap below is a corollary. Anything that moves, or anything outside the
first screenful, is either invisible to the capture or actively hostile to it.

---

## 2. Animations must be stopped in the COMPOSABLE, not in the test

**Symptom:** a handful of tests take minutes each while the rest take ~0.1s. The
suite looks hung. `jstack` on the test JVM shows a thread pinned at ~100% CPU in:

```
java.lang.Class.forName0 (Native Method)
  ← ShadowDisplayEventReceiver.newVsyncEventData
  ← ShadowDisplayEventReceiver.onVsync
  ← ...scheduleVsync
```

**Cause:** Material's indeterminate indicators run an `InfiniteTransition`.
Robolectric's shadow Choreographer "advances the clock by the frame delay every time
a frame callback is added" — so the animation schedules a frame, the clock jumps, the
animation schedules another. The loop feeds itself, and every turn pays a reflective
`Class.forName`.

**Fix:** the component draws itself still when `LocalInspectionMode` is on.

```kotlin
@Composable
internal fun BusyCircularIndicator(modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        // Determinate overload: same Material component, nothing left to animate.
        CircularProgressIndicator(progress = { StillProgressFraction }, modifier = modifier)
    } else {
        CircularProgressIndicator(modifier)
    }
}
```

Live in `ui/Components.kt`: `BusyCircularIndicator`, `BusyLinearIndicator`. Use them
instead of the Material ones — a bare `CircularProgressIndicator()` anywhere in a
captured screen brings the loop back.

**Composite components animate too.** `PullToRefreshBox` has its own indicator and
needs the same treatment through its `indicator` parameter:

```kotlin
indicator = {
    if (!LocalInspectionMode.current) {
        PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = ...)
    }
}
```

### Why the test harness cannot fix this

Three test-side attempts were made and all failed. Do not retry them:

| Attempt | Outcome |
|---|---|
| `CompositionLocalProvider(LocalInspectionMode provides true)` in the test | Necessary but **not sufficient** — it only flips the flag the composables read. Without the checks above there is nothing to react to it. |
| `ShadowChoreographer.setPaused(true)` in `@Before` | Freezes the clock before the Activity gets window focus → Roborazzi dies on `NullPointerException` in `ActivityController.windowFocusChanged`. |
| `ShadowChoreographer.setPaused(true)` inside the composition | Too late — the animation has already registered its callbacks; and with the clock stopped the capture's own frame never arrives, so the test hangs. |

The composable is the only place that knows *before the first frame* that it must be
still. Same pattern as `wishew-android-4`'s `UrlImage` and `StepProgressIndicator`.

**A comparison threshold is the wrong tool.** `changeThreshold` was considered and
rejected on the numbers: on this screen a rotated spinner arc differs by ~0.26% of
pixels while a spinner that vanished entirely differs by only ~0.13%. Any threshold
loose enough to permit the first also permits the second — which is precisely the
regression the test exists to catch. Freeze the animation; do not widen the tolerance.

---

## 3. The screenshot is the viewport — mind the fold

**Symptom:** a test is green and asserts nothing. Two goldens are **byte-identical**.

The detail screen is one long scrolling column and the profile card alone is ~600dp
of prose, so on the standard 800dp device the momentum and quality cards never reach
the capture. Recorded that way, `quality_rejected` and `quality_unknown` came out
with the same MD5 as `data`.

**Fix:** give the test a taller device.

```kotlin
@Test
@Config(qualifiers = "w360dp-h1600dp-xhdpi")
fun tickerDetailScreen_qualityRejected() = ...
```

**Detection** — cheap, run it after any re-record:

```bash
cd composeApp/src/test/snapshots/images && md5 -q *.png | sort | uniq -d
```

Any output at all is a bug. Distinct states must produce distinct images.

---

## 4. Determinism rules for fixtures

- **No wall-clock in a captured label.** A relative timestamp ("5m ago") is computed
  against `now`, so the baseline rots by itself. Fixtures use `lastSyncedAt = null`.
- **No in-flight snackbar.** `errorMessage` stays null on the offline/server states:
  a snackbar animating into frame is nondeterministic, and that is also the honest
  post-dismiss state the sticky badge exists for.
- **No chart points.** See [The chart is deliberately not captured](TESTING.md#the-chart-is-deliberately-not-captured):
  Vico draws from a `LaunchedEffect` that races the capture and loses ~1 run in 3.
- **Never a never-ending `LaunchedEffect`** in a captured screen. One
  `while (true) { ...; delay(30_000) }` kept Robolectric's scheduler permanently
  non-idle. It was removed from the app, not worked around in the test.

---

## 5. Goldens

They live in `composeApp/src/test/snapshots/images/` and **are committed**. Earlier
they were written under `build/`, which is git-ignored and wiped by `clean` — on a
fresh checkout there was no baseline and every snapshot silently degraded into "the
screen composes without throwing".

Goldens are **host-dependent** (JVM version, OS font rendering). Fine for a solo
project; a CI runner on a different image would need its own.

On a failure, **look at the diff before re-recording**: `build/outputs/roborazzi/`
holds a `*_compare.png` per mismatch. Re-record only once the change is intended,
then commit the updated images with the code that changed them.

---

## 6. When the suite gets slow

It should be seconds. History: **33 min → 15 min → 12 s**. If it creeps back, do not
guess — the slow part was never where it seemed.

**Profile the live JVM.** This is what actually found every cause:

```bash
jstack $(pgrep -f "Gradle Test Executor" | head -1) | grep -A 8 '"SDK 34 Main Thread"'
```

**Get per-test times** rather than staring at the total — the cost is never evenly
spread. Fifteen of the nineteen tests here cost ~20s *together*; four cost everything
else:

```bash
python3 -c "
import re,glob
for f in glob.glob('composeApp/build/test-results/testDebugUnitTest/*.xml'):
    s=open(f).read()
    for x in sorted(re.finditer(r'testcase name=\"([^\"]+)\"[^>]*time=\"([0-9.]+)\"',s), key=lambda y:-float(y.group(2))):
        print(f'{float(x.group(2)):7.1f}s  {x.group(1)}')
"
```

### Dead ends, so nobody pays for them twice

All three looked like wins and none was the cause. The animation loop was, every
time; once it was fixed these were removed and the suite stayed fast.

| Knob | Why it looked right | Verdict |
|---|---|---|
| `forkEvery` | Robolectric leaks across tests (robolectric#8395), and forking bounds it | Re-pays sandbox init per fork. Made things *worse*. Default (0) is correct here. |
| `maxHeapSize` | The worker died with a bare `java.io.EOFException` | Real symptom, wrong cure — the JVM died because the runaway loop allocated, not because the heap was small. |
| `kotlinx.coroutines.debug=off` | Gradle passes `-ea`, which auto-enables coroutine debug; every dispatch then renames the thread (`Thread.setName`, native, under a lock). jstack caught it at 85% CPU | A genuine cost, but only *because* the loop was dispatching constantly. Irrelevant once it stopped. |

A bare `java.io.EOFException` on `testDebugUnitTest` means the worker JVM died with
no failing test to report. Read it as "something ran away", not "give it more RAM".

---

## 7. Adding a snapshot

1. Add a `@Test` calling `captureLeaders(name, state)` or `captureDetail(name, state)`.
2. Does the state show a spinner? Pass `freezeAnimations = true`, and make sure the
   component is a `Busy*Indicator` (§2).
3. Is the thing under test below the first screenful? Add a taller `@Config` (§3).
4. Does the fixture read a clock, or animate anything? Fix that first (§4).
5. `recordRoborazziDebug`, then check for duplicate checksums (§3).
6. Commit the image together with the code.
