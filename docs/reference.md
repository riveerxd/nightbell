# Nightbell reference

The long version. See the [README](../README.md) for the short one.

A premium, glassmorphic Android monitoring app. Point it at anything that
answers over HTTP, or at a single element on a rendered web page, and it will
watch it, chart it, and shout when it breaks.

## What it monitors

| Kind | What it does |
| --- | --- |
| **Status check** | Ping a URL, assert on the status code (exact / any 2xx / range / any). |
| **Request & response** | Full control: GET·POST·PUT·PATCH·DELETE·HEAD, custom headers, request body and content type, plus a response-body assertion. |
| **Page element** | Loads the real page in an embedded browser and watches **any number** of DOM nodes you picked by tapping them, all resolved against one page load. |

**Response-body assertions:** contains · does not contain · exactly equals ·
matches regex · JSON field equals · JSON field exists. JSON paths support
nesting and array indices (`data.items[0].state`).

**Element expectations:** element exists · element is gone · text equals ·
text contains · text unchanged (snapshot). You can also compare an attribute
(`href`, `value`, `data-state`, …) instead of the visible text.

Each watched element carries its own expectation and an optional nickname. The
list is a **conjunction**, one mismatch marks the monitor down, and the alert
names the element that broke. Booting the WebView and waiting for hydration is
what an element check actually costs, so watching six nodes costs almost exactly
what watching one costs.

## Alerting

Every monitor either inherits the global alert policy or overrides it:

- **Down** and **recovery** notifications, independently toggleable.
- **Degraded** (latency-SLO) notifications on their own track, see below.
- **Sound**: silent · notification tone · alarm tone · ringtone.
- **Haptics**: six named patterns, Tick, Double pulse, Long buzz, Heartbeat,
  S·O·S, Escalating. Tapping a style plays it immediately so you can feel it
  before you trust it.
- **Escalation**: failure threshold (ignore blips), cooldown between alerts,
  optional repeat-while-down nagging.
- **Quiet hours** with midnight wrap-around, and an optional
  "still notify, but silently" bypass.
- Inline notification actions: **Re-check now**, **Mute 1h**, and
  **Acknowledge** on urgent alerts.

Android freezes sound and vibration onto a notification channel at creation
time, so a single channel could never honour per-monitor choices. Nightbell
materialises one channel per `(sound × vibration style × severity)` combination
on demand and routes each alert to the matching one. Channels are grouped so the
system settings screen stays readable.

### URGENT mode

A per-monitor switch for the things you cannot afford to sleep through.

While an URGENT monitor is down, Nightbell re-posts **one** notification on a
dedicated, DND-bypassing alarm channel every *N* minutes (default 5) until you
acknowledge it, from the notification action or from the monitor screen. It is
`ongoing`, so it cannot be swiped away.

Acknowledging stops the repeats **for that outage only**. The monitor stays red,
the ordinary down notification stays where it was, and recovery re-arms the
loop, so the *next* outage shouts again. Urgent overrides cooldown and the
repeat toggle, that is the point, but still honours the master switch, the
per-monitor alert switch, mute, the failure threshold and quiet hours. It means
"don't let me miss it", not "ignore everything I configured".

The whole state machine is `domain/UrgentAlerts.kt`: pure, and exhaustively
tested.

Because the urgent notification is `ongoing`, deliberately un-swipeable, its
lifecycle is treated as a **reconciliation, not a transition**. A healthy check
always issues a cancel rather than inferring one isn't needed, checks of one
monitor are serialised behind a per-monitor lock, and every tick sweeps
`getActiveNotifications()` against the ids monitors can currently justify. That
last one is the only way to catch a notification belonging to a monitor that has
since been deleted. See HANDOFF for the field bug that prompted all three.

### Spoken alerts

Off by default, and the switch is per monitor: **Say it out loud**, under a
monitor's alert settings, next to its sound and haptics. A monitor left on the
global alert settings follows the default policy's switch, so turning it on
under Settings, Alerts, Default alert policy covers the whole fleet in one go.
Settings, Alerts, Spoken alerts says how many monitors currently speak and has
"Turn on for all" and "Turn off for all" for the case where thirty monitors have
their own policies.

When it fires, the alert reads itself out: "Nightbell alert. Checkout API is
down. Host not found." The sentence is a template you can edit, and the four
placeholders sit under the field as buttons that add themselves: `{name}`,
`{reason}`, `{duration}` and `{others}`, filled in from the same facts the
notification carries. `{duration}` is left out of the default because an ordinary
alert fires the moment a check fails and would say "down for just now"; it earns
its place on an URGENT page.

A voice pronounces, it does not translate, and Nightbell's alerts are written in
English: there is one `values/strings.xml` and no translations. So an English
voice is the default even on a phone whose own language is something else,
because handing English words to another language's phoneme set produces
something that is neither language. The voice list is still offered, since
someone who writes their own sentence wants a voice to match it, and the card
says plainly that it changes pronunciation rather than language. To hear an alert
in another language, write the sentence yourself and leave out `{reason}`, which
is the one part Nightbell words. When the voice that will actually be used cannot
speak the language the sentence is in, including the case where the engine ships
only one language and there was never a choice, the card says so.

The voice is the phone's own text-to-speech engine, so nothing about a monitor
leaves the device. Only voices the engine reports as installed and usable
without a connection are offered: a synthesiser that fetches its voice over the
network would be silent during exactly the outage it was installed for. The card
also synthesises one word to a file before claiming the engine works, because an
engine can report an installed voice and then produce no audio at all, and
offers the system screen where voice data is installed when that happens.

An ordinary alert is spoken once when it goes down, and again on each repeat if
"Say it again on every repeat" is on. An URGENT page is spoken by the service
that owns the siren instead: once per page, so it inherits
`urgentRepeatMinutes`, with the siren muted for the sentence and restored
immediately after. Haptics keep going through it.

Everything that can silence an alert silences the announcement first, because
speech follows the notification's own verdict rather than re-deciding: the master
switch, the monitor's alert switch, mute, the failure threshold and quiet hours.
The ringer is checked on top of that, so a phone set to vibrate or silent never
speaks.

