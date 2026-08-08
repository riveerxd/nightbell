# Nightbell — handoff

## 2.0.0 — the app id is `me.river.pulse`

**2.0.0 does not update an earlier install. It installs beside it.**

`applicationId` *is* the app as far as Android is concerned. It names
`/data/data/<id>/`, and it is what the installer matches to decide "update this"
against "install a new thing". A build carrying a different one is a different
app: it lands alongside whatever is already there, with an empty data directory,
and the older install keeps running until it is removed by hand. The signing key
does not change this — a key decides whether an update is *permitted*, never
whether two APKs are the same app. Neither does any manifest or Gradle setting;
there is no supported one.

One app also cannot read another's files, so nothing automatic can carry a fleet
across. The route is the one 1.7.0 added and the reason it was shipped first:
**Settings → Backup and transfer**, export from the old install, import into this
one. Anyone who removes the old install without exporting has lost their
monitors, and no code here can get them back.

Everything else in the tree moved with the id — `namespace`, all four Kotlin
source roots, the broadcast action strings in the manifest, the R8 keep rules and
the baseline profile's class descriptors. Notification channel ids, the DataStore
name (`pulse_store`), its key (`snapshot_v1`) and the WorkManager unique names are
hardcoded strings and were **not** derived from the package, so an import lands in
a store that is byte-identical in shape to the one it was exported from.

### Things worth knowing

- **Placed home-screen widgets do not survive.** A launcher stores the provider as
  a fully-qualified `ComponentName`, so the widget belongs to the old app and stays
  with it. Widgets have to be dragged back onto the home screen after importing;
  their saved per-widget config is in the backup and is re-applied.
- **The archived APKs under `artifacts/` from before 2.0.0 carry the retired id
  compiled in.** They are zip archives, not text, and no history rewrite reaches
  inside them. Treat them as what they are: the binaries that were actually built
  and installed at the time.

## 1.7.0 — portable backups

Everything the app holds, as one JSON file the user chooses the destination for.

Shipped ahead of 2.0.0 on purpose, and the reason is in that section: 2.0.0
changes the `applicationId`, so it cannot update an existing install, and this is
the only way anything gets carried over. It is useful on its own afterwards —
moving to a new phone, keeping a copy before a risky edit — but that is not why it
went out when it did.

### The format

`data/transfer/NightbellBackup.kt`. The envelope is thin on purpose — it is the
store's own `NightbellSnapshot` plus provenance (`app`, `versionName`, `versionCode`,
`exportedAt`, `monitorCount`) and a `format` integer. Reusing the snapshot rather
than defining a schema means the export cannot drift away from what the app
actually stores, and `ignoreUnknownKeys` plus a default on everything added since
1.0.0 means a file written here imports into a later build for free.

`format` is refused when it is *higher* than this build knows, rather than
best-efforted. A version field whose only behaviour is to be ignored is not a
version field.

### What an import deliberately does not carry

Monitors, settings, mute windows and the sample history come across verbatim.
Everything that is bookkeeping about *notifications already posted* does not,
because none of it is true on the new install — the shade is empty and nothing
has been announced or acknowledged there.

This is the same pair of traps `LegacyCrashRepair` exists to undo, arriving by a
different door:

- the down track is transition-driven, so an imported `alerting = true` means the
  first genuine outage on the new install is **never announced**;
- `urgentAcknowledged` silences the urgent track until a *successful* check,
  which never arrives while a site is actually down.

Health resets to `UNKNOWN` (or `PAUSED` for a disabled monitor) rather than to
whatever the old device last saw, and `lastCheckedAt` goes with it so every
monitor reads as due immediately and the import is followed by a real pass. The
last-check verdict fields are cleared to match — with health `UNKNOWN` they would
be a message with nothing behind it. Element baselines are cleared so the first
check here establishes what the element says rather than comparing against
another install.

Two things are dropped outright: `checkerStreak`, which is evidence about a
checker process that no longer exists, and the latency `reference` window, which
measures one device's connection. Neither says anything about the user's
monitors, which is all a backup is for.

### Things worth knowing

- **Import replaces, it does not merge.** A merge has to invent an answer for two
  monitors with the same id and different settings, and the case this exists for
  is a fresh install with nothing to merge against. The screen confirms first.
- **The file picker filters on `*/*`, not `application/json`.** Mime detection for
  `.json` is inconsistent across storage providers, and a filter that hides the
  user's own backup is worse than one that shows too much. `BackupCodec.decode`
  is what actually validates.
- **The import reschedules everything and then runs a pass.** Imported monitors
  have no work enqueued in this install; without `syncAll` + `ensureSweep` they
  would sit there until something else triggered a sync, and without the pass the
  user would be looking at a screen of grey UNKNOWN cards wondering if it worked.
- **`BackupTest` is 15 JVM tests and deserves to stay that thorough.** This path
  runs exactly once per user and there is no second chance if it silently drops
  something.

## 1.6.0 — a cancelled check is not a crashed one (field bug)

**Reported from real use, with screenshots.** Six monitors, six simultaneous
notifications reading **`URGENT · <name> is down` / `Checker crashed`**,
`ongoing`, `CATEGORY_ALARM`, DND-bypassing, vibrating every 60 s. All six
timestamped 19:38 — the same minute the foreground-service notification directly
above them read **"Strict monitoring · All 6 operational"**, and the same minute
each monitor's own history recorded a *successful* check. Nothing had crashed and
nothing was down.

### Root cause

`CheckEngine.runLocked` wrapped the check in `catch (Throwable)` and turned
whatever it caught into a failed `CheckResult`:

```kotlin
val result = try {
    dryRun(monitor)
} catch (error: Throwable) {           // <- also catches CancellationException
    CheckResult(ok = false, failureKind = UNKNOWN, message = "Checker crashed", …)
}
```

`kotlin.coroutines.cancellation.CancellationException` is an
`IllegalStateException`, not an `IOException`, so it sailed past every
classification in `HttpChecker.classify` and landed here. That fabricated result
then went down the **ordinary down-alert track**: `AlertDecider.decide` with the
default `failureThreshold = 1` returned `Kind.DOWN`, `AlertCenter.notifyDown`
posted on a HIGH-importance channel, and because the monitors had `urgent = true`
the URGENT loop started too.

Three things made it much worse than a single wrong notification:

1. **The persist came after the notification.** `alerts.notifyDown(...)` is
   synchronous; the `store.updateRuntime(...)` that would have recorded
   `lastAlertAt` and `alerting` was the *next suspending call on the same
   cancelled coroutine*, so it threw immediately. Nothing was persisted — so the
   cooldown never engaged, `wasAlerting` stayed `false`, and the next cancellation
   fired a fresh full-volume `Kind.DOWN` all over again. That is the "it keeps
   vibrating".
2. **The urgent notification became an orphan.** `notifyUrgent` posts an
   `ongoing`, DND-bypassing notification, but the state that would let anything
   cancel it was never written. Only `reconcileNotifications` could clear it, and
   only from the service loop.
3. **It self-inflicted the cancellations.** See below.

### Where the cancellations came from — and why six at once

