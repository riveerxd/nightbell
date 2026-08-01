# Pulse — handoff

**Status: complete and verified.** Signed release APK built, installed and
smoke-tested on an emulator; 100 automated tests pass (68 JVM + 32 on-device).

---

## Deliverables

| Item | Path |
| --- | --- |
| Release APK (signed) | `artifacts/Pulse-1.0.0-release.apk` |
| Debug APK | `artifacts/Pulse-1.0.0-debug.apk` |
| Gradle output (release) | `app/build/outputs/apk/release/app-release.apk` |
| Gradle output (debug) | `app/build/outputs/apk/debug/app-debug.apk` |
| Screenshots (23) | `artifacts/screenshots/` |

SHA-256:

```
2129ae2b0c00e65fb1414e035e7e61677d1b3a23fcd668c79c257f7822f1fa23  Pulse-1.0.0-release.apk
3efb74fd54afaa4dd73bedc1eb475f3b9d9ae1ddaf52b819a3732e1189c205a8  Pulse-1.0.0-debug.apk
```

Rebuilt after the colour/glow/toast pass. The upload links from the first build
have expired and pointed at the pre-fix APK — re-upload from `artifacts/` if a
download link is needed again.

Both artifacts come from a `./gradlew clean` rebuild and were re-verified after
it: the debug APK re-ran all 32 on-device tests green, and the release APK was
reinstalled and launched. Android APKs are not byte-reproducible across builds
(zip metadata differs), so rebuilding will yield a different hash for the same
sources.

APK facts: `me.river.pulse` · versionName `1.0.0` · minSdk 26 ·
targetSdk 36 · 9.5 MB · signed `CN=Pulse Monitor, O=Bohemian Karst, C=CZ`
(SHA-256 `c4c1192f…6a96`).

## Toolchain that worked

Java 21 · Gradle 8.13 (wrapper) · AGP 8.13.2 · Kotlin 2.2.21 ·
Compose BOM 2025.10.01 (Compose 1.9.4, Material3 1.4.0) · compileSdk 36.

> Newer androidx (core-ktx 1.19, lifecycle 2.11, Compose BOM 2026.x) hard-requires
> AGP 9.1+/compileSdk 37 and will fail the build on this toolchain. If you upgrade
> androidx, upgrade AGP and Gradle in the same commit.

## Emulator

Created for this build: **`pulse_api34`** — Pixel 6, API 34 `google_apis`
x86_64, 3 GB RAM, 4 cores, 1080×2340 @420dpi, `swiftshader_indirect`
(the session is headless — no `DISPLAY`, so no hardware GL).

```bash
$ANDROID_HOME/emulator/emulator -avd pulse_api34 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 3072
```

Boots in ~23 s. It died once mid-run when launched with plain `nohup`;
launching with `setsid nohup … < /dev/null &` has been stable since.

## Reproducing the verification

```bash
cd "/home/river/Projects/monitoring app"

# 1. JVM tests — 68
./gradlew :app:testDebugUnitTest

# 2. Build
./gradlew :app:assembleDebug :app:assembleRelease

# 3. On-device tests — 32.
#    Run them by hand rather than via connectedDebugAndroidTest: that task
#    uninstalls both APKs when it finishes, which deletes the screenshots
#    the run just wrote.
./gradlew :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant me.river.pulse.debug android.permission.POST_NOTIFICATIONS
adb shell am instrument -w \
  me.river.pulse.debug.test/androidx.test.runner.AndroidJUnitRunner

# 4. Collect screenshots (internal storage; adb shell cannot read Android/data on API 30+)
for f in $(adb exec-out run-as me.river.pulse.debug ls files/screenshots/ | tr -d '\r'); do
  adb exec-out run-as me.river.pulse.debug cat "files/screenshots/$f" > "artifacts/screenshots/$f"
done
```

`./gradlew :app:connectedDebugAndroidTest` also works and is what CI should use
— it just isn't how to harvest screenshots.

## Bugs the tests caught (and fixed)

1. **Recovery notifications were being erased.** `CheckEngine` posted the
   recovery notification and then unconditionally cancelled the stale down
   notification — same notification id, so the recovery vanished instantly.
   Caught by `recoveryNotificationReplacesTheOutage`. Fixed by skipping the
   cancel when the decision was `RECOVERY`.
2. **`goAsync()` NPE.** `AlertActionReceiver`/`BootReceiver` called
   `pending.finish()` on a null `PendingResult` when `onReceive` runs outside a
   real broadcast dispatch. Both are now null-safe.
3. **Lenient JSON silently "parsed" HTML.** `"<html>…"` came back as a bare
   string primitive, so a JSON assertion failed with a confusing "field missing"
   instead of "that isn't JSON". `Assertions.parseJson` now only accepts objects
   and arrays.