`domain/SpokenPage.kt` builds the sentence and resolves which policy applies, so
what gets said and the count Settings shows cannot disagree. It is pure, and
unit-tested: URLs are read as hostnames rather than spelled out with their
scheme, durations are words rather than `4m 12s`, and a failure message carrying
half a JSON body is cut at a word boundary. `data/alerts/PageSpeaker.kt` owns the
engine.

### Latency SLOs and DEGRADED

`Health.DEGRADED` now means something. Give a monitor a **latency budget** (or
inherit the global default, 2.5 s) and a successful response slower than it
becomes DEGRADED, amber, not red, because the service answered.

Degraded has its own alert track with its own cooldown, its own repeat setting
and its own recovery notification, deliberately independent of the down track:
"slow" and "broken" are different incidents, and a slow morning should not eat
the cooldown an outage needs. An outage always supersedes slowness, so you never
get two notifications for one event.

### The latency reference, and how often it is contacted

The baseline needs a control, so Nightbell times a known-good endpoint and
subtracts whatever the connection itself is adding. That endpoint is
`connectivitycheck.grapheneos.network/generate_204` by default, it is a free text
field in Settings, and none of it happens with **Discount my connection** off.

It is timed once per check *pass*, never once per check, and never twice inside
`CheckEngine.REFERENCE_MIN_INTERVAL_MS` (45 s). That constant is a floor, not a
cadence. What sets the real rate is how often a pass runs: with strict mode off
the only recurring pass is the 15-minute sweep, so four requests an hour is the
ceiling and Doze makes it fewer; with strict mode on the service loop wakes every
15 to 60 s, so the floor becomes the real limit at roughly one a minute. Pull to
refresh and "Check all now" run a pass too, which is the case the floor exists to
absorb.

If the endpoint stops answering, the gap doubles per consecutive failure up to six
doublings, so a network that blocks it settles at one wasted request every 48
minutes rather than one per pass. Absent readings are not an error: the baseline
maths treats them as "judge the latency raw".


### How background checks actually run

Three layers, and the app is explicit about what each one can promise:

| Layer | Cadence it can honour | Notes |
| --- | --- | --- |
| Per-monitor `PeriodicWorkRequest` | the monitor's interval, **floored at 15 min** | Android's floor, not a Nightbell setting |
| 15-minute repair sweep | anything overdue, at 15-min granularity | also re-arms missing periodic work |
| Strict foreground service | exactly as configured, down to ~15 s | costs a permanent notification and battery |

