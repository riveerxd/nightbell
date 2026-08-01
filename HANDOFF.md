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
e6288107f4feb3c04fb600d843a763fd4b6ce8d77f405e1d92713153f3082e0d  Pulse-1.0.0-release.apk
2b2344cc1681d515f9bba603c410bda36346cc78e27d089c7e9a1f352f34fae2  Pulse-1.0.0-debug.apk
```

**Download link (release APK, ~72 h retention):**
<https://litter.catbox.moe/uv5d8b.apk> — verified by re-downloading; SHA-256
matches the local file exactly.
Secondary (browser interstitial, not a direct link):
<https://tmpfiles.org/w4wPRhcCcxRV/pulse-1.0.0-release.apk>

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

## What is *not* done

- No CI config, no Play Store metadata, no ProGuard/R8 shrinking
  (`isMinifyEnabled = false` for both build types — the release APK is 9.5 MB and
  would shrink meaningfully with R8 plus the WebView-bridge keep rules already
  present in `proguard-rules.pro`).
- No baseline profile tuning beyond what AGP generated.
- Screenshots were captured with motion intensity 0 (see below) — the animated
  aurora, sonar rings and FAB halo are not visible in stills.

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
