---
name: launch-ios
description: Build, install and launch the Stockpickers iOS app. Targets a physical iPhone when one is attached, otherwise the simulator. Use this whenever the user wants to run, start, launch, open, install, sideload, or see the iOS app — including phrasings like "fallo girare su iPhone", "avvia l'app iOS", "run it on the simulator", "does it start?", "check it launches", or when they want to verify a change actually works on iOS rather than merely compiling. Also use it when an iOS launch fails and needs diagnosing.
---

# Launch the iOS app

Builds `iosApp` and runs it. A physical iPhone wins over the simulator when one is
attached, because if someone bothered to plug in a device that is where they want to
see the app.

**This does not need the Xcode GUI** — the whole loop is `xcodegen`, `xcodebuild`,
`simctl` and `devicectl`.

## Facts about this project

Values you would otherwise have to rediscover each time:

| | |
|---|---|
| Xcode project | `iosApp/iosApp.xcodeproj` — **generated**, git-ignored |
| Source of truth | `iosApp/project.yml` (xcodegen) |
| Scheme | `iosApp` |
| Bundle ID | `app.stockpickers.kmp.ios` |
| Deployment target | iOS 26.0 |
| Kotlin framework | built automatically — see below |

**You never build the Kotlin framework yourself.** `project.yml` registers a
`preBuildScript` running `./gradlew :shared:embedAndSignAppleFrameworkForXcode`, so
`xcodebuild` pulls Gradle in on its own. Running Gradle first is a wasted couple of
minutes.

**Device builds are disabled in this repo.** `project.yml` sets
`CODE_SIGNING_ALLOWED: "NO"` with the comment *"Simulator-only builds"*. Step 2
checks this before touching a device so the failure is a sentence rather than a wall
of `xcodebuild` errors. The appendix explains how to lift it — propose it, never do
it unasked.

## Step 0 — Preflight

```bash
which xcodegen || echo "MISSING xcodegen"
xcodebuild -version | head -1
```

`xcodegen` missing → `brew install xcodegen`, then stop and let the user run it.

Regenerate the project if it is absent or stale — `project.yml` newer than the
`.xcodeproj` means someone edited the manifest and the generated project no longer
matches:

```bash
if [ ! -d iosApp/iosApp.xcodeproj ] || [ iosApp/project.yml -nt iosApp/iosApp.xcodeproj ]; then
  (cd iosApp && xcodegen generate)
fi
```

## Step 1 — Pick the target

```bash
xcrun devicectl list devices --json-output /tmp/devices.json >/dev/null 2>&1
python3 -c "
import json
d = json.load(open('/tmp/devices.json'))
for dev in d.get('result', {}).get('devices', []):
    p = dev.get('deviceProperties', {})
    c = dev.get('connectionProperties', {})
    if c.get('tunnelState') != 'unavailable':
        print(p.get('name'), dev.get('identifier'), c.get('tunnelState'))
" 2>/dev/null || echo "no devices"
```

An iPhone listed and reachable → **Step 2 (device)**. Nothing → **Step 3
(simulator)**. Say which one you picked and why before you start building; a build
takes minutes and the user should know where it is headed.

## Step 2 — Physical iPhone

Check signing is even possible **before** building:

```bash
security find-identity -v -p codesigning | tail -1
grep -n 'CODE_SIGNING_ALLOWED' iosApp/project.yml
```

Both must hold: at least one valid identity, and `CODE_SIGNING_ALLOWED` not `"NO"`.

If either fails, do not attempt the build — it cannot succeed, and the error output
buries the actual cause. Tell the user precisely which of the two is missing, point
at the appendix, and offer the simulator instead. Ask before falling back rather than
silently retargeting: someone who plugged in a phone may have a reason the simulator
does not serve.

When both hold:

```bash
UDID=<identifier from step 1>
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphoneos \
  -destination "platform=iOS,id=$UDID" \
  -derivedDataPath iosApp/build/dd build

APP=iosApp/build/dd/Build/Products/Debug-iphoneos/iosApp.app
xcrun devicectl device install app --device "$UDID" "$APP"
xcrun devicectl device process launch --device "$UDID" app.stockpickers.kmp.ios
```