**Sub-15-minute intervals cannot be honoured in the background.**
`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is 15 minutes and WorkManager
clamps anything shorter, silently. Nightbell clamps it *visibly* instead: the
scheduler coerces to the floor, the sweep still picks the monitor up as overdue
on every wake, and the Settings → **Checker health** card says so in as many
words. Strict mode is the only way to get the interval you asked for, and that is
the honest answer rather than a promise the platform will not keep.

Nothing re-arms itself from inside its own execution, and every reconciliation
uses a policy that **cannot cancel work in flight**
(`ExistingPeriodicWorkPolicy.UPDATE` for cadence, `ExistingWorkPolicy.KEEP` for
"check now"). That is not a style preference, see the 1.6.0 section of HANDOFF
for what the previous `REPLACE`-everywhere design did to real users.

### Checker health, separately from monitor health

A check can fail to produce an answer for three very different reasons, and Nightbell
now keeps them apart:

| | What it means | What the user gets |
| --- | --- | --- |
| **Monitor failure** | the site is down, the selector is gone, the status is wrong | notification + vibration, per policy |
| **System-limited** | Doze, battery saver, no connectivity, background restricted | shown in Settings; **never** a notification |
| **Checker fault** | an exception escaped Nightbell's own checker code | its own quiet channel, only once verified |

The middle row is the one that was missing, and its absence is why cancelled
checks used to arrive as outages. A checker fault needs **three consecutive
internal errors with no completed check in between** before it says anything, is
held in memory only (so a restart cannot inherit a stale claim), and clears the
instant any check produces a verdict, passing *or* failing.

**Coroutine cancellation is not any of the three.** WorkManager replacing work, a
foreground service stopping and a screen going away all cancel checks constantly;
none of them is evidence about anything, and Nightbell records and says nothing.

### Strict foreground monitoring

WorkManager is the right default, battery-friendly, survives reboots, but Doze
can defer work for a long time and its periodic minimum is 15 minutes. A monitor
set to "check every minute" does not check every minute.

Turning on **strict foreground monitoring** runs a foreground service that keeps
your intervals exactly. It sleeps until the next monitor is actually due rather
than polling on a fixed tick, and WorkManager stays armed underneath as a repair
sweep, so a service the OS kills still gets checks eventually.

The tradeoffs, stated plainly in the UI as well as here:

| | Strict off (default) | Strict on |
| --- | --- | --- |
| Cadence | best-effort; Doze may delay | as configured, down to ~15 s |
| Battery | negligible | real, a wake per due check |
| Notification | none | permanent, un-dismissable (Android's rule) |
| Survives reboot | yes | yes |
| Survives OS kill | yes | restarted by `START_STICKY` + the sweep |

An unacknowledged URGENT outage starts the same service on its own regardless of
the setting, and stops it the moment you acknowledge or the monitor recovers.

The service declares `foregroundServiceType="specialUse"` rather than
`dataSync`, because `dataSync` is capped at six hours per day on API 34+, which
would silently break the one guarantee strict mode exists to make. Shipping this
through Google Play would need that subtype justified in review; sideloading is
unaffected.

## Home-screen widget

A worst-first list of monitors, configurable per instance:

- **Theme**: black · white · blue, each a solid rounded surface, because a
  widget has to stay legible on a white beach photo and a black AMOLED wallpaper
  alike, and translucency cannot promise that.
- **Density**: compact (dot · name · status) or detailed (adds host, latency and
  the failure message).
- **Header pieces, independently**: the mark, the word "Nightbell", the fleet
  summary ("1 of 6 is down") and the settings cog each switch off on their own.
  They used to be one flag, which meant the only way to drop the summary was to
  lose the branding with it.
- Show/hide the last-checked footer, and optionally hide healthy monitors
  entirely.
- **Monitors**: automatic, or capped at 1-10. Automatic lists as many as the
  size the widget was dragged to can hold, which is the setting a widget should
  have had from the start: the cap used to be applied before the layout was
  planned, so a widget with room for twelve rows drew five and counted the rest
  as "+7 more" with a third of its surface empty. Any overflow is still
  disclosed as "+N more" rather than silently truncated.
- **Columns**: automatic, or pinned to 1-3. See below.

Tapping the widget opens the dashboard; tapping a row deep-links to that
monitor's detail screen. Placing several widgets gives each its own
configuration, stored in a separate DataStore keyed by `appWidgetId` so a
corrupt widget preference can never take the monitor list down with it, and so
it survives an APK update. Widgets refresh after every completed check, plus the
platform's 30-minute periodic tick.

### Finding a placed widget's settings

Three routes, because the platform only reliably gives you one and it did not
exist below Android 12:

1. **The cog in the widget itself.** Works on every launcher and every API level;
   can be switched off per widget if you prefer the cleaner look.
2. **Long-press the widget** → its settings entry. This is
   `widgetFeatures="reconfigurable"`, added in API 31 and ignored below it.
3. **Settings → Home-screen widgets**, which lists every placed widget.

Reported as a real problem: once the widget was on the home screen its
configuration was unreachable.

### Columns

A widget's height is whatever the user dragged it to. Flatten one and the old
single-column layout stopped drawing monitors past the fold — they were still in
the list, still counted in "+3 more", and invisible. A short widget has spare
*width*, so monitors spill sideways into a second or third column instead.

`WidgetLayout` decides the shape from `OPTION_APPWIDGET_MIN_WIDTH` and
`OPTION_APPWIDGET_MAX_HEIGHT`, which the launcher reports through
`onAppWidgetOptionsChanged`. It is a pure function with no Android types in it,
because it is the arithmetic that decides whether a monitor is visible at all and
the only other way to exercise it is to drag a widget around a home screen by
hand, which is to say never. Twenty-seven unit tests cover the cell sizes a
phone launcher actually offers, including the 531x300 one this was reported
against.

Five rules earn their keep:

- **Width always caps the column count.** No number of monitors justifies a
  column too narrow to read a name in, so a forced count of 3 still collapses on
  a two-cell-wide widget. The floor is about 104dp a column, counted with the
  12dp gutters taken out: dividing the bare width by 104 said three columns fit a
  340dp widget, where three columns are 96dp each and every name ellipsised.
- **The cheapest shape that shows everything wins, and the footer is cheaper than
  a column.** One column is tried before two, and within a column count the
  timestamp is spent before another column is added: at 250 square that one row
  is the difference between six monitors and five with a "+1 more". A column
  costs width, and a narrow column costs the latency reading beside every name,
  so it is the more expensive of the two. The footer only goes when losing it
  means nothing is hidden: dropping it to show fourteen of twenty would take away
  the one line saying the list is incomplete, which is a worse widget than twelve
  and an honest "+8 more".
- **Below roughly 150dp a column, the trailing value is dropped.** Two columns in
  a four-cell widget leaves about 105dp each, and "DOWN" or "4100 ms" was eating
  enough of that to truncate "Marketing site" to "Market…". The dot already
  carries health and the number is one tap away, so the name wins.
- **A widget too short for both a footer and a monitor drops the footer.** A
  header, one row and a footer come to more than a four-by-two widget's 110dp.
  This is the same trade as the rule above, at the size where there is nothing
  left to trade: the footer goes whether or not its height buys a whole row,
  because the alternative is a monitor clipped down to a coloured dash.
- **The heights it divides by are measured, not read off the XML.** A compact row
  is 28.95dp, not the 27 that was assumed from its attributes, so a widget with
  room for six rows planned seven and drew the last one past the bottom edge.
  Each height is a line through the font scale, since every text size here is sp
  and someone reading at 200 per cent gets rows half again as tall in the same
  box. `WidgetInstrumentedTest` measures the real views against those lines at
  100, 130 and 200 per cent, and lays the whole widget out at four heights to
  check that nothing planned ends up drawn outside it.

`RemoteViews` has no flexbox and cannot set `LayoutParams`, so a column is its
own layout file carrying `layout_weight="1"`, added into a horizontal container,
with rows added into it before it is added to the parent. The gutter between
columns is `setViewPadding` on every column but the first, since padding is
remotable and margins are not.

Rows fill column-major, so the worst-first ordering still reads top to bottom in
the first column before continuing in the second.

### Colours and transparency

Three presets (black, white, blue) plus **Custom**: a background colour, a text
colour, and a background-opacity slider that goes all the way to zero, at which
point the widget is text on your wallpaper and nothing else.

The surface is a tintable `ImageView` behind the content, not a background
drawable on it, because `RemoteViews` cannot recolour a `View` background on
API 26–30. `setColorFilter` plus `setImageAlpha` give an arbitrary colour at an
arbitrary opacity while keeping the rounded corners a flat `setBackgroundColor`
would throw away. The hairline edge is its own layer and fades out with the
surface, so a fully transparent widget has no ring floating around it.

Presets deliberately ignore the opacity slider: they are defined surfaces with
known contrast, and letting a stray opacity value apply to them would quietly
turn a legible preset illegible. Picking a pale custom background moves the text
colour to something readable *unless* you have already chosen one yourself.

## The live strict-monitoring card

On Android 16 the strict-monitoring notice draws its own history. `ProgressStyle` —
the template rideshare and delivery apps use — gives a horizontal line of coloured
segments with milestone points and a tracker riding along it, and it is the only
template the platform will promote to a status-bar chip and expand on the lock
screen. `LiveTimeline` turns the check history into that shape; `LiveCard` hands it
to the notification.

Reading the line:

| What you see | What it is |
| --- | --- |
| Green / red stretch | a **band** — one run of an outcome, as wide as it actually lasted |
| Taller red block | a **marker** — where an outage *began* |
| Grey tail past the dot | the wait until the next check |

The marker is not redundant with the band it sits on. The shortest possible outage
is one bucket, a couple of per cent of the width, which on screen reads as a
rendering artefact — the block is what makes a single failed check findable, and the
band is what makes a long one measurable.

### Three things it used to get wrong

All three were reported from a device and none was visible from reading the code.

**The window was measured from checks it could not draw.** The span came from the
oldest retained sample of any age and was then clamped to 24 hours, so one straggler
older than a day stretched the line to a full day and was immediately skipped for
falling before the window start. Every bucket between the left edge and the first
drawable check stayed unknown, and carrying forward cannot rescue them — it
propagates left to right and there is no earlier bucket to inherit from. Half a bar
of grey under a label claiming a day of history. Samples older than the ceiling are
now dropped *before* the span is measured, so the label describes the line drawn.

**Compression invented outages.** Capping the band count used to absorb the shortest
non-outage band into a neighbour — and the neighbours of an up-band are outages by
construction, since same-tone runs are already fused, so the absorbed uptime could
only ever be handed to an outage, and the adjacency pass then merged the two red runs
into one. Measured on a monitor that alternated pass/fail every half hour for a day:
24 buckets genuinely failed, the line drew 40, and the longest drawn run was 33 — a
claimed sixteen and a half hours of continuous downtime that never happened. The old
guarantee that outages are never merged *away* was true and beside the point.
Compression now reports each group's real composition, so the drawn red total matches
the real one exactly, at the cost of approximating order within a group.

**A live outage could be painted over.** Carry-forward ran on the fleet-merged tone,
so a monitor checking every five minutes filled every bucket after an hourly
monitor's failure with green — the line drew green to the right edge under a red
tracker and a "1 DOWN" chip. Each monitor now carries its own last verdict before the
fleet merge.

### The countdown on the line

The tail's width says *how much* of the wait is left; the label at the end of the line
says how much in words — "15m", "4m", "now". Both come from the same `nextCheckInMs`,
so they cannot disagree.

`ProgressStyle` carries no text: segments and points take a colour and nothing else. It
does take an icon at each end, and an icon is a bitmap, so the label is drawn into one.
Three things about that slot were learned the hard way, on a device:

- **It centre-crops to a square.** A bitmap sized to its text is trimmed from both ends —
  "1h20m" rendered as "h20", "now" as "how". The canvas is square and the text is fitted
  into it, so a short label renders larger than a long one.
- **It renders full colour**, not the alpha mask a status-bar small icon gets. Proven by
  posting red/green/blue stripes into it and getting three stripes back.
- **Glyphs must be filled, not knocked out.** The first version punched the text out of a
  pill, which put the card behind it — a colour this code neither chooses nor can
  measure — on one side of the contrast ratio. It measured 2.5:1 and was reported from a
  device, accurately, as unreadable.

There is no container behind the label now, so the ink follows `uiMode` exactly as the
tones do: dark slate on the light shade, light grey on the dark one. One limit worth
knowing: a *colourised* card is pinned to `nightbell_ink` whatever the system theme is, so on
a light-themed phone whose card wins promotion the ink resolves the wrong way. The same
inversion already affects the segment colours, and fixing it means deciding colourisation
before the style is built rather than after.

### The tail is a gauge, not a measurement

It is the only part of the drawing not to scale, deliberately. To scale it cannot
move: a bucket is `spanMs / 48`, so on the fifteen-odd hours a real fleet accumulates
a bucket is nearly nineteen minutes and a fifteen-minute countdown floors to the same
value full and empty alike. It spends a fixed sixth of the bar and empties across it
instead.

It is also paced by **one** monitor — the fastest, ties broken by id — rather than by
whichever is due soonest. "Soonest across the fleet" is the literal next check and is
useless as a gauge: eight monitors on a fifteen-minute interval are staggered, so one
is always nearly due, the value sits near its floor and resets every couple of
minutes. Reported as the tail "not moving at all"; it was moving, in a fast erratic
sawtooth indistinguishable from stuck.

## The launcher icon

The mark is a bell with a heartbeat trace knocked out of it, and it is the brand
blue. The trace is the same six points that *were* the whole mark from 2.4.3 to
2.5.0, scaled to 0.42 and centred inside the bell rather than redrawn, so 3.0.0's
rename kept the old drawing instead of discarding it. A bell says the name; the
trace says what the app does.

Blue and not red or green, for a reason the palette cares about. The trace used to
be red, the one element that meant failure, and a logo drawn as one red line reads
as permanently broken. Red still means failure everywhere the *data* lives (charts,
history strip, status orbs), and green still means working there. The mark stays out
of that vocabulary entirely.

The cutout is a real hole rather than a dark line painted over the bell, which
matters in the two places the system tints the icon flat from its alpha channel: the
themed launcher icon and the status-bar glyph. A painted-on hole would vanish there
and leave a solid bell. `docs/brand/android_assets.py` computes the hole as a
subpath and fills with `evenOdd`, because a vector drawable has no `<mask>`.

All six drawables carry the trace. 3.0.0 and 3.0.1 shipped the widget header and
the status-bar glyph solid, on the claim that the slot was "under two pixels" at
that size. That was the dp figure read as pixels: the widget header is drawn at
18dp and its slot is 1.01dp, which is 2px at xhdpi, 3px at xxhdpi and 4px at
xxxhdpi. The two 24dp canvases widen it by a third anyway, because the hole has to
survive a 1x density, and a slot that closes up is worse than one slightly heavier
than the master.

## The cold-start animation

`ui/NightbellSplash.kt`. The bell arrives, rings, and the word wipes in beside it,
over 2.8 s, on cold start only.

It is one `Animatable` from 0 to 1 with each beat reading its own window out of it,
so the beats cannot drift apart and re-timing the sequence is editing a table
rather than chasing delays through nested coroutines. The mark it draws is
`NightbellMark`, the same composable the dashboard header uses, so the splash
cannot drift from the icon it is introducing.

Three things it has to get right and one it deliberately does not do:

- **The platform draws its own splash first.** From Android 12 every cold start
  gets one whether the app asks or not, and the default puts the launcher icon on
  a white circle. `values-v31/themes.xml` sets the splash ground to the void and
  the icon to the mark with no plate, so the system's screen is the first frame of
  ours instead of a competing one.
- **The bell starts where the platform left it.** The system centres the icon; this
  row centres bell *and* word, so handing over would slide the bell left by half
  the word. The row starts shifted right by half the measured word block and drifts
  into place as the word arrives.
- **The last 0.7 s is the finished lockup sitting still.** That hold is the point,
  not padding: an opening that dissolves the instant it resolves reads as a glitch.
- **It does not animate the cut.** An earlier version carved the trace into the
  bell here. It looked good alone and was wrong in sequence, because the system
  splash already shows the finished icon, so the bell arrived whole, lost its
  heartbeat and grew it back.

The whole thing is skipped when `rememberSystemAnimationsEnabled()` is false. A
splash is decoration, and that setting is asking us not to play decoration.

It ships as a **legacy** icon rather than an adaptive one. That is deliberate, and the reason is worth writing down because it cost two
rounds of chasing the wrong thing.

The mark was originally drawn on an opaque ink plate. Asked to make the
background transparent, the obvious move is an `<adaptive-icon>` whose
`<background>` is `@android:color/transparent`. It renders solid black.
`AdaptiveIconDrawable.draw()` fills its layer bitmap with `Color.BLACK` before it
composites the background and foreground, because the format assumes the
background is opaque. So the black is not a launcher bug and not a stale icon
cache — it reproduces in Settings' app-info screen, and survives clearing the
launcher's data.

What Android actually guarantees is that an app icon gets a *filled shape*:

| Icon format | Result |
| --- | --- |
| Adaptive with a transparent background | the framework fills it black |
| Legacy with transparency | most launchers wrap it in a light plate |

There is no transparent app icon on Android. Legacy is nonetheless the closer of
the two: it is genuinely transparent wherever a launcher does not apply its
legacy wrapping, and where one does the result is a clean light plate rather than
a black one. The cost is themed ("monochrome") icons, which only the adaptive
format carries — `ic_launcher_foreground` and `ic_launcher_monochrome` are kept,
unused, for whenever a plate is wanted back.

`LauncherIconInstrumentedTest` pins the things that are actually in the app's
control: that the manifest does not point at an adaptive icon, and that the
drawable it does point at has transparent corners, draws its trace in the brand
blue, and no longer paints the ring across the top. All of it was invisible to
review — a dark icon on a dark background looks the same either way.

### One geometry, five copies

The mark exists as the launcher icon, the widget header mark, a notification
silhouette, an adaptive foreground and a Compose composable, each needing
different stroke weights on a different canvas. They are generated from the
brand drawing by `docs/brand/android_assets.py` rather than scaled by hand,
because the first hand-scaled set shipped a legacy icon whose trace vertices were
simply wrong and a Compose mark that did not match the vectors. Run it after
changing any geometry:

```bash
python3 docs/brand/android_assets.py
```

As of 2.4.3 each copy is the single trace path — the ring direction 30 drew around
it is gone — fitted to its canvas and vertically centred. The generator computes
that fit from the trace's own bounding box, so `size` still means the drawn mark's
extent and the copies cannot drift apart.

## Moving between installs

**Settings → Backup and transfer** writes every monitor, its sample history and
your settings to a JSON file, through the Storage Access Framework, so Nightbell
needs no storage permission, the destination is yours to pick, and nothing is
uploaded anywhere.

It also exists because **2.0.0 changed the app's `applicationId` to
`me.river.nightbell`, and that is not a rename.** Android identifies an app by that
id, so 2.0.0 installs *beside* an earlier version rather than updating it: new
data directory, no monitors, and no way for it to read the older install's files.
No signing key or manifest setting changes that. Export from the old install,
install 2.0.0, import, and export **before** uninstalling anything, because once
the old app is gone so is its data.

Placed home-screen widgets do not come across either: a launcher stores the
provider as a fully-qualified class name, so they belong to the old app. Drag them
back on after importing; their settings are in the backup.

An import **replaces** what is on the device rather than merging, and says so
before it does it. What it carries is monitors, settings, mute windows and
history. What it deliberately does not carry is any record of notifications
already posted: health resets to unknown until this install has actually checked
something, and the alert bookkeeping resets with it. Importing an in-progress
alert state would suppress the first real outage on the new device, see the
1.7.0 and 2.0.0 sections of HANDOFF for why that is the same trap twice.

## Being told about a new Nightbell

**Settings → About → Nightbell updates** switches the check on, picks the source
and offers **Check now**. When a newer version exists the app says so twice: a
notification, once per version, and a modal banner on the dashboard that keeps
saying it until the user answers.

**The notification opens the app, not a browser**, whenever the release has an
APK behind it. Both GitHub and F-Droid publish one, so the route turns on whether
there is a file rather than on which source published it. The tap lands on the
dashboard where the banner offers **Install**, and it clears any "Remind later"
deferral on the way, because that deferral quietens the surfaces that appear on
their own and a deliberate tap is not one of those. A release with no APK is the
one case where a page is the only route, and there the notification still opens
it.

Nothing is downloaded until Install is pressed, and Android asks again before it
replaces the app. Where Android has not been told to allow installs, the button
reads **Settings** and says what it will do, rather than being an Install button
that opens a settings screen.

## The diagnostic log

**Settings → About → Diagnostic log** records what Nightbell is doing to a text
file the user can export and hand to somebody. It exists because a report used
to arrive as a sentence and get answered with a guess: `adb logcat` is the only
place the app's own account of itself lived, and a phone is not a machine that
can read it.

The switch governs the **file** and nothing else. Three sinks, and only one of
them is optional:

| Sink | When | Where |
| --- | --- | --- |
| logcat | always | `adb logcat -s Nightbell`, unchanged from before this feature |
| a ring of 500 lines | always | memory only, never disk |
| the file | while the switch is on | `filesDir/diagnostics/`, capped at 192 KB with one rotation |

A crash is the exception and is recorded whichever way the switch is set, to a
file of its own, carrying the stack trace plus the ring. A crash cannot be
reproduced on request, so a switch that has to be on beforehand would record
nothing the first time, which is what issue 2 arrived with.

The switch is off on a fresh install and does not travel in a backup: it is a
decision about a device, not about a fleet.

### What is in a line

Lines are for grepping, not for reading as prose:

```
18:36:25.896 I HTTP  http.request monitor=7f3a1c2e url=https://checkout.example.com/*2?*1 headers=1
18:36:25.902 W PAGE  page.expired stage=LOADING percent=43 ready=interactive load_event=false requests=87
```

Time is local. Level is at a fixed column, which is what the in-app viewer
colours by. The area names one of the surfaces the app has actually been
reported broken on: `SCHED`, `CHECK`, `HTTP`, `PAGE`, `ALERT`, `STORE`, `NET`,
`APP`, `WIDGET`, `UPDATE`. After the event code, everything is `key=value`.

An exported file opens with a block of facts about the device: version and
whether the build was minified, API level, model, **the WebView package and
version**, whether battery optimisation is on, which alert permissions are
granted, the network state, and the fleet as counts. Those are the fields issue
threads kept having to ask for one at a time.

### What is left out, and how that is enforced

The file is going to end up in a public issue thread, so the danger is not
somebody reading the device. It is the owner publishing their own credentials
because of a line they never read.

Censoring is therefore an allowlist rather than a filter. There is no logging
call that takes a string: `Diag.log` takes an event whose text is a constant and
a list of `LogField`s, and every factory on `LogField` states what class of
value it will accept. `Log.i(TAG, "checking $url")` is not a call this app can
make any more, because no overload accepts it.

- **Addresses** keep scheme, host and port. The path becomes a segment count and
  the query a parameter count, so a link with a key in it cannot reach the file.
- **Credentials** are never content. A token, a session cookie or a stored
  localStorage blob renders as `[length:hash6]`, which is enough to say "the same
  one as last time" and useless to replay.
- **Monitor names** have no factory at all, and are also fed through a second
  pass over every finished line, so a name that got into a line some other way
  is fingerprinted anyway. Monitors are identified by the first eight characters
  of their id.
- **Request headers, request bodies, page content, element selectors and
  response snippets** are not written. A count or a length is what a line may
  say about them.
- **Free text this app did not author**, which is exception messages and browser
  console output, goes through a pattern sweep for bearer tokens, basic auth,
  GitHub token prefixes, JWTs, named header values, named query parameters, URL
  userinfo, email addresses and anything else long enough to be an identifier.

There is no way to turn any of it off, in any build. A flag for "log the real
values" is a flag that reaches a release by accident.

`LogSentinelTest` is what keeps this true. It fills every string a monitor, the
settings and a verdict can hold with a unique sentinel, points every factory at
them, and fails if any survives. It also reflects over those models and fails
when one grows a string field that has not been classified, so deciding whether
a new field may be published happens when the field is added rather than the
first time somebody logs it.

### Reading it before sending it

**Read log** opens the file in a dialog over the page, and that is not a
nicety. Somebody about to paste this into a public thread should be looking at
the thing they are about to paste, so the viewer reads the file rather than an
approximation of it, and warnings and errors are coloured. It is a dialog rather
than an expanding panel in the card because a thousand monospace lines make the
About tab enormous, and because a panel inside the settings list scrolls with
the list, so there is no way to move through the log without also moving the
page. The dialog gets its own scroll and is capped at half the window height, so
the same screen works on a phone and on a 480 dp head unit.

Lines run oldest first, like the file, and it opens already scrolled to the
newest one. **Delete the log** removes both generations and the crash file, and
leaves one line saying so, because a file that silently begins in the middle
cannot be told from one that only just started.

With the switch off the viewer shows nothing, even though the in-memory ring is
still filling for the crash handler's benefit. Showing the ring would contradict
the switch two rows above it.

**Export is blocked while there is nothing to hand anybody**, and the switch's
own subtitle two rows above it says why, so the reason a blocked button is
blocked is on screen permanently rather than behind a tap. A blocked button still
announces as unavailable rather than reading as a caption; see `NightbellButton`.
Deleting is held rather than confirmed, like every other control in the app that
takes something away.

Export goes through the Storage Access Framework, like the backup export, so
there is no storage permission, no `FileProvider`, no exported component, and the
destination is the user's to pick.

## The element picker

1. Enter a URL and tap **Open live preview**.
2. The page loads in a real WebView. Browse and scroll normally.
3. With *Tap to select* off, the page is live: links work, and so does the
   button on a cookie banner or an age gate. Turn it back on when the view you
   want is on screen.
4. With *Tap to select* on, tap the node you care about.
5. Injected JS derives a durable signature and streams it back over a JS bridge:
   `id` → stable data-attribute (`data-testid`, `aria-label`, …) → shortest
   unique CSS path → absolute XPath → text fingerprint.
6. Every later check re-resolves that signature **in the same order**, so a
   cosmetic markup change degrades instead of false-alarming.

**The pick carries its page with it.** The toolbar shows the address the preview
is actually on, not the one that was typed, and confirming a pick made somewhere
else points the monitor at that page. The bottom bar says so before the button
is pressed, and the button reads *Watch this page* rather than *Use this
element*. A monitor still watches one page: every element it holds is resolved
against that single load.

**Pages behind a gate.** Some sites show nothing until you have pressed
something. When the pick is confirmed, the cookies and `localStorage` the
preview ended up holding are stored with the monitor and put back before each
check, so the check sees what the person who chose the element saw. The captured
session is treated as a credential: it is never logged, never shown, and never
written into an export unless secrets were asked for in the same breath. Setup
says the monitor is carrying one. When a check finds nothing and something
gate-shaped is standing over the page, the failure names the button it can see
rather than reporting a missing element.

## Architecture

```
domain/    Models, Assertions, AlertDecider, UrgentAlerts, Summary,
           Validation                                     ← pure Kotlin, no Android
