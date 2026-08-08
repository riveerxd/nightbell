# URGENT paging — review

> **Status: fixed in 2.1.0.** Everything below was written against 2.0.0 and is
> kept as the diagnosis. What shipped, and what deliberately did not, is recorded
> in "Outcome" at the end.


Reviewed at 2.0.0 (versionCode 11), targetSdk 36, minSdk 26. Read paths:
`domain/UrgentAlerts.kt`, `data/alerts/AlertCenter.kt`,
`data/alerts/AlertActionReceiver.kt`, `data/check/CheckEngine.kt`,
`data/work/NightbellMonitorService.kt`, `data/work/MonitorWorkers.kt`,
`data/NightbellStore.kt`, `AndroidManifest.xml`.

## Why it does not page today

**P1 — There is no full-screen intent.** `AlertCenter.notifyUrgent` builds a
plain notification on an `IMPORTANCE_HIGH` channel. That buys a heads-up peek
for a few seconds *if the screen is already on and unlocked*, and an ordinary
lockscreen row otherwise. The call-like behaviour — screen wakes, a red surface
takes over the lockscreen — is exclusively `setFullScreenIntent(pi, true)`
plus an Activity marked `showWhenLocked` / `turnScreenOn`. Neither exists.
`USE_FULL_SCREEN_INTENT` is not in the manifest.

**P2 — `setColorized(true)` is silently ignored, so the red container never
renders.** Android honours `setColorized` only for notifications tied to a
foreground service (and media sessions). The urgent notification is
`setOngoing(true)` but is not the FGS notification, so the flag is dropped.
Every `notifyDown` / `notifyDegraded` / `notifyRecovery` call has the same dead
`setColorized(true)`.

**P3 — `setBypassDnd(true)` is a no-op, so Do Not Disturb silences urgent
completely.** That setter requires Notification Policy Access.
`android.permission.ACCESS_NOTIFICATION_POLICY` is not declared and the user has
never been sent to `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`, so the flag is
discarded at channel creation. The KDoc on `urgentChannel` claims DND "can be
configured to let it through" — only true if the user hand-adds Nightbell to their
DND exceptions. Bedtime mode is exactly when a page matters most.

**P4 — The channel on your device is frozen and cannot be fixed in place.**
`urgentChannel()` returns early when `getNotificationChannel(id) != null`, and
Android freezes importance, sound and DND-bypass at creation. `pulse.urgent.*`
has existed on your install since 1.1.0. Any change to importance/sound/bypass
must ship under a **new channel id** (e.g. `pulse.urgent.v2.*`) with the old one
deleted, or it will do nothing on upgrade.

**P5 — The alarm sound fires once, then silence for `urgentRepeatMinutes`.**
Channel sound is one-shot per post. A pager should be insistent.
`FLAG_INSISTENT` is system-only, so looping needs our own
`MediaPlayer`/`Ringtone` on `USAGE_ALARM`, owned by the foreground service or the
full-screen activity, stopped on acknowledge/recover.

**P6 — The repeat loop only exists inside the foreground service, and that
service often cannot start.** `tickUrgent()` — the repeat *and* the
reconciliation sweep — is called from exactly one place:
`NightbellMonitorService.runLoop`. Neither `MonitorWorker` nor `SweepWorker` calls
it. The service is started from `Nightbell.notifyStateChanged() →
NightbellMonitorService.sync()`; on Android 12+ a background
`startForegroundService` throws `ForegroundServiceStartNotAllowedException`,
which `sync()` catches and logs as "Foreground start refused". So with strict
mode off and the phone in your pocket: the first page posts (from the
WorkManager pass), and **no repeat ever fires** until you open the app. The one
promise urgent makes is the one that breaks while you are away from the phone.

**P7 — With strict mode off the first page can be 15–30+ minutes late.**
WorkManager's periodic floor is 15 min and Doze defers it. An urgent monitor
should either force strict cadence or say plainly in the editor that it will not
page promptly without it.

**P8 — Quiet hours swallow urgent by default.** `urgentEligible` honours
`policy.quietHoursEnabled` and only bypasses when `criticalBypassesQuiet` is
on — which defaults to `false`. A user who enabled quiet hours gets *no page at
all* overnight. Defensible as a stance, but the opposite of what the switch
reads as; the urgent toggle should at least prompt for the bypass.

**P9 — `policy.vibrate = false` still vibrates on the first post.**
`urgentChannel` always calls `enableVibration(true)`, while `notifyUrgent` gates
its manual `previewVibration` on `policy.vibrate`. Inconsistent — the channel
should be derived from the policy the way `channelFor` does it.