The first launch on a device the user has not trusted stops at "Untrusted Developer".
That is resolved on the phone — Settings → General → VPN & Device Management — not
from here.

## Step 3 — Simulator

Prefer a simulator that is already booted; booting a second one is slow and leaves
the user staring at the wrong window.

```bash
xcrun simctl list devices booted | grep -E "iPhone|iPad"
```

Nothing booted → use `iPhone 17 Pro` (the device `CLAUDE.md` documents). If that name
is absent, take the first available iPhone on iOS 26.0 or newer — anything older
cannot install this app.

```bash
xcrun simctl boot "iPhone 17 Pro" 2>/dev/null   # already-booted is not an error
open -a Simulator
```

Build, install, launch:

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath iosApp/build/dd build

xcrun simctl install booted iosApp/build/dd/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl launch booted app.stockpickers.kmp.ios
```

`-derivedDataPath` is what makes the `.app` path predictable. Without it the bundle
lands in `~/Library/Developer/Xcode/DerivedData/iosApp-<hash>/`, and the hash forces
a search step on every run.

A clean build takes **several minutes** — Gradle links the Kotlin framework, then
Xcode compiles and links. Run it in the background and say so, rather than letting a
long-running foreground command look like a hang.

## Step 4 — Confirm it actually ran

`simctl launch` prints a PID the instant the process spawns, and prints it just the
same for an app that dies a second later. So the PID is not evidence. Two cheap
checks turn "I ran the command" into "it works":

```bash
sleep 5
xcrun simctl spawn booted launchctl list | grep -q stockpickers \
  && echo "alive" || echo "died after launch — see 'When it fails'"

xcrun simctl io booted screenshot /tmp/ios-launch.png
```

Read the screenshot. A process can survive while rendering nothing — a blank or
half-drawn screen is a real failure mode here, and the leaders board is distinctive
enough that one look settles it. Show the screenshot to the user; it is the thing
they actually wanted when they asked to run the app.

## When it fails

**`xcodebuild` fails, no useful message** — rerun the failing step alone and read the
last 40 lines. Gradle failures surface as a failed "Compile Kotlin Framework" phase
and are Kotlin errors, not Xcode ones; reproduce those faster with
`./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`.

**Installs, then dies at launch** — the app reached the device, so this is runtime.
Stream the log:

```bash
xcrun simctl spawn booted log stream --predicate 'process == "iosApp"' &
xcrun simctl launch booted app.stockpickers.kmp.ios
```

Or read the newest crash report in `~/Library/Logs/DiagnosticReports/`.

Two startup crashes are specific to this project and worth recognising on sight:

- **`PlistSanityCheck`** — `CADisableMinimumFrameDurationOnPhone` is missing from the
  generated `Info.plist`. It is in `project.yml`, so this means the project was not
  regenerated: `(cd iosApp && xcodegen generate)`.
- **`Class 'X' is not registered for polymorphic serialization`** — a NavKey missing
  from the `SerializersModule` in `NavigationState.kt`. Passes every Android check and
  the framework link, then crashes only here. See `docs/NAVIGATION.md`.

**Stale behaviour after a Kotlin change** — the framework is cached per configuration.
`./gradlew clean` then rebuild.

## Appendix — enabling device builds

Only if the user asks. Three things are needed:

1. An Apple developer account added to Xcode, giving a signing identity in the
   keychain (`security find-identity -v -p codesigning` must list one).
2. `DEVELOPMENT_TEAM` set to that team ID in `project.yml`.
3. Signing re-enabled for device builds while keeping simulator builds free of it,
   which is the point of the per-SDK form:

```yaml
CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]: "NO"
CODE_SIGN_STYLE: Automatic
DEVELOPMENT_TEAM: <TEAM_ID>
```

Then `(cd iosApp && xcodegen generate)`.

Edit `project.yml`, never the generated `.pbxproj` — it is git-ignored and the next
`xcodegen generate` discards it. And leave the change to the user: signing config
carries an account and a team ID, which is theirs to decide.