data/      NightbellStore (DataStore+JSON, forward migration), HttpChecker (OkHttp),
           ElementChecker (offscreen WebView, N targets per load), CheckEngine,
           AlertCenter, WorkManager scheduling, NightbellMonitorService,
           Nightbell (service locator)
ui/        theme (colours, glass modifiers, motion, backdrop blur),
           icons (hand-authored), components, dashboard,
           setup (+ element picker), detail, settings
widget/    RemoteViews provider, per-instance config store, config activity
```

The whole decision surface, status matching, body assertions, element
comparisons, and the entire alert escalation matrix, lives in `domain/` as pure
functions, which is why it can be exhaustively unit-tested without a device.

**Design notes**

- `Modifier.glass()` composes the house style: translucent pane, light-catching
  top edge, diagonal specular sweep, and an optional accent rim.
- **Colour is reserved for health.** Chrome is one brand blue; red, amber and
  green only ever mean down, degraded and up. `healthRim()` tints a card's edge
  for the states worth interrupting someone for and returns transparent for the
  rest, if every card is outlined, the broken one stops standing out.
- `softShadow()` replaces `Modifier.shadow`. Platform elevation shadows are
  rasterised by the GPU driver and degenerate into a hard dark rectangle behind
  translucent surfaces on software renderers. The drawn version renders
  identically everywhere. It stays black: tinting a drop shadow with the card's
  accent is what turns a dashboard into a wall of neon.
- `rememberLoopingFloat()` drives every looping animation and genuinely *stops*
  at reduced motion instead of speeding up, better for battery, and it lets the
  Compose frame clock go idle.
- **Entrances play once.** `StaggeredEntrance` records itself in a screen-scoped
  `EntranceLog`, because a `LazyColumn` discards an item's composition when it
  scrolls out of view, state kept inside the item resets, and the animation
  fires again on every pass.
- Confirmations are a **capsule sized to its text**, parked below the wordmark
  and carrying a genuine drop shadow. A full-width banner at the top edge covers
  the app's name and its "N systems operational" verdict, which is the one line
  people open Nightbell to read.
- Icons are hand-authored `ImageVector`s on a 24-unit grid with 1.7px round
  strokes, so the whole app shares one optical weight and the APK carries only
  the glyphs it uses.
- **Muted ≠ broken.** A snoozed monitor's rim goes amber and it gains a "Muted"
  pill; red stays reserved for "this needs you now". A wall of red cards that
  you have already triaged teaches you to ignore red.
- **Pull-to-refresh is a two-stage gesture.** Re-checking every monitor fires
  real requests at every endpoint at once, so it should not be reachable by an
  accidental over-scroll. The pull is rubber-banded through
  `maxPull · (1 − e^(−raw/maxPull))` and every visual, puck scale, ring sweep,
  glyph rotation, label, is a pure function of that one distance, so the
  indicator is welded to the finger. Past the threshold you must *hold* for two
  seconds while a ring closes; releasing early springs back and cancels.
- **Real backdrop blur on API 31+** (`ui/theme/Backdrop.kt`). The scrolling
  subtree records itself into a `GraphicsLayer`; a floating sheet draws that
  layer, translated and clipped to its own bounds, so it shows a genuinely
  blurred copy of the pixels underneath. Two layers, not one, a layer's
  `renderEffect` applies every time it is drawn, so a single blurred layer would
  put the *screen* out of focus too. Sinks are invalidated by the source via
  `DrawModifierNode.invalidateDraw()`, because a node that draws a layer
  somebody else records reads no state and would otherwise freeze mid-scroll.
  Below API 31, or with the setting off, it degrades to the opaque pane the app
  shipped with. Toasts stay fully opaque either way.

## Build

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # minified + resource-shrunk, signed
./gradlew :app:assembleReleaseTest  # minified but debug-signed, for smoke-testing R8
./gradlew :app:testDebugUnitTest    # 118 JVM tests
./gradlew :app:connectedDebugAndroidTest   # 57 on-device tests
```

