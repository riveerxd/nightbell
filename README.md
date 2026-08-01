# Pulse

A premium, glassmorphic Android monitoring app. Point it at anything that
answers over HTTP — or at a single element on a rendered web page — and it will
watch it, chart it, and shout when it breaks.

<p align="center">
  <img src="artifacts/screenshots/10-dashboard.png" width="240" alt="Dashboard" />
  <img src="artifacts/screenshots/11-detail-failing.png" width="240" alt="Monitor detail" />
  <img src="artifacts/screenshots/14-settings-haptics.png" width="240" alt="Alert settings" />
</p>

---

## What it monitors

| Kind | What it does |
| --- | --- |
| **Status check** | Ping a URL, assert on the status code (exact / any 2xx / range / any). |
| **Request & response** | Full control: GET·POST·PUT·PATCH·DELETE·HEAD, custom headers, request body and content type, plus a response-body assertion. |
| **Page element** | Loads the real page in an embedded browser, and watches one DOM node you picked by tapping it. |

**Response-body assertions:** contains · does not contain · exactly equals ·
matches regex · JSON field equals · JSON field exists. JSON paths support
nesting and array indices (`data.items[0].state`).

**Element expectations:** element exists · element is gone · text equals ·
text contains · text unchanged (snapshot). You can also compare an attribute
(`href`, `value`, `data-state`, …) instead of the visible text.

## Alerting

Every monitor either inherits the global alert policy or overrides it:

- **Down** and **recovery** notifications, independently toggleable.
- **Sound**: silent · notification tone · alarm tone · ringtone.
- **Haptics**: six named patterns — Tick, Double pulse, Long buzz, Heartbeat,
  S·O·S, Escalating. Tapping a style plays it immediately so you can feel it
  before you trust it.
- **Escalation**: failure threshold (ignore blips), cooldown between alerts,
  optional repeat-while-down nagging.
- **Quiet hours** with midnight wrap-around, and an optional
  "still notify, but silently" bypass.
- Inline notification actions: **Re-check now** and **Mute 1h**.

Android freezes sound and vibration onto a notification channel at creation
time, so a single channel could never honour per-monitor choices. Pulse
materialises one channel per `(sound × vibration style × severity)` combination
on demand and routes each alert to the matching one. Channels are grouped so the
system settings screen stays readable.

## The element picker

1. Enter a URL and tap **Open live preview**.
2. The page loads in a real WebView. Browse and scroll normally.
3. With *Tap to select* on, tap the node you care about.
4. Injected JS derives a durable signature and streams it back over a JS bridge:
   `id` → stable data-attribute (`data-testid`, `aria-label`, …) → shortest
   unique CSS path → absolute XPath → text fingerprint.
5. Every later check re-resolves that signature **in the same order**, so a
   cosmetic markup change degrades instead of false-alarming.

## Architecture

```
domain/    Models, Assertions, AlertDecider, Validation   ← pure Kotlin, no Android
data/      PulseStore (DataStore+JSON), HttpChecker (OkHttp),
           ElementChecker (offscreen WebView), CheckEngine,
           AlertCenter, WorkManager scheduling, Pulse (service locator)
ui/        theme (colours, glass modifiers, motion), icons (hand-authored),
           components, dashboard, setup (+ element picker), detail, settings
```

The whole decision surface — status matching, body assertions, element
comparisons, and the entire alert escalation matrix — lives in `domain/` as pure
functions, which is why it can be exhaustively unit-tested without a device.

**Design notes**

- `Modifier.glass()` composes the house style: translucent pane, light-catching
  top edge, diagonal specular sweep, and an optional accent rim.
- **Colour is reserved for health.** Chrome is one brand blue; red, amber and
  green only ever mean down, degraded and up. `healthRim()` tints a card's edge
  for the states worth interrupting someone for and returns transparent for the
  rest — if every card is outlined, the broken one stops standing out.
- `softShadow()` replaces `Modifier.shadow`. Platform elevation shadows are
  rasterised by the GPU driver and degenerate into a hard dark rectangle behind
  translucent surfaces on software renderers. The drawn version renders
  identically everywhere. It stays black: tinting a drop shadow with the card's
  accent is what turns a dashboard into a wall of neon.
- `rememberLoopingFloat()` drives every looping animation and genuinely *stops*
  at reduced motion instead of speeding up — better for battery, and it lets the
  Compose frame clock go idle.
- **Entrances play once.** `StaggeredEntrance` records itself in a screen-scoped
  `EntranceLog`, because a `LazyColumn` discards an item's composition when it
  scrolls out of view — state kept inside the item resets, and the animation
  fires again on every pass.
- Confirmations are a **capsule sized to its text**, parked below the wordmark
  and carrying a genuine drop shadow. A full-width banner at the top edge covers
  the app's name and its "N systems operational" verdict, which is the one line
  people open Pulse to read.
- Icons are hand-authored `ImageVector`s on a 24-unit grid with 1.7px round
  strokes, so the whole app shares one optical weight and the APK carries only
  the glyphs it uses.

## Build

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # signed, see keystore/ below
./gradlew :app:testDebugUnitTest    # 68 JVM tests
./gradlew :app:connectedDebugAndroidTest   # 32 on-device tests
```

Release signing reads `keystore/keystore.properties`; when it is absent the
release build is simply unsigned. Regenerate with:

```bash
keytool -genkeypair -v -keystore keystore/pulse-release.jks -alias pulse \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass <pw> -keypass <pw> -dname "CN=Pulse Monitor, O=Bohemian Karst, C=CZ"
```

## Testing

| Suite | Count | Covers |
| --- | ---: | --- |
| `AssertionsTest` | 20 | status modes, all six body assertions, JSON paths, element modes |
| `AlertDeciderTest` | 14 | thresholds, cooldown, repeat, recovery, quiet-hour wrap, runtime folding |
| `HttpCheckerTest` | 14 | real sockets: 200/503/418, body + JSON assertions, POST echo, timeout, DNS, refused, redirects |
| `ValidationTest` | 16 | URL/header/assertion/cadence/element validation |
| `PersistenceTest` | 4 | snapshot round-trip, forward compatibility, vibration table integrity |
| `PulseE2ETest` | 8 | full UI journeys on-device |
| `ElementMonitorTest` | 10 | real WebView DOM: locate, fallbacks, picker capture |
| `AlertsInstrumentedTest` | 11 | real notifications, channels, escalation, mute, actions |
| `ScreenshotTest` | 3 | drives every screen and writes PNGs |

`TinyHttpServer` (in `src/testShared`) is a ~100-line dependency-free HTTP
server shared by both suites, so the checker is always tested against real
sockets rather than a mock.

## Known limitations

- **Background cadence is best-effort.** WorkManager one-shots self-reschedule
  per monitor and a 15-minute periodic sweep repairs dropped chains, but Doze
  and App Standby can delay sub-15-minute intervals. Manual and foreground
  checks are exact.
- **Element checks need a renderable page.** Heavy SPAs are polled for up to
  ~5 s after `onPageFinished`; pages that hydrate slower than that, or that hard-
  block headless/offscreen WebViews, may report "element not found".
- **No custom sound-file picker.** Sound choice is silent / notification /
  alarm / ringtone; per-channel fine-tuning is handed off to system settings.
- Local storage only — nothing leaves the device, and there is no sync or export
  UI yet (the store is a single JSON document, so both are easy to add).