`MonitorScheduler` used `ExistingWorkPolicy.REPLACE` everywhere, and **REPLACE
cancels the work it replaces**:

- `MonitorWorker` re-armed itself at the end of `doWork()` — `enqueueUniqueWork`
  with `REPLACE`, under **its own unique name**, while still running. Every
  scheduled check cancelled itself on the way out.
- `MonitorScheduler.syncAll()` did the same `REPLACE` across *every* monitor, and
  it was called from `NightbellApplication.onCreate`, `BootReceiver`, every settings
  write, and — crucially — from the 15-minute `SweepWorker`, *after* that sweep
  had already run `runAllDue()`.

The reported screenshot is that last path exactly. `SweepWorker` and the
per-monitor chains were both on 15-minute cadences and had drifted into alignment
(they are all armed together by any `syncAll`). The sweep ran its own sequential
pass — visible in the history as three successes one second apart at 19:22:22/23/24
— and then `syncAll` REPLACEd all six unique work names, cancelling every
`MonitorWorker` running in parallel with it. Six cancellations, one instant, six
"Checker crashed" alerts, while the sweep's own checks had all passed.

A second, quieter bug fed the same fire: `MonitorWorker` called
`engine.run(monitorId)` **unconditionally**, with no due-check. That is why one
monitor on a 15-minute interval recorded three samples within three seconds — and
every redundant run was another chance to be cancelled and mis-reported.

### The fix, in four parts

**1 · Cancellation is never a verdict.** `CheckEngine` catches
`CancellationException` *before* the `Throwable` clause, records nothing and
rethrows. Same rethrow added to `HttpChecker`, `ElementChecker` (where it was
producing false `"Page failed to load"` outages), `LatencyReference`,
`MonitorWorker`, `SweepWorker` and `AlertActionReceiver`. `domain/Cancellation.kt`
adds `runCatchingCancellable`, and every `runCatching` around a suspending call in
the check path now uses it.

**2 · A checker fault is not a monitor failure.** A genuinely escaped exception no
longer touches the monitor's health at all — `run()` returns `null` and feeds
`domain/CheckerHealth`, a pure state machine with its own notification channel and
one deterministic id (`4243`, deliberately outside the `100_000..399_999` alert
range so the reconciliation sweep leaves it alone). It needs three consecutive
internal errors inside a 45-minute window before it says anything, never becomes
`ongoing`, vibrates only on the first raise, and clears on any completed check, on
`forgetMonitor` (delete/disable), on `resetCheckerHealth` (process start, boot,
settings write, service stop) or on evidence ageing out after 90 minutes. It is
held **in memory only** — so "clear stale crash state after app restart" is a
property of the type rather than a code path that could be forgotten.

**3 · Nothing cancels a check in flight any more.** Cadence is now a per-monitor
`PeriodicWorkRequest` with `ExistingPeriodicWorkPolicy.UPDATE`, which applies to
the next period and does not touch a running worker. `requestImmediate` moved to
its own unique name (`pulse.monitor.now.<id>`) with `KEEP`, so "check now" can
neither cancel the periodic worker nor cancel itself. No worker re-arms itself.
`MonitorWorker` now respects `CheckEngine.isDue` unless explicitly forced, which
kills the duplicate-check bursts. `syncAll` also cancels the legacy
`pulse.monitor.<id>` chain once, so upgrades do not leave an orphan.

**4 · The third state now exists.** `CheckerLimit` / `CheckerLimits` +
`data/health/SystemLimits` diagnose *why* checks are late — offline, metered,
battery saver, background-restricted, Doze, or the user's own switch — and surface
it in Settings → **Checker health**, with a route to the battery-optimisation
exemption. None of it is ever a notification. Battery saver is deliberately not
blamed while strict mode is running (a foreground service is not deferrable work),
and the "Android is delaying checks" verdict has a 50-minute floor on its
tolerance so a 1-minute interval is not flagged merely for hitting the documented
15-minute platform floor.

### The data on disk had to be repaired too

The bug wrote state, not just notifications: per monitor a `DOWN` health, a raised
`alerting` flag, a failure streak, `urgentActive`, and a persisted `Sample`
recording a failure that never happened. Shipping the engine fix alone would leave
all six cards red, would let `reconcileNotifications` keep the down notifications
alive (it treats `health == DOWN` as legitimate), and would have `lastResultFor`
re-hydrate the string verbatim into every urgent re-nag.

`domain/LegacyCrashRepair` scrubs it, and `NightbellStore.migrate` applies it **on
read** — in force from the first moment the new build runs, with no write to
schedule and no race against a worker that starts before a startup repair would
have finished. Rules:

- a runtime whose *current* `lastMessage` is the sentinel is reset to
  `Health.UNKNOWN` (not `UP` — nothing is actually known) with the streak, the
  alerting flag and `urgentActive` cleared;
- a runtime that has since had a real check keeps its current state; only the fake
  samples go;
- `PAUSED` stays `PAUSED`; mute windows, latency history and everything unrelated
  survive;
- fabricated samples are dropped, because uptime and p95 computed from failures
  that did not occur are worse than a shorter history. A sample must be *both* a
  failure *and* carry the exact sentinel note, which no genuine verdict does.

`REPAIR_VERSION` is bumped to 5 so the one-time `cancelEverything()` clears the
standing notifications, and `NightbellApplication.onCreate` additionally cancels the
checker-health id synchronously before any worker in the process can run.

### Store compatibility

`applicationId`, the DataStore name (`pulse_store`), the key (`snapshot_v1`) and
`SCHEMA_VERSION` are all unchanged. No field was removed or renamed; nothing new
is persisted. 1.5.0 installs update in place and keep their monitors, settings,
notification preferences and widget configuration.

### Things worth knowing

- `catch (Throwable)` around anything suspending is the shape of this bug. There
  is now `runCatchingCancellable` and `isCancellation` in `domain/Cancellation.kt`;
  prefer them, and treat a bare `runCatching` around a `suspend` call as a defect.
- `CheckerHealth.REPEAT_GAP_MS` must stay **shorter** than `EVIDENCE_TTL_MS` or
  `Action.REPEAT` is unreachable — the claim would always expire before its own
  repeat came due. There is a test asserting exactly that.
- `STREAK_WINDOW_MS` must span more than one WorkManager period (it is 45 min, three
  periods) or Doze breaks the streak of a genuinely broken checker every time the
  platform batches it. And a streak restarting deliberately does **not** withdraw a
  raised claim — otherwise a Doze-delayed fault would re-raise, and re-vibrate,
  every few errors.
- Notification ids `4242` (service) and `4243` (checker health) sit outside
  `ALERT_ID_MIN..ALERT_ID_MAX` on purpose. Anything added inside that range becomes
  fair game for `reconcileNotifications`.
- `MonitorWorker` rethrowing `CancellationException` is correct for
  `CoroutineWorker`: WorkManager records the run as cancelled rather than retrying
  work nobody is waiting for.
