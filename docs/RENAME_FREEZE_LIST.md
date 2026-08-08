# Rename freeze list

Pulse became Nightbell in 2.5.0. The rename was deliberately partial. Everything
below keeps the old name forever, because each one is either persisted on the
device or part of the update path, and changing it costs a user their data, their
alert grants, or their ability to install the next version.

The rule the rename followed:

> Rename capital-`Pulse`. Never touch lowercase `pulse`.

Every persisted identifier in this codebase is lowercase, and every user-visible
string is capitalised, so the rule separates the two cleanly. If you are adding a
new persisted key, keep it lowercase `pulse.` so this stays true.

## Frozen: the update path

| Identifier | Where | Why it cannot change |
| --- | --- | --- |
| `applicationId = "me.river.pulse"` | `app/build.gradle.kts` | Android identifies an app by this string. A build with a different one is a *different app*: it installs alongside, will not update, and the user loses every monitor. |
| `namespace = "me.river.pulse"` | `app/build.gradle.kts` | Paired with the above; also the prefix for the intent actions below. |
| Kotlin package `me.river.pulse` | all of `app/src` | Renaming is safe for the compiler but pointless churn, and it is the namespace the manifest actions are built from. |
| `signingCertDn = "CN=Pulse Monitor, ..."` | `website/site.config.mjs` | A DN cannot be edited without issuing a new certificate, and Android refuses an update signed by a different key. Invisible to users. |

## Frozen: notification channels

Renaming a channel ID makes Android create a **new** channel with default
settings and silently orphan the old one. The user's importance, sound, and their
full-screen and do-not-disturb grants all live on the old ID. On this app that is
the whole product.

| Identifier | Where |
| --- | --- |
| `URGENT_CHANNEL_V2 = "pulse.urgent.v2"` | `data/alerts/AlertCenter.kt` |
| `"pulse.urgent.<style>.<stream>"` derived IDs | `data/alerts/AlertCenter.kt` |
| `GROUP_DOWN = "pulse.group.down"` | `data/alerts/AlertCenter.kt` |
| `GROUP_DEGRADED = "pulse.group.degraded"` | `data/alerts/AlertCenter.kt` |
| `GROUP_RECOVERY = "pulse.group.recovery"` | `data/alerts/AlertCenter.kt` |
| `GROUP_CERT = "pulse.group.cert"` | `data/alerts/AlertCenter.kt` |
| `GROUP_URGENT = "pulse.group.urgent"` | `data/alerts/AlertCenter.kt` |
| `GROUP_HEALTH = "pulse.group.health"` | `data/alerts/AlertCenter.kt` |

## Frozen: stored data

| Identifier | Where | Why |
| --- | --- | --- |
| `preferencesDataStore(name = "pulse_store")` | `data/NightbellStore.kt` | The filename of every monitor the user owns. |
| `preferencesDataStore(name = "pulse_widgets")` | `widget/WidgetConfig.kt` | Widget bindings; a rename empties every placed widget. |
| `"pulse-backup-"` filename prefix | `ui/settings/SettingsScreen.kt` | Backups already on disk must stay importable. |

## Frozen: intents and work

These are referenced by pending intents that already exist on the device, and by
`AndroidManifest.xml` `<action>` filters.

| Identifier | Where |
| --- | --- |
| `me.river.pulse.action.RECHECK` | `AndroidManifest.xml` |
| `me.river.pulse.action.MUTE_1H` | `AndroidManifest.xml` |
| `me.river.pulse.action.ACK_URGENT` | `AndroidManifest.xml` |
| `me.river.pulse.action.WIDGET_REFRESH` | `AndroidManifest.xml` |
| `EXTRA_MONITOR_ID = "pulse.monitor_id"` | `MainActivity.kt` |
| `SWEEP_NAME = "pulse.sweep"` | `data/work/MonitorWorkers.kt` |
| `TAG_MONITOR = "pulse.monitor"` | `data/work/MonitorWorkers.kt` |
| `TAG_SWEEP = "pulse.sweep.tag"` | `data/work/MonitorWorkers.kt` |
| `R.layout.widget_pulse` | `res/layout/widget_pulse.xml` |

## What did change

Class and symbol names (`NightbellColors`, `NightbellIcons`, `NightbellStore`,
`NightbellMonitorService`, `NightbellWidgetProvider`, `NightbellApplication`, `NightbellTheme`,
`Theme.Nightbell`), the two user-visible resource strings, every user-facing string
literal, outbound `User-Agent` values, log tags, `rootProject.name`, and the
matching `-keep` lines in `proguard-rules.pro`.

The proguard rules matter: they name the three classes reflectively constructed by
the platform. If a `-keep` line and a class name ever disagree, R8 strips the
class and the app crashes on launch in a release build only.