**Release is minified.** R8 plus resource shrinking takes the APK from 9.55 MB
to **1.96 MB** (−79%). `proguard-rules.pro` documents every keep and why R8
cannot see the reference itself: the WebView JS bridge (called from JavaScript
by method name), kotlinx.serialization's generated serializers, WorkManager
workers (instantiated by class name from a database that survives app updates),
and the widget provider (referenced by an `AppWidgetProviderInfo` the *launcher*
persists outside our APK).

The `releaseTest` variant exists so the R8 configuration can be exercised on a
device without replacing the installed release build. Everything reflective was
verified against it: monitors round-tripped through a process kill, WorkManager
jobs scheduled, the JS bridge returned a picked selector, the foreground service
entered `specialUse`, and the notification actions resolved.

**Baseline profile.** `app/src/main/baselineProfiles/baseline-prof.txt` is
hand-authored and covers the cold-launch path plus the four flows in the brief.
AGP merges it into `assets/dexopt/baseline.prof` and rewrites it through the R8
mapping, so the source names in the file are correct even though the shipped
classes are obfuscated. See HANDOFF for why it is not macrobenchmark-generated.

Release signing reads `keystore/keystore.properties`; when it is absent the
release build is simply unsigned. Regenerate with:

```bash
keytool -genkeypair -v -keystore keystore/nightbell-release.jks -alias nightbell \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass <pw> -keypass <pw> -dname "CN=Nightbell, OU=river, O=river, L=Prague, C=CZ"
```