- **The two WorkManager guarantees the fix rests on were verified against the
  2.10.5 bytecode, not against the docs.**
  - `ExistingWorkPolicy.KEEP` (used by `requestImmediate`) skips enqueueing *only*
    when an existing spec for that unique name is `ENQUEUED` or `RUNNING`
    (`EnqueueRunnable.enqueueWorkWithPrerequisites`). Terminal work falls through to
    `CancelWorkRunnable.forNameInline` and the new request is enqueued — so KEEP
    coalesces a duplicate "check now" without ever permanently blocking one.
  - `ExistingPeriodicWorkPolicy.UPDATE` reads
    `Processor.isEnqueued(workSpecId)` first; when the worker **is** executing it
    neither calls `Scheduler.cancel` nor reschedules, and returns
    `APPLIED_FOR_NEXT_RUN` (`WorkerUpdater.updateWorkImpl`). A running check is
    genuinely untouched.
- **`UPDATE` throws `UnsupportedOperationException` if the existing spec under that
  unique name is one-time work.** This is why the periodic name is
  `pulse.monitor.periodic.<id>` and *not* the 1.5.0 name `pulse.monitor.<id>`,
  which held one-shots. Do not "tidy" the names back together — upgrading installs
  would throw on first sync. `pulse.sweep` was already periodic, so it is safe.
- **`UPDATE` preserves `lastEnqueueTime`, so a *shortened* interval only takes
  effect at the next period boundary.** Changing a monitor from 4 h to 15 min can
  leave up to 4 h before the new cadence engages. Covered rather than ignored:
  `SetupViewModel.save()` runs the monitor immediately, and the 15-minute sweep
  picks it up as overdue in the meantime — which is exactly the repair role the
  sweep exists for. Worth knowing before chasing it as a bug.

### What an adversarial review of this change turned up

The whole change was put through five independent dimension reviews (cancellation,
WorkManager semantics, notification lifecycle, migration, regression), each finding
then handed to a verifier told to refute it. 18 findings raised, 6 refuted, 12
confirmed — all 12 fixed, plus the refuted ones whose mechanism was sound anyway.
The ones worth remembering:

- **`cancelEverything()` does not re-post what is genuinely current.** The down and
  degraded tracks are transition-driven: with `alerting = true` persisted and
  `repeatEnabled = false` (the shipped default), `AlertDecider.decide` returns
  `NO_TRANSITION` for the whole outage. Wiping the shade without clearing the
  bookkeeping left a live outage with no notification and none coming.
  `repairNotificationsIfNeeded` now resets `alerting`/`lastAlertAt` fleet-wide via
  `NightbellStore.updateAllRuntimes`.
- **`urgentAcknowledged` is the field that silences urgent *permanently*.**
  `UrgentAlerts.evaluate` returns `NONE` for an acknowledged monitor and only clears
  the acknowledgement on a **successful** check — which never arrives while a site is
  down. Tapping "I've got it" on an ongoing fake nag was the expected user response,
  so affected devices very likely have it set. `LegacyCrashRepair` clears it.
- **Notify-then-persist is a cancellation hazard, not just a style.** Every alert
  side effect in `runLocked` is non-suspend, so cancellation can only be observed at
  the persist that follows — after the shade has already changed. A recovered monitor
  could stay recorded `DOWN` with `urgentActive = true`, and `tickUrgent` would
  re-shout about it. The commits in `runLocked`, `acknowledgeUrgent`, `tickUrgent`
  and `mute` are now `withContext(NonCancellable)`.
- **`ExistingPeriodicWorkPolicy.UPDATE` cannot resurrect terminal work.**
  `WorkerUpdater.updateWorkImpl` returns `NOT_APPLIED` when `state.isFinished`, and a
  single throwable escaping `doWork` is enough for WorkerWrapper to mark a periodic
  spec FAILED. That monitor would then never be checked again and every later
  `syncAll` would be a silent no-op. `MonitorScheduler.clearIfDead` cancels a spec
  whose every `WorkInfo` is finished before re-enqueueing — and only then, so it can
  never touch work in flight. `Nightbell.install` also moved inside both workers' `try`.
- **A gate is only as good as where it is evaluated.** `MonitorWorker` checked
  `isDue` and then blocked on the per-monitor mutex behind the sweep's check of the
  same monitor, so it ran anyway. `run()` now re-checks inside the lock, and takes a
  `force` flag so explicit user actions still always check.
- **`lastCheckedAt` is wall-clock.** With no unconditional background check path
  left, a clock moving *backwards* made `isDue` false for every monitor until the
  clock caught up. `DueCheck.isDue` treats a future stamp as due now.
- **A direct `vibrate()` obeys nothing.** `previewVibration` was called after
  `post()` regardless of whether the notification arrived, so turning the channel off
  — the remedy the notification itself invites — silenced the notice and left the
  buzz. `post()` now reports whether it posted, and `channelCanAlert` checks the
  channel's importance first. Applied to the urgent track too, where it was
  pre-existing.
- **Aggregating two monitors' numbers together produces nonsense.**
  `CheckerLimits` compared the *oldest* check age against the *tightest* interval, so
  a healthy 15-minute-plus-2-hour fleet read as "Android is delaying checks" for most
  of every two hours. Lateness is per monitor now (`MonitorCadence`).

### Device verification still outstanding

The JVM suite covers the state machines, the repair and `HttpChecker` cancellation
against real sockets. `CheckerCancellationInstrumentedTest` covers the rest but
needs a device or emulator — see the verification checklist at the end of this
section in the release notes below.


## 1.6.0 — a placed widget's settings were unreachable, and it could only be one of three colours

**Reported from real use:** "I can't find the settings of the widget after I placed
it on the homescreen."

Correct, and not a discoverability problem: there was no route. The config activity
is declared with `android:configure`, which Android launches **once**, when the
widget is dropped. Getting back to it needs
`android:widgetFeatures="reconfigurable"`, which did not exist here — and which was
only added in API 31 anyway, so on API 26–30 there is no platform route at all.

Three routes now, deliberately redundant:

1. **A cog in the widget's header.** The only one that works everywhere. The header
   row is kept even when the title is switched off, or hiding the title would hide
   the settings with it. It is per-widget optional (`showSettingsButton`).
2. **`widgetFeatures="reconfigurable"`** for the long-press menu on API 31+.
3. **Settings → Home-screen widgets**, enumerated with
   `AppWidgetManager.getAppWidgetIds`.

