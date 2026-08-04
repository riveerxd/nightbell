# Pulse

Uptime monitoring that lives on your phone and actually wakes you up.

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/minSdk-26-blue" alt="minSdk 26" />
  <img src="https://img.shields.io/badge/targetSdk-36-blue" alt="targetSdk 36" />
  <img src="https://img.shields.io/badge/release-2.2.2-FF4D57" alt="Release 2.2.2" />
  <img src="https://img.shields.io/badge/tests-249%20JVM%20%2B%2098%20on--device-2FD98A" alt="Tests" />
</p>

<p align="center">
  <img src="docs/screens/hero.png" width="900" alt="Dashboard, an urgent page arriving, and monitor detail" />
</p>

Point it at anything that answers over HTTP, or at one element on a rendered web
page, and it watches it, charts it, and gets loud when it breaks. No server, no
account, no third party. The phone in your pocket does the checking.

## Why

I kept missing outages. The monitoring services I tried either wanted a
subscription to text me, or sent a notification that looked exactly like a
marketing email and got swiped away with one. I wanted the thing that tells me
production is down to be impossible to confuse with anything else.

So urgent alerts in Pulse arrive as a red card that behaves like an incoming
call, with a looping alarm that keeps going until you acknowledge it, and it
wakes the screen if the phone is locked.

<p align="center">
  <img src="docs/screens/urgent-page.png" width="300" alt="An urgent page arriving over the launcher" />
</p>

Three buttons, because at 3am you want to decide one thing: Ack silences it,
Re-check runs the check again there and then, and the crossed bell mutes that
monitor for an hour.

## What it watches

| Kind | What it does |
| --- | --- |
| Status check | Hit a URL, assert on the status code. Exact, any 2xx, a range, or any response at all. |
| Request and response | Pick the method, set headers and a body, then assert on what comes back. Contains, does not contain, equals, regex, or a JSON field by path. |
| Page element | Loads the real page in an embedded WebView and watches DOM nodes you picked by tapping them. Existence, text, or an attribute. |

Page-element monitors watch any number of nodes per page and resolve them all
against one page load, so watching six costs about what watching one costs. The
expensive part is booting the WebView, not the assertions.

<p align="center">
  <img src="docs/screens/element-picker.png" width="270" alt="Tapping an element to watch it" />
  <img src="docs/screens/detail.png" width="270" alt="Monitor detail with latency chart" />
  <img src="docs/screens/settings.png" width="270" alt="Settings" />
</p>

## Getting the alerts to actually arrive

This is the part every phone monitoring app gets wrong, so it is worth being
specific about. Android will happily let an app think it is alerting you while
delivering nothing. Pulse needs four grants, and there is a screen on first
launch that walks you through them.

<p align="center">
  <img src="docs/screens/setup.png" width="300" alt="The permission setup screen" />
</p>

| Grant | What breaks without it |
| --- | --- |
| Notifications | Nothing is posted at all. |
| Unrestricted battery | Android can refuse the service that owns the page and its repeat loop. |
| Full-screen alerts | A page on a locked phone cannot wake the screen. It waits on the lockscreen. |
| Do Not Disturb access | Bedtime mode silences urgent pages completely, which is exactly when you want one. |

Two of those show a dialog in place. The other two are Special App Access
toggles, and Android exposes no API to request them, so the screen deep-links
straight to each toggle and re-checks when you come back. It cannot be one tap
and it does not pretend to be. It is four taps instead of hunting three settings
sections.

Urgent pages follow your ringer switch by default. Vibrate mode gets haptics
only. There is a setting to override that if you want a pager that answers to
nothing.

## Install

Grab the APK from [Releases](../../releases), or from `artifacts/` in this repo,
and sideload it. It is signed with my own key, so Play Protect will ask you to
confirm. There is no Play listing.

```
adb install -r artifacts/Pulse-2.2.2-release.apk
```

Anything from 2.0.0 onward updates in place and keeps your monitors. 1.x used a
different application id, so the only way across is the JSON export in Settings.

## Build

Needs JDK 17 and an Android SDK with API 36. `local.properties` wants
`sdk.dir=/path/to/Android/Sdk`.

```bash
./gradlew :app:assembleDebug          # debuggable
./gradlew :app:assembleRelease        # minified, needs keystore/keystore.properties
./gradlew :app:testDebugUnitTest      # 249 JVM tests
```

Release builds are signed from `keystore/keystore.properties`, which is
gitignored along with the key itself. Without it the release task still builds,
just unsigned.

## Tests

249 JVM tests cover the pure logic: the alert state machines, escalation,
quiet-hours arithmetic, assertions, the latency baseline, backup round-trips.
98 instrumented tests cover the parts that need a real Android, which is most of
the interesting ones. Notifications, channels, the foreground service, the
WebView element checker, widgets, and a suite that drives a genuine
connection-refused outage all the way to a red page on screen.

```bash
# one class at a time, the emulator does not enjoy the whole suite at once
adb shell am instrument -w -e class me.river.pulse.UrgentPageEndToEndTest \
  me.river.pulse.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The urgent path has a habit of breaking in ways only a device shows. The alarm
taking half a minute to stop after acknowledging, a custom notification layout
getting squashed vertically, colorisation being silently dropped. None of that
is visible from reading the code, so those cases got tests that were checked
against the broken version first.

## How it works

One `CheckEngine` runs a check, folds the result into persisted state, and
decides whether to interrupt you. Four alert tracks come off that: down and
recovery, degraded on latency, urgent paging, and the checker's own health,
which is deliberately separate so a bug in Pulse never gets reported as your
website being down.

State is one JSON document in DataStore, which makes the whole store trivially
exportable. Checks run on WorkManager by default, or on a foreground service if
you turn on strict mode and want a cadence Android will not batch away.

`docs/reference.md` has the long version, including the reasoning behind the
awkward parts.

## Known limitations

- Element checks need a WebView per page load. They are the slow kind of check.
- Strict mode costs battery and a permanent notification. That is Android's
  price for a real cadence, not a design choice.
- Quiet hours still suppress urgent pages unless you turn on the bypass. That
  default is wrong and is the next thing I want to fix properly, with a
  migration rather than a flipped constant.
- No CI. The test commands above are run by hand.