## Testing

**242 JVM + 67 on-device = 309 automated tests.**

| Suite | Count | Covers |
| --- | ---: | --- |
| `AssertionsTest` | 20 | status modes, all six body assertions, JSON paths, element modes |
| `AlertDeciderTest` | 14 | thresholds, cooldown, repeat, recovery, quiet-hour wrap, runtime folding |
| `HttpCheckerTest` | 14 | real sockets: 200/503/418, body + JSON assertions, POST echo, timeout, DNS, refused, redirects |
| `ValidationTest` | 16 | URL/header/assertion/cadence/element validation |
| `PersistenceTest` | 4 | snapshot round-trip, forward compatibility, vibration table integrity |
| `UrgentAlertsTest` | 13 | the urgent state machine: start, repeat gap, acknowledge, recovery re-arm, suppression |
| `DegradedAlertTest` | 12 | latency threshold, independent cooldown/repeat/recovery, quiet hours, down-track regression guard |
| `CheckerHealthTest` | 25 | the checker-health machine: cancellation raises nothing, the three-error bar, streak windows, clearing, expiry |
| `CancellationIsNotAFailureTest` | 8 | real sockets: a cancelled check throws instead of returning a verdict, and real IO failures are still classified |
| `CheckerLimitsTest` | 12 | why checks are late, and the precedence between offline / metered / restricted / saver / Doze |
| `LegacyCrashRepairTest` | 10 | scrubbing 1.5.0's fabricated crash state without touching a genuine outage |
| `DueCheckTest` | 7 | the due-ness rule, including a wall clock that jumps backwards |
| `WidgetPaletteTest` | 12 | preset and custom widget colours, opacity clamping, fully-transparent surfaces |
| `BackupTest` | 15 | the transfer format, its refusals, and what an import must not carry over |
| `NetworkBaselineTest` | 19 | the latency-reference maths and its four trust states |
| `MultiElementTest` | 13 | target list, 1.0.0 migration, per-element validation, SLO/urgent validation |
| `SummaryTest` | 10 | worst-first ranking shared by dashboard, widget and service |
| `NightbellE2ETest` | 8 | full UI journeys on-device |
| `ElementMonitorTest` | 10 | real WebView DOM: locate, fallbacks, picker capture |
| `GatedElementInstrumentedTest` | 5 | issue #8 driven through the real UI: press through a gate, follow a link, pick, and have the check agree; plus the same check from a wiped browser store, a `localStorage`-only wall, the gate-shaped failure message, and a session refusing another origin |
| `GatedPageTest` | 14 | which page a pick belongs to, which origin a captured session may be replayed at, and what an export leaves behind |
| `AlertsInstrumentedTest` | 11 | real notifications, channels, escalation, mute, actions |
| `UrgentModeInstrumentedTest` | 8 | urgent end-to-end through the real engine + notification action |
| `CheckerCancellationInstrumentedTest` | 5 | the reported bug through the real graph: a cancelled check, and a cancelled six-monitor pass, must announce nothing |
| `WidgetInstrumentedTest` | 34 | RemoteViews **inflation**, ordering, every theme/density, custom colours at three opacities, the settings cog, config persistence and id remapping, the planner's heights against the views that get drawn |
| `WidgetConfigUiTest` | 2 | the widget's own settings screen, driven: the monitor count on Auto, pinned to a number, and back |
| `ScreenshotTest` | 3 | drives every screen and writes PNGs |