**P10 — `PRIORITY_MAX` is dead code** on API 26+ (channel importance wins);
minSdk is 26 so it never applies. Cosmetic.

**P11 — `repeatCount` is always `1`**: `applyUrgent` passes `1` for every
REPEAT, so the body reads "Reminder #1" forever instead of counting up. Makes a
working escalation look broken.

## False-alarm review

Correct today, and worth not regressing:

- a cancelled check has no verdict and records nothing (1.6.0)
- offline is not down: `runLocked` and `runAllDue` both gate on `isOnline`, and
  `tickUrgent` pauses the repeat while offline instead of re-asserting
- recovery `CLEAR`s unconditionally rather than from a possibly-stale read
- the reconciliation sweep compares against `getActiveNotifications()`, the only
  way to see an orphaned `ongoing` alert
- per-monitor `Mutex` around `run`/`acknowledgeUrgent`, and every state commit
  wrapped in `NonCancellable`
- checker faults have their own track and channel, so a Nightbell bug is never
  reported as someone's site being down

Remaining risks:

**F1 — `tickUrgent` re-asserts "still down" from stale state.** `down =
runtime.health == Health.DOWN`, with no fresh check. With strict mode off, no
checks are running while the service ticks, so a 5-minute repeat can page 3× off
one observation up to 15 minutes old. A page is a much stronger claim than a
notification row; it should re-check first, or refuse to escalate to full-screen
when the last verdict is older than the repeat gap.

**F2 — Pausing a monitor does not tear its page down.**
`NightbellStore.setEnabled(false)` sets `health = PAUSED, alerting = false` but
leaves `urgentActive = true` and never calls `alerts.cancelAll`. The ongoing,
un-swipeable urgent notification survives until `tickUrgent` next runs — which
needs the service (see P6). `sync()`'s `nagging` check also ignores `enabled`,
so the service stays alive for a paused monitor. Same class as the 1.1.2 orphan,
narrower blast radius.

**F3 — `urgentNotificationId()` is `hashCode() and 0x7FFF`: 32 768 buckets.**
Two monitors can collide onto one urgent notification, and then one monitor's
acknowledge cancels the other's page. At ~50 monitors that is a ~4% chance of a
**silently lost page**. Worth a stored per-monitor id instead of a hash.

**F4 — A denied `POST_NOTIFICATIONS` fails silently.** `post()` returns `false`
and nothing tells the user their pager is dead. A paging feature needs a hard,
visible "URGENT cannot reach you" state covering: notification permission, the
urgent channel being switched off, DND policy access, full-screen-intent
permission, and battery optimisation.

**F5 — Down and urgent both fire for one outage** (separate ids, by design). Not
a false alarm, but two notifications for one event dilutes the signal; the page
should probably suppress the ordinary down alert.

## What the device proved

Posted for real on API 34 and captured with `uiAutomation.takeScreenshot()`;
screenshots in `artifacts/screenshots/urgent-headsup*`, harnesses in
`UrgentHeadsUpDesignTest` / `UrgentHeadsUpRoundTwoTest`. Four things that reading
the docs does not settle:

1. **`setColorized(true)` really is dropped** for a `notify()`-posted
   notification — the card stayed on the system surface colour and only the small
   icon was red. Confirms P2 empirically.
2. **The same builder posted as a foreground-service notification renders the
   whole card in the down colour.** This is the only route to a system-drawn red
   container, and Nightbell already runs a foreground service for exactly the period
   an urgent page is unacknowledged.
3. **`CallStyle` is not demoted** once a full-screen intent is attached, and
   produces the real call container with round Decline/Answer buttons. The button
   labels are system-owned — "Decline", "Answer", "Hang Up" — and cannot be
   reworded, which is the main cost of choosing it. It also combines with
   colorisation: a red call card is achievable.
4. **A custom `RemoteViews` does paint red inside the decoration**, but the
   heads-up slot is about two short lines tall. The first layout was four stacked
   TextViews and the monitor name was sliced in half mid-glyph on the device.
   Needs no permission and no foreground service, which makes it the best
   fallback tier.

Also worth recording: the app's status icon is not usable as a `CallStyle`
avatar. It is a bare 24dp stroke path, and the platform scaled it into a large
white triangle with no container — it read as a broken image. A drawn circular
bitmap fixes it, and it must not be the down colour or it disappears against a
colorised card.

## What a fix looks like