The config screen now knows which of the two it is: `WidgetConfigStore.exists`
distinguishes a freshly dropped widget (*Add widget*, and Cancel means "don't place
it") from a revisit (*Save*).

### Arbitrary colours on API 26

`RemoteViews` cannot recolour a `View`'s background below API 31, which is why the
widget had exactly three looks — one compiled-in `<shape>` each. The surface is now
two tintable `ImageView`s *behind* the content:

- `widget_surface`, a white rounded rect, tinted with `setColorFilter` and faded
  with `setImageAlpha`;
- `widget_surface_border`, stroke only, on the same treatment.

Both methods are `@RemotableViewMethod`, so this works from API 26 up, and the
rounded corners survive — which a flat `setBackgroundColor` would not.
`WidgetInstrumentedTest` inflates custom colours at 0%, 35% and 100% opacity through
`RemoteViews.apply()`, which is the same inflation the launcher's process runs, so a
non-remotable method fails the test rather than showing "Problem loading widget" on
someone's home screen.

Details that matter:

- `setColorFilter` wants **opaque** RGB and `setImageAlpha` carries the
  transparency. Passing a translucent colour to `setColorFilter` tints by the
  *filter's* alpha and looks nothing like the swatch — hence `opaque()`/`alphaOf()`.
- `customBackgroundRgb` stores RGB only. Alpha is `backgroundOpacity`'s job, so
  dragging opacity to zero and back cannot lose the colour.
- The border alpha is `backgroundOpacity × 0.20`, so a fully transparent widget has
  no ring floating around it.
- Presets ignore `backgroundOpacity` on purpose: they are surfaces with known
  contrast, and letting a stray value apply would quietly make a legible preset
  illegible.
- The Compose preview reads `WidgetConfig.palette`, the same property the real
  widget reads. It used to hard-code its own copy of the three themes, which is
  survivable until the palettes gain arithmetic — then the preview disagrees with
  the widget about exactly the setting being previewed. The preview also draws a
  checkerboard behind the surface so "fully transparent" previews as see-through.
- `WidgetTheme` gained `CUSTOM` and `WidgetConfig` gained five fields, all with
  defaults. Enum values serialise by name and unknown keys are ignored, so widgets
  placed by 1.5.0 keep their exact look and gain the cog. `widget_bg_black/white/blue`
  are gone; nothing references them.

## 1.5.0 — discount the phone's own connection before calling anything slow

**Reported from real use:** DEGRADED alerts for services that were fine. The
cause is arithmetic, not a bug: a latency measured from a phone is the network
round trip *plus* the server's time, and those are indistinguishable from one
measurement. So a bad connection makes **every** monitor breach its SLO at once,
and every one of those alerts is wrong.

The fix is a control. `data/check/LatencyReference` times an endpoint that is
always up, and `domain/NetworkBaseline` uses it to estimate what the network is
contributing.

### The maths, and why it is the *excess*

```
floor    = 25th percentile of the reference window   (its good-conditions cost)
current  = median of the last three readings         (what it costs right now)
excess   = current − floor
adjusted = measured latency − excess
```

Subtracting the reference's **total** would be wrong: a connection with a 300 ms
floor is not slow, that is simply what it costs, and the SLO was chosen while
living with it. Only the reference being slower *than it usually is* has nothing
to do with the server.

Estimator choices, both of which matter:

- **Floor is the 25th percentile, not the median.** If the connection has been
  poor for most of the window the median is poor too, the excess collapses to
  zero, and the compensation stops working exactly when it is needed. Not the
  minimum either — one lucky round trip would drag the floor down and start
  suppressing real alerts.
- **Current is the median of three, not the latest.** One unlucky round trip
  would otherwise wipe out a genuine degradation.

Four trust states. `UNKNOWN` (too few readings, all stale, or the reference is
unreachable) means judge the raw number exactly as before this existed;
`UNRELIABLE` means the reference is so far off its floor that nothing measured
through this link means anything, and no degradation is claimed. `UNRELIABLE`
needs a large absolute excess **and** a large relative jump: 4× a 30 ms floor is
still only 120 ms, and 750 ms may be normal variation on a satellite link.

Every threshold errs toward *reporting* slowness. A missed "slow" alert costs
less than a real degradation written off as bad wifi.

### Things worth knowing

- **It only ever touches the latency verdict.** A bad connection is a reason to
  doubt a *slow* reading, never a reason to stay quiet about an outage — DOWN is
  untouched. Suppressing that would risk hiding a real failure, which is the one
  thing this app exists to catch. (Timeout-driven DOWN on a terrible connection
  is a real remaining gap; see below.)
- **`lastLatencyMs` still records what was measured.** The adjustment is an
  interpretation and overwriting the observation with it would make the history
  lie. The excess is stored beside it so the card can show `−300 ms`, because an
  invisible correction to a number the user is reading is indistinguishable from
  a bug.
- **The window is persisted, not in memory.** WorkManager can run each pass in a
  fresh process, so an in-memory window would never reach the minimum size and
  the compensation would silently never engage.
- **One probe per pass, not per check.** It was in `runLocked` first, which meant
  a reference the network blocks added its whole timeout to every check — the
  measurement was unaffected, but the pass got slower for nothing. This showed up
  as a flaky `NightbellE2ETest`, not as an obvious failure. Probes also back off
  exponentially while failing, so a network that blocks the endpoint costs one
  wasted request per half hour.
- **Not ICMP.** Raw sockets need root on Android. This is HTTP round trip to
  first byte, which is *better* for the purpose: it includes DNS, TCP and TLS —
  exactly the costs a monitor's own request pays.

**Verified:** 138 JVM (`NetworkBaselineTest`, 19 — including the reported
scenario and its counter-case, that a genuinely slow server still gets flagged
through a mediocre link) + 84 on-device (`LatencyBaselineInstrumentedTest`, 8,
driving the real engine with a seeded window). On this emulator the default
reference is unreachable, and that path was confirmed end to end: the stored
window stays `[]`, checks run normally and the raw verdict stands.

**Known gap:** a connection bad enough to time a request out still produces a
false DOWN. Fixing that means suppressing outage alerts on evidence about the
network, which needs more care than this did.

APK: `artifacts/Pulse-1.5.0-release.apk` · versionName `1.5.0` · versionCode `8`.

---

## 1.4.0 — favicons on page-element cards

A page-element monitor *is* the page it watches, so the site's own mark
identifies it faster than one more identical cursor glyph down a list.
`data/icons/FaviconStore` resolves and caches it; `IconBadge` gained an `image`
parameter that replaces the glyph, drawn untinted (the accent tint that makes a
monochrome stroke icon read would destroy a real logo). Scoped to
`WEBSITE_ELEMENT` only — an API endpoint has no favicon worth showing.

### `BitmapFactory` cannot decode ICO

This is the whole difficulty, and it is not obvious until it fails: `/favicon.ico`
is the one path every site has, and Android cannot decode the ICO format at all.
Modern `.ico` files are *containers* whose entries are usually complete PNGs, so
the store parses the 6-byte header plus 16-byte directory entries, picks the
largest PNG-encoded entry and hands that to the decoder. Entries in the older DIB
encoding are skipped rather than half-supported — reconstructing a BMP header and
its AND mask is a lot of fiddly code for icons that a `<link rel="icon">` almost
always supersedes anyway. There is a test that builds a real ICO-wrapped PNG.

Resolution order is `<link rel="…icon…">` from the page head first (the only
authoritative answer — plenty of sites serve a placeholder or an HTML error page
at `/favicon.ico`), largest declared `sizes` winning, then the well-known paths.
`data:` and `.svg` hrefs are skipped since neither can ever decode.

### Caching is a requirement, not an optimisation

The dashboard recomposes constantly and a `LazyColumn` re-runs an item's effects
every time it scrolls back into view. Uncached, that is one request to somebody
else's server **per scroll**. Three layers: an `LruCache`, then
`filesDir/favicons`, then network at most once per origin per 30 days. Details
that each exist for a reason:

- **Negative caching.** Plenty of sites have no icon; without remembering that,
  every scroll re-probes all three well-known paths forever.
- **An offline failure is not a miss.** Gated on `NetworkMonitor.isOnline()` and
  it returns without recording anything, otherwise one tunnel would blank every
  badge for the length of the negative TTL.
- **The cache key is the origin,** so two monitors on one site share one fetch.
- **A per-origin `Mutex`,** because several cards compose at once and would
  otherwise each fire the same request.
- **Expired beats empty.** A stale icon is served if a refetch fails; a site's
  mark rarely changes and a blank badge is worse than a slightly old one.
- **Writes go via a temp file + rename,** or a process death mid-write leaves a
  truncated PNG that decodes to null on every future launch.

`TinyHttpServer.Response` gained a `bytes` field: the existing `body` is encoded
as UTF-8, which silently mangles binary, so a PNG served through it never decodes.

**Verified:** 119 JVM + 76 on-device (`FaviconStoreInstrumentedTest`, 8 — each
asserting on the *delta* in requests the server saw, which is the only thing that
actually proves a cache works). Screenshot `36-favicon-badges.png` shows the icon
on the page-element card and the server glyph retained on a status monitor
pointing at the same site. R8 keeps the class (`FaviconStore -> n5.f`, all methods
present in the mapping) — there is no reflective surface here.

> Emulator caveat for the next session: this emulator has IP connectivity but **no
> working DNS**, so real-site favicons cannot be tested on it. Both the tests and
> the screenshot use a local `TinyHttpServer`.

APK: `artifacts/Pulse-1.4.0-release.apk` · versionName `1.4.0` · versionCode `7`.

---

## 1.3.0 — no connectivity means paused, not down (field bug)

**Symptom reported from real use:** with no wifi or data the phone filled up with
"down" notifications. Every monitor failed at once, and all of them were wrong.

**Why it happened.** A check with no network fails for a reason that has nothing
to do with the monitored thing — and it fails for *every* monitor
simultaneously, so one walk through a tunnel produced a full sweep of false
outages, poisoned the uptime history with them, and (for urgent monitors) started
an un-dismissable nag loop. WorkManager's `NetworkType.CONNECTED` constraint was
already in place and did not help, for two reasons worth remembering: it does not
require the network to actually work, and **strict mode's foreground service does
not go through WorkManager at all** — it runs its own loop, which is where the
spam came from.

**The gate lives in `CheckEngine`,** via an injected `isOnline: () -> Boolean`
wired to the new `data/net/NetworkMonitor` by the graph (same lambda trick as
`onStateChanged`, so the engine stays Android-free and testable). Three places
needed it, and finding only the first would have looked like a fix while still
spamming:

1. `runAllDue()` returns 0 immediately. `force` does **not** override this —
   "check now" means "don't wait for the interval", not "check even though it
   cannot possibly succeed".
2. `runLocked()` bails as a backstop, so no caller can record an offline failure.
   It bails **before `markChecking(true)`** — after it, the matching `false` lives
   in a `finally` that never runs and the card spins forever. There is a test.
3. `tickUrgent()` skips the re-nag. This is the second, less obvious spam source:
   with no checks running at all, a monitor that was down when signal dropped
   keeps shouting from persisted state every few minutes. Reconciliation still
   runs offline on purpose — it only ever *cancels* notifications nothing can
   justify, which stays correct with no network and is the one path that clears
   an orphaned `ongoing` urgent alert.

### `NET_CAPABILITY_VALIDATED` is the trap here

The obvious definition of "online" is `INTERNET && VALIDATED`, and that was the
first implementation. It is wrong, and it was caught by testing on a device
rather than by reasoning: **this emulator's network never gets `VALIDATED`**,
because validation only appears after the framework's own probe to a Google
endpoint succeeds. So `VALIDATED` is absent in three unrelated situations — no
connectivity, a captive portal, and *a perfectly good network whose probe cannot
get out*: a firewalled office LAN, DNS filtering, a pi-hole, a sandboxed
emulator. Requiring it means Nightbell silently stops monitoring on networks where it
works fine.

That failure is strictly worse than the bug being fixed. Spam is visible and
annoying; silence is invisible and lets a real outage pass unnoticed. So the rule
is `INTERNET && !CAPTIVE_PORTAL` — the portal case is the one Android tells us
about explicitly — and every unexpected path in `isOnline()` **fails open**.

> Residual gap, accepted knowingly: a network that advertises internet and
> blackholes traffic without being flagged as a portal still produces failed
> checks. Rarer than losing signal, and it errs toward reporting what it actually
> observed.

**Elsewhere:** the banner turns blue and reads `NO CONNECTION · Checks paused`
with the CTA disabled to "Waiting for a connection" — it deliberately does *not*
show the last-known verdict as if it were current, which is the same lie the
notifications were telling. The foreground-service notification says
"Monitoring paused · offline" ahead of the fleet verdict, since it is permanently
on screen claiming to describe the present. Manual check paths toast instead of
silently doing nothing. `NetworkMonitor.onReconnected` runs a pass the moment
connectivity returns, so it does not wait out the interval. New `WifiOff` icon.

**Known gap:** the home-screen widget still shows last-known state with no
offline marker.

**Verified:** 119 JVM + 67 on-device (new `OfflineGateInstrumentedTest`, 5).
Then on the device with wifi and data actually switched off: 45 minutes' worth of
ticks produced **zero** notifications and `CheckEngine: Offline — check pass
skipped` in the log; the banner flipped live in both directions; and the same
online→offline→online cycle was re-run against the **minified release**, where
uptime moved only after reconnecting — which is `onReconnected` firing a real
pass. R8 leaves the anonymous `NetworkCallback` alone.

APK: `artifacts/Pulse-1.3.0-release.apk` · versionName `1.3.0` · versionCode `6`.

---

## 1.2.0 — the fleet banner replaces the uptime dial

**What changed.** The dashboard's top block — wordmark line plus overview card —
is now one state-coloured banner (`ui/dashboard/FleetBanner.kt`). Ten candidate
redesigns were built as live Compose and reviewed as device screenshots, each in
a healthy and an alerting state; this one was chosen. The other nine, the lab
harness and the comparison screenshots are deleted — the chosen design is the
record that matters, and the rejects were 11 MB of PNGs.

**`Sparkline` lost its failure dots.** The stroke now carries the health itself:
one `Brush.horizontalGradient` stop per sample, which — because the stops are
evenly spaced and so are the points — lands each stop exactly on its own point.
A failed check is reddest at its peak and interpolates back to the accent as
soon as the next one passes. Two reasons this beats markers: dots made a
one-check blip and a twenty-check outage exactly the same size on screen, and on
a bad run they stacked into noise over the line they were annotating. The
`HistoryStrip` underneath still gives the discrete per-check readout, so nothing
was lost. The leading "now" dot stays, tinted by the last sample's outcome.

**The two charts on a card are now aligned by construction** — reported from a
real phone: the red in the line did not sit above the red ticks. Two independent
causes, both in `Status.kt`:

1. **Different windows.** `Sparkline` got `takeLast(28)` and `HistoryStrip`
   `takeLast(40)`, so the same check landed at different fractions — and a
   failure old enough to be in the strip but not the line showed a red tick under
   an untouched blue stretch. There is now one `SAMPLE_WINDOW = 40` and
   `MonitorRowCard` computes the list **once** and hands the same instance to
   both, so they cannot be windowed apart again.
2. **Different x axes.** The strip laid out *cells* (centre of tick `i` at
   `i*(tick+gap) + tick/2`) while the line spanned *edge to edge*
   (`i * w/(n-1)`). Those agree nowhere except the middle, drifting up to half a
   cell at the ends. Both now call one private `sampleCenterX()`.

Because the points moved to cell centres, the stroke's gradient stops had to
become **explicitly positioned** — `Brush.horizontalGradient(colors)` spreads
evenly over `0..w`, which is half a cell off once the first point is inset. The
stops are now pinned to each sample's own `sampleCenterX(i)/w`.

> The visible cost: the sparkline draws 40 points instead of 28, so it is
> slightly busier. Alignment is worth more than smoothing, and the cubic path
> absorbs it.

> Verified by measuring a screenshot, not by eye: on a 7-sample monitor the line
> is blue at tick 0's centre and fully rose by tick 1's centre, and the head dot
> sits exactly on the last tick's centre.

**The argument for it.** The dial it replaced put the *number* in 112dp of
prominence and the *verdict* in a 13.5sp subtitle, which is backwards for a
screen someone opens to be reassured in one glance. Now the whole surface takes
the worst monitor's colour, so "is anything broken" is answered before any text
is read, and the tick row underneath says *how much* of the fleet is affected
without opening anything. `Summary.headline` is the single source of the verdict
string, so the banner, the widget and the service notification cannot disagree.

**Three things this broke, all fixed — none caught by compilation:**

1. **`clearAndSetSemantics` on the headline row wiped its text nodes.** Three
   `NightbellE2ETest` assertions went red because `onNodeWithText("All 1 operational")`
   could no longer see through the merged node. The fix is the pattern
   `SectionHeader` already used: leave the visible `Text` alone and put the
   human-readable form in `contentDescription`. `Mono` now takes a `spoken`
   parameter for exactly this — every ALL-CAPS string in the banner needs it, or
   TalkBack reads `UPTIME` as six letters.
2. **"No monitors yet" disappeared.** It used to be the header subtitle, and
   hiding the banner on an empty fleet took it with it. The banner now renders
   at `total == 0` in reduced form — icon, `NOTHING WATCHED`, headline, and it
   stops there, because there is no uptime, no fleet and nothing to re-check.
3. **The toast covered the verdict.** `TOAST_TOP_GAP` was 66dp to clear the old
   tall wordmark and its subtitle; against the new 34dp identity row that landed
   the capsule squarely on "All 5 operational". Now 10dp, which parks it over
   the wordmark row — the cheap thing to cover is the app's own name.

> The general lesson: `clearAndSetSemantics` is not a free "tidy up the a11y
> tree" — it deletes what it merges, including the text your tests match on. Use
> it on decorative rows (the tick strip) and `contentDescription` on the leaf
> for everything that carries words.

**Verified:** 119 JVM + 62 on-device tests pass (`NightbellE2ETest` 8,
`AlertsInstrumentedTest` 11, `ElementMonitorTest` 10,
`UrgentModeInstrumentedTest` 13, `WidgetInstrumentedTest` 17, `ScreenshotTest` 3).
The signed minified release was installed **over the previous release install**
and came up reading *"2 of 2 are down"* against its pre-existing monitors — so
R8 left the new composables alone and the store survived the update. Screenshots
of all four banner states (empty, all-clear, single outage, mixed fleet) are in
`artifacts/screenshots/`.

APK: `artifacts/Pulse-1.2.0-release.apk` · versionName `1.2.0` ·
versionCode `5` · 1,977,768 B. Mapping: `artifacts/mapping/mapping-1.2.0.txt.gz`.

---

## 1.1.2 — notification-orphan fix (field bug)

**Symptom reported from real use:** repeated "Videre2 is down · Check failed"
alerts for a monitor that was up, with no downtime in its history.

**Diagnosis** (over wireless adb to an S25 Ultra, One UI 8 / API 36). The app
was right and the notifications were stale. `dumpsys notification` showed six
`category=alarm` urgent notifications and six `category=err` down notifications
standing, while the app's own foreground-service notification read
*"Strict monitoring · All 6 operational"* and no monitor showed an Urgent tag or
an Acknowledge button. The notifications were **orphans**: posted, with no state
left that would ever cancel them. Because urgent is `ongoing`, they could not be
swiped away either — the only remaining escape was clearing app data.

**Three defects, all fixed:**

1. **`UrgentAlerts.evaluate()` returned `NONE` instead of `CLEAR`** when a
   monitor was healthy and the previous state was already idle. That treats
   persisted state as a truthful record of what is on screen. It isn't:
   `run()` reads it *before* a check that takes seconds, so two overlapping runs
   of one monitor could post from the stale-losing run and then persist idle
   state from the other. Combined with `tickUrgent()` skipping non-nagging
   monitors, nothing could ever cancel the result. Now a healthy check always
   reconciles, and cancelling an unposted id is a free no-op.
   The same stale-read gated the down notification's cancel; that is now
   unconditional on a healthy check too.

2. **Deleting a monitor only cancelled its down notification.** `cancelUrgent`
   and `cancelDegraded` were never called, and once the monitor was gone no
   per-monitor loop could ever visit it again. Both delete paths now call
   `AlertCenter.cancelAll`. Added a reconciliation sweep in `tickUrgent()` that
   compares `NotificationManager.getActiveNotifications()` against the ids
   monitors can currently justify, which is the only way to see this class of
   orphan at all.

3. **A throwing check pass skipped the sweep.** `runAllDue()` and `tickUrgent()`
   shared one `runCatching` in the service loop, so one WebView blowing up took
   the reconciliation with it for that tick. Split into separate catches. This
   is why the sweep alone did not clear the device on first deploy.

Plus a **per-monitor `Mutex`** in `CheckEngine`, so same-monitor checks cannot
interleave and re-create the race; `mute()` collapsed from two writes to one for
the same reason; and a **one-time upgrade repair** (`NightbellApplication`,
`GlobalSettings.notificationsRepairedForVersion`) that clears the slate once
after updating, because orphans left by 1.1.0/1.1.1 cannot be enumerated from
state.

**Verified on the device:** 12 orphaned notifications before, 0 after, then
three minutes of steady running with no phantom alerts and all six monitors
intact. Regression tests added: 4 new instrumented (`UrgentModeInstrumentedTest`
→ 13) and 1 JVM.

> Worth internalising: the urgent notification is `ongoing` by design, which
> makes any bug in its lifecycle strictly worse than a bug in a dismissible one.
> Anything that posts an `ongoing` notification needs a reconciliation path that
> does not depend on the state that posted it.

---

**Status: 1.1.0 complete and verified.** Minified, resource-shrunk, signed
release APK built, installed *over a real 1.0.0 install* and smoke-tested on an
emulator; 175 automated tests pass (118 JVM + 57 on-device).

---

## Deliverables

| Item | Path |
| --- | --- |
| Release APK (signed, R8) | `artifacts/Pulse-1.1.0-release.apk` |
| Debug APK | `artifacts/Pulse-1.1.0-debug.apk` |
| R8 mapping | `artifacts/mapping/mapping-1.1.0.txt.gz` |
| Gradle output (release) | `app/build/outputs/apk/release/app-release.apk` |
| Screenshots (27) | `artifacts/screenshots/` |

APK facts: `me.river.pulse` · versionName `1.1.0` · versionCode `2` ·
minSdk 26 · targetSdk 36 · signed `CN=Pulse Monitor, O=Bohemian Karst, C=CZ`.

**Keep the mapping file.** Release is obfuscated now, so a stack trace is
unreadable without it. `mapping-1.1.0.txt.gz` is the only copy that survives the
next `./gradlew clean`.

Android APKs are not byte-reproducible (zip metadata differs), so rebuilding
yields a different hash for the same sources.

## Size

| Build | Size | Notes |
| --- | ---: | --- |
| 1.0.0 release (`isMinifyEnabled = false`) | 9,549,797 B (9.55 MB) | |
| 1.1.0 release (R8 + resource shrinking) | 1,961,380 B (1.96 MB) | **−79.5%** |

That is *after* adding a widget, a foreground service, three new domain modules
and the blur machinery — so the shrink is doing more work than the raw delta
suggests. Single `classes.dex`, 3.24 MB uncompressed.

## Toolchain that worked

Java 21 · Gradle 8.13 (wrapper) · AGP 8.13.2 · Kotlin 2.2.21 ·
Compose BOM 2025.10.01 (Compose 1.9.4, Material3 1.4.0) · compileSdk 36 ·
`androidx.profileinstaller` 1.4.1 (added).

> Newer androidx (core-ktx 1.19, lifecycle 2.11, Compose BOM 2026.x) hard-requires
> AGP 9.1+/compileSdk 37 and will fail the build on this toolchain. If you upgrade
> androidx, upgrade AGP and Gradle in the same commit.

## What changed in 1.1.0

| # | Feature | Where |
| --- | --- | --- |
| 1 | Baseline profile + startup pass | `app/src/main/baselineProfiles/baseline-prof.txt` |
| 2 | R8 + resource shrinking | `app/build.gradle.kts`, `app/proguard-rules.pro` |
| 3 | Strict foreground monitoring | `data/work/NightbellMonitorService.kt` |
| 4 | URGENT mode | `domain/UrgentAlerts.kt`, `data/check/CheckEngine.kt`, `data/alerts/AlertCenter.kt` |
| 5 | Latency SLOs + DEGRADED alerts | `domain/AlertDecider.decideDegraded`, `Monitor.latencySloMs` |
| 6 | Multi-element page monitors | `Monitor.elements`, `PickerScripts.locateMany`, `ElementChecker.locateAll` |
| 9 | Configurable home-screen widget | `widget/` |
| 10 | Wear tile | **not shipped** — groundwork + plan below |
| 11 | Real backdrop blur (API 31+) | `ui/theme/Backdrop.kt` |
| — | Muted monitors read amber, not red | `ui/dashboard/DashboardScreen.kt` |
| — | Pull-to-refresh: rubber band + hold-to-confirm | `ui/components/Status.kt` |

> The brief as received skipped from item 6 straight into the middle of the
> widget item; **items 7 and 8 were missing from the text**. Everything that was
> legible is implemented. If 7/8 were meant to be import/export and E2E-encrypted
> sync (the next two entries on 1.0.0's own idea list), they are still open.

## Verification

### Tests

```
./gradlew :app:testDebugUnitTest         → 118 tests, 0 failures
```

On-device (emulator `pulse_api34`, API 34), run one class at a time — see
Gotchas:

| Class | Result |
| --- | --- |
| `AlertsInstrumentedTest` | OK (11) |
| `ElementMonitorTest` | OK (10) |
| `UrgentModeInstrumentedTest` | OK (8) |
| `WidgetInstrumentedTest` | OK (17) |
| `NightbellE2ETest` | OK (8) |
| `ScreenshotTest` | OK (3) |

### The minified build, exercised on a device

`assembleReleaseTest` builds a minified, debug-signed variant with
`applicationIdSuffix .minified`, so R8 can be smoke-tested without touching the
installed release. Every reflective path was driven through the UI:

- **kotlinx.serialization** — created a monitor, force-stopped the process,
  relaunched: monitor and history intact.
- **WebView JS bridge** — opened the picker, tapped an `<h1>`, got
  `#hdr` / `<h1>` / `"Fixture"` back. **This was a real latent bug:** the 1.0.0
  keep rule only matched `data.web.**`, but the only actual bridge
  (`PickerBridge`) lives in `ui.setup`. Under the old rule R8 would have stripped
  it and the picker would have silently stopped returning selections.
- **WorkManager** — jobs present in `dumpsys jobscheduler` after the update.
- **Notification action receivers** — `RECHECK` and `ACK_URGENT` resolve by name.
- **Foreground service** — enters the foreground with
  `types=40000000` (`SPECIAL_USE`) and posts on `pulse.service.strict`.
- **Widget provider** — registered in `dumpsys appwidget`.
- **Multi-element** — captured two nodes on one page, test reported
  *"All 2 elements matched in 843ms"*.
- **URGENT** — killed the fixture server, both monitors went down, an urgent
  notification appeared on `pulse.urgent.double_pulse`
  (`importance=4 category=alarm flags=0x2 actions=2`, separate id from the down
  notification), the dashboard read *"urgent, not acknowledged"*, and tapping
  Acknowledge cancelled **only** the urgent notification while the monitor
  stayed DOWN.

### Data preservation across the update

Not asserted — performed. Two monitors were created in the **actual installed
1.0.0 release** (one a single-element page monitor), then
`adb install -r` (no uninstall) put 1.1.0 over it:

- both monitors present, `100% uptime`, `408 ms average`, history intact;
- the legacy element monitor now reads
  *"Watching: 1 element on one page load · `<h1>` · Element exists"*;
- re-checking it succeeded through the new multi-target code path (945 ms).

## Reproducing

```bash
cd "/home/river/Projects/monitoring app"

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest

adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant me.river.pulse.debug android.permission.POST_NOTIFICATIONS
for C in AlertsInstrumentedTest ElementMonitorTest UrgentModeInstrumentedTest \
         WidgetInstrumentedTest NightbellE2ETest ScreenshotTest; do
  adb shell am instrument -w -e class me.river.pulse.$C \
    me.river.pulse.debug.test/androidx.test.runner.AndroidJUnitRunner
done

# Screenshots (internal storage; adb shell cannot read Android/data on API 30+)
for f in $(adb exec-out run-as me.river.pulse.debug ls files/screenshots/ | tr -d '\r'); do
  adb exec-out run-as me.river.pulse.debug cat "files/screenshots/$f" > "artifacts/screenshots/$f"
done
```

**Rebuild the app APK, not just the test APK.** `assembleDebugAndroidTest` does
not repackage `app-debug.apk`, so installing a stale one against fresh tests
fails with `NoSuchMethodError` on any data class whose constructor changed. Cost
one confusing run here.

## Not done, and why

### 10 · Wear OS tile — blocked, not skipped

**Blocker (environmental).** No Wear system image is installed
(`$ANDROID_HOME/system-images` has android-33 and android-34 phone images only),
and a tile cannot be meaningfully verified without one. Worse, getting monitor
state onto a watch needs the Wearable Data Layer from
`play-services-wearable`, which needs a GMS-flavoured Wear image and a paired
phone emulator. Shipping an unverifiable module — and dragging Play Services
into a build that currently has zero Google dependencies — would have
destabilised the phone APK for a feature nobody could confirm works.

**Groundwork that is real and shipped:** `domain/Summary.kt` is the worst-first
roll-up a tile needs — pure Kotlin, no Android types, 10 unit tests, and already
the single source of truth for the dashboard header, the widget and the
foreground-service notification. Nothing speculative was added; there is no dead
code waiting for a consumer.

**Plan, concretely:**

1. `sdkmanager "system-images;android-34;android-wear;x86_64"`, create a Wear
   AVD, pair it to `pulse_api34`.
2. New `:wear` module — `com.android.application`, minSdk 30, its own manifest,
   `applicationId` **identical** to the phone app (Wear requires this for a
   paired install) but its own `versionCode` line.
3. Phone side: `implementation("com.google.android.gms:play-services-wearable")`,
   and in `Nightbell.Graph.notifyStateChanged()` also push a compact payload —
   `Summary.Fleet.worst` plus the counts — via
   `DataClient.putDataItem("/pulse/summary")`. That hook already exists and is
   already called from every place state changes.
4. Wear side: `WearableListenerService` caches the payload to its own DataStore;
   `TileService` renders it with `androidx.wear.protolayout`
   (`PrimaryLayout` + `CircularProgressIndicator`, worst monitor as the title,
   `Summary.Fleet.headline` as the subtitle) and calls
   `getUpdater(context).requestUpdate()` on each new payload.
5. Reuse `Summary.severity()` for the colour so the watch and the phone can
   never disagree about which monitor is worst.
6. Add `:wear` to `settings.gradle.kts` **last** — the phone APK must keep
   building standalone.

Estimated one focused session, most of it emulator wrangling.

### Baseline profile is hand-authored, not generated

A macrobenchmark-generated profile needs a `:baselineprofile` module,
`androidx.benchmark:benchmark-macro-junit4`, and a **rooted** `google_apis`
emulator to run `BaselineProfileRule` — this session's emulator is headless with
`swiftshader_indirect`, and adding a benchmark module changes the build graph
for every variant.

What is shipped instead: a hand-authored profile covering process start, the
service locator and store decode, every `@Serializable` model, the theme, the
dashboard, and (as post-startup) detail, setup and the check pipeline. AGP
merges it into `assets/dexopt/baseline.prof` (4,855 B + 612 B `.profm`) and
rewrites it through the R8 mapping — verified by finding renamed entries like
`HSPLw5/m;->c(Lme/river/pulse/domain/Health;)J` (that is
`ThemeKt.healthColor`) in the final merged profile.

**Honest caveat:** class-level rules (`Lcom/…;`) land reliably; some of the
hand-written *method* signatures for top-level `…Kt` composables do not resolve,
because the Compose compiler rewrites those signatures heavily. Those lines are
inert, not harmful. Replacing this file with a generated one is the single
highest-value follow-up for startup.

### Not attempted

- CI config, Play Store metadata.
- Import/export of the store JSON, and encrypted sync (possibly the missing
  items 7–8).

## Gotchas for the next session

- **Two graphics layers for backdrop blur, never one.** `GraphicsLayer.renderEffect`
  applies every time the layer is drawn, so recording the content into a blurred
  layer and drawing it puts the *whole screen* out of focus. This was caught on
  a device screenshot, not by a test. `BackdropState` holds `sharp` (drawn to
  screen) and `blurred` (re-records `sharp` through the effect; sinks sample it).
- **A blur sink must be invalidated by its source.** Compose only redraws a node
  when state *it* reads changes. A pane drawing somebody else's layer reads
  nothing, so it would freeze mid-scroll. `BackdropSourceNode` calls
  `invalidateDraw()` on every registered sink right after recording. It cannot
  loop: invalidating a sink never dirties the source.
- **A sink must live outside the recorded subtree.** Otherwise frame N contains
  frame N−1's blurred pane and smears worse every frame. `BackdropHost`'s
  `content`/`overlay` split enforces this structurally — that is why the setup
  footer became a `BottomCenter` overlay whose measured height feeds back as the
  scroll area's bottom padding.
- **Infinite animations vs. Compose tests.** Every looping animation goes
  through `rememberLoopingFloat`, which collapses to a constant when
  `NightbellMotion.enabled` is false. Tests set `motionIntensity = 0f` *before*
  launching the activity. If you add a new `rememberInfiniteTransition`
  directly, the UI test suite will hang. The new hold-to-confirm timer respects
  this too — at zero motion it commits in 1 ms instead of 2 s.
- **`SectionHeader` renders ALL-CAPS** but carries the human-cased title as its
  `contentDescription`. Match with `hasContentDescription("Configuration")`.
- **LazyColumn nodes must be scrolled to before they exist.** Use
  `onNodeWithTag("dashboard-list" | "detail-list" | "settings-list")
  .performScrollToNode(...)`.
- **Put new decision logic in `domain/`.** `Assertions`, `AlertDecider`,
  `UrgentAlerts` and `Summary` are pure and exhaustively tested; `CheckEngine`
  only sequences them.
- **Colour means health, nothing else** — with one addition: *muted* now means
  amber. `healthColor()` for content, `healthRim()` for card edges, and the
  dashboard overrides the rim to amber when a monitor is snoozed.
- **Three notification id spaces.** Down (`100_000+`), urgent (`200_000+`) and
  degraded (`300_000+`) are disjoint on purpose, so one monitor can hold all
  three at once and cancelling one never silently eats another.
- **R8: anything reflective needs a keep.** Especially a new
  `@JavascriptInterface` bridge, a new `Worker`, or a new manifest component the
  *launcher* persists a reference to. Smoke-test with `assembleReleaseTest`.
- **Widget rows use `RemoteViews.addView`,** not a collection. Test any change
  with `RemoteViews.apply()` (see `WidgetInstrumentedTest`) — that runs the real
  inflation. A `TextView` inside a `GONE` parent still reports itself `VISIBLE`,
  so a visibility assertion has to stop at the hidden container.
- **Run the on-device suites one class at a time.** Instrumenting everything in a
  single `am instrument` reliably kills this emulator partway through
  `NightbellE2ETest`. Confirmed environmental.

## Next polish ideas

1. Replace the hand-authored baseline profile with a macrobenchmark-generated one.
2. Wear tile (plan above).
3. Import/export of the store JSON, and optional end-to-end-encrypted sync.
4. A custom sound-file picker.
5. Per-monitor tags/grouping once the list outgrows one screen.
