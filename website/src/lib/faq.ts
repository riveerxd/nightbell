import { APK_MB } from "./site"
export type FaqGroup = 'before' | 'using' | 'data';

export interface Faq {
  q: string;
  a: string;
  group: FaqGroup;
}

export const GROUPS: { id: FaqGroup; no: string; rail: string; head: string; lede: string }[] = [
  {
    id: 'before',
    no: '01',
    rail: 'Before you install',
    head: 'What it is, what it costs you, and what it refuses to do.',
    lede: `The questions worth having an answer to before a ${APK_MB} MB file is on your phone. Two of these answers are reasons not to install it.`,
  },
  {
    id: 'using',
    no: '02',
    rail: 'Living with it',
    head: 'The parts that are not obvious from the screenshots.',
    lede: 'What it can watch, what it puts on your home screen, and what it does to a battery and a Do Not Disturb schedule.',
  },
  {
    id: 'data',
    no: '03',
    rail: 'Your monitors',
    head: 'Where they live, and how they get to another phone.',
    lede: 'There is no account and no server, so there is exactly one copy of your monitors and it is on the device. That has consequences worth reading before you need them.',
  },
];

export const LANDING_COUNT = 6;

export const faqs: Faq[] = [
  {
    q: 'Does it keep checking when my phone is off?',
    group: 'before',
    a: 'No. The checks run on the phone, so a phone that is off, out of signal or in flight mode is a monitor that is not checking. That is the honest cost of having no server. If you need something watching while the phone is in a drawer, you need a machine that stays awake, and Nightbell is the wrong tool.',
  },
  {
    q: 'Can it check more often than every 15 minutes?',
    group: 'before',
    a: 'Only in strict mode. Android’s PeriodicWorkRequest has a hard 15 minute minimum and WorkManager silently clamps anything shorter, so Nightbell clamps it visibly instead and says so in Settings under Checker health. Turning on strict foreground monitoring runs a foreground service that keeps your interval exactly, down to one minute. It costs a permanent notification and real battery, which is Android’s price for a real cadence and not a design choice.',
  },
  {
    q: 'Is it on F-Droid?',
    group: 'before',
    a: 'Yes, since 13 August 2026, though it can run several releases behind this site, because F-Droid builds on its own schedule. It is also a reproducible build: F-Droid compiles Nightbell from this source on their own machines, compares the result against the APK attached to the GitHub release, and publishes mine only because the two match byte for byte. So the F-Droid download and the direct download carry the same signature, which means either one updates the other and you can move between them without uninstalling and losing your monitors. IzzyOnDroid is a separate repository and Nightbell is not in it.',
  },
  {
    q: 'Why is it not on the Play Store?',
    group: 'before',
    a: 'There is no Play listing, and F-Droid covers the reason most people want one: a store client that installs it and keeps it updated. What keeps it off Play is the foreground service, which declares specialUse rather than dataSync, because from Android 15 dataSync is capped at six hours a day and that cap would quietly break the one guarantee strict mode exists to make. Shipping it through Play review would mean justifying the subtype. Neither F-Droid nor a direct APK is affected, and both are signed with my own key, so a direct install makes Play Protect ask you to confirm the first time.',
  },
  {
    q: 'Does anything leave my phone?',
    group: 'before',
    a: 'Three things, and this answer used to say two, which was wrong for as long as the app has had an update check. First, the check itself, to the service you pointed it at. Second, a connectivity probe to a reference endpoint, so losing signal in a car park is not reported as every service going down at once: an empty 204 carrying nothing about you, pointed at GrapheneOS rather than an advertising company by default, repointable at your own always-up host, and switchable off in Settings under Checks. Third, once every six hours, the version check. It reads a small file from nightbell.app and says which version it is running, and I count those requests, because it is the only way to find out how many copies of this app exist. The log line holds a timestamp, a status and that version string. It holds no address, no identifier and no hash of either, so nothing in it can be tied to a phone or joined up across two days, and there is no per install id anywhere in the app to log even if I wanted one. Turn it off with "Tell me about new versions" in Settings, or leave it on and point it at GitHub if you would rather a stranger got the request. Beyond those three, only what you point it at yourself, such as a webhook or your sign-in provider: no analytics library and no crash reporter in the dependency list, a permission list short enough to read in one go, and site icons fetched from the monitored origin rather than through an icon proxy. Your service sees the request and your IP, the same as any other client on your network would. What never happens is anybody seeing your tokens, your URLs or your uptime history.',
  },
  {
    q: 'Can I watch something on my home network, or over plain HTTP?',
    group: 'before',
    a: 'Yes. Cleartext traffic is allowed on purpose, so a box on your LAN answering on http:// is a valid target. The catch is the same one everywhere else: the check comes from the phone, so the phone has to be able to reach the host. A monitor on a LAN address stops passing the moment you leave the house, which is a true fact about your network rather than a bug.',
  },
  {
    q: 'What happens to my monitors when I update?',
    group: 'data',
    a: 'Anything from 3.0.1 onward installs over the top and keeps your monitors, history and settings. 3.0.0 is the exception: 3.0.1 is signed with a new key, because the old certificate’s subject still carried the previous name and a subject cannot be edited, so a 3.0.0 install has to be uninstalled rather than updated. Coming from Pulse 2.x is different again: 3.0.0 renamed the app and moved its application id, which Android treats as a different app entirely, so it installs beside the old one with an empty data directory rather than upgrading it. The route across is Settings, Backup and transfer in 2.4.3, then import on the new install. Export before uninstalling anything, because once the old app is gone so is its data. Placed widgets and your notification channel settings do not come across either.',
  },
  {
    q: 'How is this different from UptimeRobot or Uptime Kuma?',
    group: 'before',
    a: 'Both are better than Nightbell at being a monitoring service: they keep watching when your phone is in a drawer, they hold years of history, they have status pages. Nightbell is not trying to win that. It is trying to be the thing that wakes you at 3am without a subscription, a server or an account, by running on hardware you already carry and paging you with a local alarm instead of a push notification. If you already run Uptime Kuma, Nightbell is a reasonable pager to put in front of it.',
  },
  {
    q: 'Will the alarm really get through Do Not Disturb?',
    group: 'using',
    a: 'Once you grant Do Not Disturb access, yes: urgent alerts go to a dedicated DND bypassing alarm channel. One thing to know first, because it is the current known bug rather than a feature: quiet hours still suppress urgent pages unless you turn on the bypass. That default is wrong and is the next thing on my list, with a migration rather than a flipped constant.',
  },
  {
    q: 'What does it do to my battery?',
    group: 'using',
    a: 'With the default WorkManager scheduling, close to nothing. Strict foreground monitoring costs real battery, because it wakes for every due check instead of letting Android batch them, and it holds a permanent notification while it does. An unacknowledged urgent outage starts that same service on its own regardless of the setting, and stops it the moment you acknowledge or the monitor recovers.',
  },
  {
    q: 'Can it watch one element on a page rather than a status code?',
    group: 'using',
    a: 'Yes, and that is the check worth knowing about. Enter a URL, tap Open live preview, and the page loads in a real WebView you can browse and scroll. Dismiss the cookie banner or sign through the age gate with tap-to-select off, turn it on when the view you want is on screen, then tap the thing you care about. What gets stored is not a CSS selector: injected JavaScript derives a signature that falls back in order from an id, to a stable data attribute, to the shortest unique CSS path, to an absolute XPath, to a text fingerprint, and every later check re-resolves it in that same order, so a cosmetic markup change degrades instead of waking you at 3am. If the pick was made behind a gate, the cookies and localStorage the preview ended up holding are stored with the monitor and put back before each check, and that captured session is treated as a credential: never logged, never shown, and never written into an export unless you asked for secrets in the same breath.',
  },
  {
    q: 'Is there a home-screen widget?',
    group: 'using',
    a: 'Yes, worst monitor first, and it is configurable per instance rather than per app, so two widgets on the same screen can show different things. Three solid presets, black, white and blue, plus Custom with your own background and text colours and a transparency slider that goes all the way to clear. Compact density is a dot, a name and a status; detailed adds the host, the latency and the failure message. The mark, the word Nightbell, the fleet summary and the settings cog each switch off on their own. Monitor count is automatic by default, which fills whatever size you dragged the widget to, and anything that does not fit is disclosed as "+N more" rather than quietly dropped.',
  },
  {
    q: 'Can I move my monitors to a new phone?',
    group: 'data',
    a: 'Settings, About, Backup and transfer writes every monitor, its sample history and your settings to a JSON file through the Storage Access Framework, so the app needs no storage permission, you pick where the file goes, and nothing is uploaded anywhere. Import on the other device. Two things to know before you do it. An import replaces what is on the device rather than merging with it, and it says so before it acts. And placed widgets do not come across, because a launcher stores the widget provider as a fully qualified class name that belongs to the old install, so drag them back on afterwards; their settings are in the backup. Health resets to unknown until the new install has actually checked something, which is deliberate: carrying an in-progress alert state across would suppress the first real outage on the new phone.',
  },
  {
    q: 'Can I see what the app actually did, when something looks wrong?',
    group: 'using',
    a: 'Settings, About, Diagnostic log. It records what Nightbell is doing to a text file you can export and attach to an issue. The switch governs the file and nothing else: the logcat stream and a 500 line ring in memory are always on, and the ring never touches disk. A crash is recorded whichever way the switch is set, to a file of its own carrying the stack trace and the ring, because a crash cannot be reproduced on request and a switch you had to turn on beforehand would have caught nothing the first time. The file caps at 192 KB with one rotation, the switch is off on a fresh install, and it does not travel in a backup, because it is a decision about one device rather than about a fleet.',
  },
  {
    q: 'Where does it keep my monitors on disk?',
    group: 'data',
    a: 'One JSON document in DataStore, inside the app private directory, with forward migration when the shape changes. No database server, no sync service, no cloud copy, and no account that could hold one. That is also why the backup file is the only way a monitor gets off the device: there is no other copy of it anywhere. The practical consequence worth knowing is the one every no-server design has, which is that losing the phone without an export means losing the monitors.',
  },
  {
    q: 'Is it actually free?',
    group: 'before',
    a: 'Apache 2.0, the whole thing, source included. No paid tier, no account, no upsell, nothing to cancel. Same licence as everything it depends on, so forking it or lifting a piece of it is straightforward. Keep the notice and say what you changed.',
  },
];

export const faqPageLd = (id: string, entries: Faq[]) => ({
  '@context': 'https://schema.org',
  '@type': 'FAQPage',
  '@id': id,
  mainEntity: entries.map((f) => ({
    '@type': 'Question',
    name: f.q,
    acceptedAnswer: { '@type': 'Answer', text: f.a },
  })),
});