1. `UrgentAlertActivity` — `showWhenLocked`, `turnScreenOn`, `excludeFromRecents`,
   `launchMode="singleInstance"`, hosting the chosen page design.
2. `setFullScreenIntent` on the urgent notification + `USE_FULL_SCREEN_INTENT`
   in the manifest; check `NotificationManager.canUseFullScreenIntent()` and
   route the user to `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` when denied
   (targetSdk 36 means it is **not** pre-granted). Degrade to heads-up + looping
   alarm when it is not granted — never fail closed.
3. New channel family `pulse.urgent.v2.*`, delete `pulse.urgent.*`; declare
   `ACCESS_NOTIFICATION_POLICY` and prompt for policy access so DND bypass is
   real.
4. Looping alarm audio on `USAGE_ALARM` owned by the service, stopped on
   acknowledge / recovery / mute.
5. Drive `tickUrgent()` from `SweepWorker` as well, and re-check before a repeat
   page rather than re-asserting stale state (fixes P6 + F1).
6. `setEnabled(false)` → `alerts.cancelAll` + clear urgent state (F2); stored
   per-monitor notification id (F3); a "urgent can't reach you" banner (F4);
   real `repeatCount` (P11); channel built from policy (P9).

## Outcome (2.1.0)

Fixed:

- **P1 / P2 — the page.** The URGENT page is now the foreground service's own
  notification, built by `UrgentPageStyles.CALL_CUSTOM`. That is not a stylistic
  choice: `setColorized` is honoured only for a foreground-service notification,
  so it is the only place the card renders red at all. Proven by posting the
  identical builder both ways — see `artifacts/screenshots/urgent-final/`.
- **P1 — locked screens.** `UrgentAlertActivity` is wired as a full-screen intent,
  gated on `canUseFullScreenIntent()` and simply omitted when not granted, so the
  notification is never rejected for carrying an intent it may not use.
- **P3 — DND.** `ACCESS_NOTIFICATION_POLICY` declared; `urgentBypassesDnd()` and
  `dndAccessIntent()` expose whether the grant actually exists.
- **P4 — the frozen channel.** New family `pulse.urgent.v2.*`, with the 1.1.0-era
  `pulse.urgent.*` deleted on first use. Without this, none of P3 could reach an
  existing install.
- **P5 — one chime.** `UrgentAlarm` loops the alarm-stream sound and the monitor's
  haptic pattern until acknowledged, owned by the service so it cannot outlive the
  page.
- **P6 — the repeat that never repeated.** `SweepWorker` now calls `tickUrgent()`
  too, so the escalation and the reconciliation sweep no longer depend on a
  service Android often refuses to start from the background.
- **P11 / F1 — honest escalation.** `urgentPageCount` and `urgentSinceAt` are
  persisted, so the page counts reminders and reports downtime truthfully; and a
  repeat re-checks first when the newest verdict is older than the repeat gap
  rather than re-asserting it.
- **F2 — pausing.** `setEnabled(false)` clears urgent state, so pausing a monitor
  ends its page instead of leaving one that cannot be dismissed.
- **F3 — id collisions.** Moot for the paging path: the page lives on the
  foreground-service id, so the 32 768-bucket hash no longer decides whether a
  page is delivered. Still applies to down/degraded notifications.
- **New, found while fixing the above:** `sync()` called `startForegroundService`
  and, on the next state change, `stopService`. Android does not cancel the
  "must call `startForeground`" promise when a service is stopped, so two state
  changes close together killed the process with
  `ForegroundServiceDidNotStartInTimeException`. `sync()` no longer commands a
  stop — the loop already stands down on its own — and the service promotes in
  `onCreate`, before the object graph is built.

Deliberately not done:

- **P8 — quiet hours.** Still suppress urgent unless `criticalBypassesQuiet` is
  on, and that still defaults off. Changing a default silently changes what an
  existing install does overnight; this wants a migration and a prompt, not a
  flipped constant.
- **P9 / P10.** Cosmetic. The urgent channel still always vibrates by design
  (a pager that honours "vibration off" is not a pager) and `PRIORITY_MAX` is
  still dead code on the other tracks.
- **F4 — "your pager cannot reach you".** The capability checks exist
  (`canUseFullScreenIntent`, `urgentBypassesDnd`, `urgentChannelEnabled`,
  `UrgentAlarm.alarmStreamAudible`) but nothing in Settings surfaces them yet.
  This is the most valuable remaining piece of work: today a user whose alarm
  volume is zero or whose DND access is ungranted has a silently degraded pager.
- **F5.** Down and urgent still both fire for one outage.