4. **History cap ignored small values** (`coerceAtLeast(5)` floor).
5. **Emulator shadow artifact** — see `softShadow()` in the README design notes.
6. UI polish found in screenshots: `MONITORS` label wrapping, a one-sample
   sparkline rendering as a lone dot, and a `—` placeholder leaking into the
   picker's "currently watching" line.

## Design pass — colour, glow, motion, toast

A later pass fixed four things. All 68 JVM and 32 on-device tests still pass;
the screenshots in `artifacts/screenshots/` were re-captured from the result.

1. **Health colours had been flattened to monochrome.** `PulseColors.Rose` and
   `Mint` were both `#FFFFFF` and `Amber` was `#EDEDED`, so `healthColor(DOWN)`
   returned white — a dead monitor rendered identically to a healthy one.
   Restored as real semantic colours. Everything routes through those constants,
   so one edit fixed orbs, pills, sparkline failure markers, latency bars, the
   history strip, error banners and the Danger button.
2. **The bloom was accent-tinted.** `softShadow()` stacked translucent rounded
   rects in the *card's* colour, giving every surface a neon halo. It is black
   now, and status colour moved to a 1 dp rim via `healthRim()` — applied only
   to DOWN and DEGRADED, so the broken card is the only one that stands out.
   The FAB's pulsing halo, the orb bloom and the aurora were all dialled back.
3. **Entrance animations replayed on every scroll.** See the `EntranceLog` note
   under Gotchas.
4. **The toast was a full-width banner** that covered the wordmark, led with a
   sparkle icon and glowed. It is now a text-width capsule below the header with
   a status dot, an opaque fill and a real drop shadow.

Known rough edge: all eight toast messages share one style, so the warning
("Notifications are blocked — enable them in system settings") shows the same
green dot as the confirmations. Needs a tone parameter threaded through
`PulseViewModels.toast`.

## What is *not* done

- No CI config, no Play Store metadata, no ProGuard/R8 shrinking
  (`isMinifyEnabled = false` for both build types — the release APK is 9.5 MB and
  would shrink meaningfully with R8 plus the WebView-bridge keep rules already
  present in `proguard-rules.pro`).
- No baseline profile tuning beyond what AGP generated.
- Screenshots were captured with motion intensity 0 (see below) — the animated
  aurora drift and sonar rings are not visible in stills.

## Gotchas for the next session

- **Infinite animations vs. Compose tests.** Every looping animation goes
  through `rememberLoopingFloat`, which collapses to a constant when
  `PulseMotion.enabled` is false. Tests set `motionIntensity = 0f` *before*
  launching the activity (hence `createEmptyComposeRule()` + manual
  `ActivityScenario.launch`, not `createAndroidComposeRule`). If you add a new
  `rememberInfiniteTransition` directly, the UI test suite will hang.
- **`SectionHeader` renders ALL-CAPS** but carries the human-cased title as its
  `contentDescription` (screen readers spell out all-caps). Match section
  headings in tests with `hasContentDescription("Configuration")`, not
  `onNodeWithText`.
- **LazyColumn nodes must be scrolled to before they exist.** Use
  `onNodeWithTag("dashboard-list" | "detail-list" | "settings-list")
  .performScrollToNode(...)`, and `performScrollToIndex(0)` before tapping a
  header button that has been recycled.
- **`Assertions` and `AlertDecider` are pure** — put new decision logic there,
  not in `CheckEngine`, so it stays unit-testable.
- **`StaggeredEntrance` needs a unique key and the screen's `EntranceLog`.**
  Reusing a key across call sites makes those items share one "already played"
  entry; dropping the log brings back the bug where every item re-animates each
  time it scrolls back into view. The suites run at `motionIntensity = 0`, so
  they will *not* catch a regression here — check it by hand.
- **Colour means health, nothing else.** `healthColor()` for content,
  `healthRim()` for card edges (transparent unless DOWN/DEGRADED). Don't reach
  for `Rose`/`Mint`/`Amber` as decoration, and don't tint `softShadow` — an
  accent-coloured shadow is what made the redesign read as AI-generated.
- **Run the on-device suites one class at a time.** Instrumenting all 32 in a
  single `am instrument` reliably kills this emulator partway through
  `PulseE2ETest`. Confirmed environmental, not a code regression: the same run
  dies identically on a build of the pre-change commit.

## Next polish ideas

1. R8 + resource shrinking for the release build (easy 3–4 MB win).
2. Home-screen widget and a Wear tile showing the worst monitor.
3. Import/export of the store JSON, and optional end-to-end-encrypted sync.
4. Multi-element page monitors (watch several nodes on one page load).
5. Latency SLO thresholds per monitor, so "slow" can alert independently of
   "down" (`Health.DEGRADED` already exists but never alerts).
6. Real backdrop blur via `rememberGraphicsLayer()` capture on API 31+ for
   genuinely frosted glass rather than a translucent pane.
7. A foreground service option for users who want strict sub-15-minute cadence.
