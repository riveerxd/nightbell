# 3.0.0: Pulse became Nightbell

3.0.0 renames the app and moves it to a new package. **It does not update a 2.x
install.** This page is what to do about that, and the record of what changed
underneath.

## For anyone running 2.x

Android identifies an app by its `applicationId`. 2.4.3 is `me.river.pulse` and
3.0.0 is `me.river.nightbell`, so to the platform these are two unrelated apps.
Installing 3.0.0 does not upgrade anything: it lands beside the old app with an
empty data directory. No signing key, manifest setting or store listing changes
that.

The route across, in order:

1. In **Pulse 2.4.3**, Settings → Export. It writes one JSON file wherever you
   point it.
2. Install **Nightbell 3.0.0**.
3. In Nightbell, Settings → Import, and pick that file.
4. Check the monitors arrived, then uninstall Pulse.

Two things do not come across:

- **Placed widgets.** A launcher stores a widget provider as a fully-qualified
  `ComponentName`, so the old ones are pointing at an app that is about to be
  gone. Remove and re-place them.
- **Notification channel settings.** Your importance, sound and the four grants
  live against the old package's channels. Nightbell asks for them again on first
  launch through the setup screen.

Backups written by any 2.x build import fine. The reader validates the envelope's
`format` field and never looks at the filename, so a file still called
`pulse-backup-2026-08-04-0031.json` is read exactly as it always was.

## Why the name changed

Pulse was unwinnable as a search term. Pulse Pager and Pulse UpTime both ship
uptime monitoring under the same word, with two more products alongside them, so
searching for this app returned four competitors first. Nightbell collides with
nothing in software.

## Why the internal identifiers moved too

2.5.0 renamed everything user-facing and deliberately froze every persisted
identifier: the DataStore names, the notification channel ids, the WorkManager
unique names, the backup prefix. That was correct at the time, because the package
was staying put and renaming a channel id silently discards the importance, sound
and do-not-disturb grants attached to it. On this app that is the whole product.

3.0.0 moves the package, which removes the thing being protected. A new package is
a new sandbox, so there is no live install whose channels could be orphaned. At
that point keeping `pulse.*` keys inside an app called Nightbell would mean paying
the confusion forever to protect a migration nobody receives. So they moved:

| Was | Is |
| --- | --- |
| `me.river.pulse` | `me.river.nightbell` |
| `pulse_store`, `pulse_widgets` | `nightbell_store`, `nightbell_widgets` |
| `pulse.urgent.v2` and its per-style ids | `nightbell.urgent.v2`, same derivation |
| `pulse.group.{down,degraded,recovery,cert,urgent,health}` | `nightbell.group.*` |
| `pulse.monitor_id`, `pulse.monitor`, `pulse.sweep` | `nightbell.*` |
| `pulse-backup-` written prefix | `nightbell-backup-` (old files still read) |
| `pulse://monitor`, `pulse://widget` deep links | `nightbell://…` |
| `R.color.pulse_ink`, `R.layout.widget_pulse` | `nightbell_ink`, `widget_nightbell` |
| `keystore/pulse-release.jks` | `keystore/nightbell-release.jks` |

## 3.0.1 changed the signing key

3.0.0 still shipped a certificate whose subject read `CN=Pulse Monitor`, because a
subject cannot be edited without issuing a new certificate. 3.0.1 issues one:

```
CN=Nightbell, OU=river, O=river, L=Prague, C=CZ
SHA-256  20:d8:ab:da:a8:41:6a:9a:75:1e:3e:a1:44:ef:15:23:d7:dd:ba:ae:ee:9e:c6:be:01:d6:3a:65:57:4a:70:de
```

Android refuses an update signed by a different key than the installed build, so
**a 3.0.0 install cannot update to 3.0.1.** Uninstall 3.0.0 first, exporting your
monitors beforehand if you had already imported them. This was done an hour after
3.0.0 shipped, deliberately, because the cost only grows.

The old key is archived at `keystore/pulse-legacy.jks` and still verifies every
release up to and including 3.0.0. It is never used again.

## What deliberately did not change

- **The archived key's alias, `pulse`.** An alias names a key inside a keystore, so
  changing it would mean a different key again. `keystore/pulse-legacy.jks` keeps
  both the old key and the old alias so it can still verify what it signed.
- **`VibrationStyle.DOUBLE_PULSE`** and its `"Double pulse"` label. That is a
  haptic pattern, two pulses, and has nothing to do with the old name. It is also
  a serialised enum name.
- **`docs/brand/archive/pulse-directions/`.** Thirty logo directions from when the
  app was called Pulse. Kept unchanged, because they record a real decision about a
  different name.

## The mark

The icon is a bell with a heartbeat trace knocked out of it. The trace is the same
six points the Pulse mark drew, scaled and centred inside the bell rather than
redrawn, so the old identity is still in there. See `docs/brand/README.md`.