`WidgetInstrumentedTest` calls `RemoteViews.apply()` rather than asserting on
the builder, because that runs the same inflation the launcher's process runs, an unsupported view, a method that isn't `@RemotableViewMethod`, or a missing
resource fails there instead of showing "Problem loading widget" on a home
screen.

`TinyHttpServer` (in `src/testShared`) is a ~100-line dependency-free HTTP
server shared by both suites, so the checker is always tested against real
sockets rather than a mock.

## Updating from 1.0.0

`applicationId`, the signing key and the DataStore key (`snapshot_v1`) are
unchanged, so 1.1.0 installs over 1.0.0 and keeps every monitor, its history and
its settings. Every new field has a default and unknown keys are ignored, so the
only real migration is multi-element monitors: `NightbellStore.migrate` lifts 1.0.0's
single `element` into the new `elements` list, and keeps `element` pointing at
the head of that list so a downgrade still finds a target.

Verified on a device, not just asserted: two monitors (one of them a
single-element page monitor) were created in a real 1.0.0 build, 1.1.0 was
installed over it without uninstalling, and both came back with their history
intact and the element monitor re-resolving through the new code path.

## Known limitations

- **Sub-15-minute background intervals are impossible, not merely unreliable.**
  `PeriodicWorkRequest`'s minimum period is 15 minutes and there is no supported
  way around it. Nightbell clamps to the floor, lets the 15-minute repair sweep pick
  up anything overdue, and says so in Settings → Checker health. Manual and
  foreground-service checks are exact; strict mode makes the background ones exact
  too, at the cost of a permanent notification and real battery.
- **Doze and App Standby can delay even a 15-minute interval.** Exempting Nightbell
  from battery optimisation (offered in Settings → Checker health) helps and is
  not a guarantee. Only the foreground service is. Delay is reported in the UI and
  is never notified about, it is not an outage.
- **No Wear OS tile yet.** The shared roll-up it needs (`domain/Summary.kt`)
  exists and is tested; the module itself is blocked on tooling, see HANDOFF.
- **Widget rows are capped, not scrollable.** RemoteViews collections would buy
  scrolling at the cost of a second process hop and a class of stale-data bugs;
  a short worst-first list is what is actually glanceable. Overflow is disclosed.
- **Real blur is API 31+.** Below that, and when the setting is off, floating
  sheets are opaque. Nothing becomes see-through in either mode.
- **Element checks need a renderable page.** Heavy SPAs are polled for up to
  ~5 s after `onPageFinished`; pages that hydrate slower than that, or that hard-
  block headless/offscreen WebViews, may report "element not found".
- **A captured browser session expires when the site says it does.** Nightbell
  replays what the preview was holding, it does not renew it. The age gate on
  the site in issue #8 lasts fourteen days, after which the check reports the
  gate and the fix is to open the preview and press through it again. Nothing
  is replayed on the user's behalf: a recorded click that re-answered a consent
  dialog every interval was considered and refused.
- **No custom sound-file picker.** Sound choice is silent / notification /
  alarm / ringtone; per-channel fine-tuning is handed off to system settings.
- Local storage only, nothing leaves the device, and there is no sync or export
  UI yet (the store is a single JSON document, so both are easy to add).
