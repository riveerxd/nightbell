
This page describes Nightbell 3.18.0. Settings is split into four tabs, Alerts,
Checks, Look and About, and a path such as "Settings, Checks, Checker health"
names the tab and then the card.

## What it monitors

| Kind | What it does |
| --- | --- |
| **Status check** | Requests a URL and asserts on the status code: Exact code, Any 2xx, Range or Any response. An optional response-body assertion can be added. |
| **Request & response** | Full control: GET, POST, PUT, PATCH, DELETE or HEAD, custom headers, a request body and content type, plus the same status and response-body assertions. |
| **Page element** | Loads the real page in an embedded browser and watches **any number** of DOM nodes you picked by tapping them, all resolved against one page load. |
| **Prometheus** | Reads a metrics endpoint, a PromQL query or an Alertmanager. Three sources, one kind. See below. |
| **Health endpoint** | Reads a service's own report of itself in the health report format: a verdict, each part, the rules that decided it and the evidence. Signs in the way the service's own clients do, including single sign-on. See below. |
| **GitHub repo** | Watches a repository over the public API: stars, issues, pull requests, comments, releases and how often the release files are downloaded. See below. |

### Setting up a monitor

On an empty dashboard, **Watch something** offers five starting points that fill
in the setup screens and save nothing until you do, plus **Start from scratch**:

| Template | What it fills in |
| --- | --- |
| A website | Status check, Any 2xx, every 15 min |
| A health endpoint | Request & response, GET, Exact 200, JSON field `status` equals `ok`, `Accept: application/json`, every 5 min, URGENT on with a 5 min repeat |
| An API, with a budget | Exact 200, a 1.5 s latency budget, every 15 min, slow alerts on |
| A GitHub repository | every 15 min |
| Something on a page | every 30 min, 30 s timeout |

The health endpoint template is an ordinary Request & response monitor reading a
JSON field. It is not the [Health endpoint](#health-endpoint) kind, which reads a
whole report in its own format.

The URL field does not ask for a scheme. A bare host is finished as `https://`
when you leave the field, and says so; `.onion` and `.i2p` addresses get
`http://`, because they have no certificate to check. Type `http://` yourself and
it is left alone, with a warning that the traffic is not encrypted.

| Setting | Range | Default |
| --- | --- | --- |
| Check every | 1 to 1440 min | 15 min (Settings, Checks, Default interval) |
| Timeout | 1 to 120 s | 15 s (Settings, Checks, Default timeout) |
| History kept | 10 to 300 checks | 60 |
| Status code | 100 to 599 | 200 |

Every number that sits between a minus and a plus can also be typed: tap it and
it becomes a field with a keypad. While you type it says the range it takes, in
amber when the value is outside it ("Highest is 1440m"), and leaving the field
lands on the nearest end. Clearing the field leaves the value where it was.

Intervals under 15 minutes are accepted and labelled best-effort, for the reason
given under [How background checks actually run](#how-background-checks-actually-run).

**Follow redirects** is on by default; off, a 3xx is reported as it came back.
Requests carry `User-Agent: NightbellMonitor/1.0 (Android)` unless a header of
your own replaces it, and `Cache-Control: no-cache`. The body is read up to
512 KiB for assertions.

A keep-alive connection the far end had already closed, or one that broke while
it was being set up, gets exactly one more attempt before anything is reported.
That retry never happens for POST or PATCH, never after a timeout, and only when
the failure came in under half the timeout.

### A GitHub repository

The one kind that is mostly not an uptime check. Being turned away by GitHub's
rate limit (a 429, or a 403 with no budget left or a `Retry-After`) is recorded
and shown on the card, and never touches health or any alert track, because it
means Nightbell learned nothing about the repository. Every other failure is a
real failed check and goes through the ordinary down alert: a repository that
was deleted or made private (404, "Repository not found, or not visible to this
token"), a token that was refused (401), a 5xx or a network failure.

Five tracks, each switched on separately:

| Track | What arrives |
| --- | --- |
| Stars | Every increase, or only round numbers (10, 25, 50, 100, 250, 500, 1k, 2.5k, 5k, 10k, 25k, 50k), or one summary an hour or a day. A count that falls is never reported as growth. |
| Issues and pull requests | New ones, optionally filtered to a keyword or an author. Pull requests are a separate switch, off by default, because the issues endpoint returns both and letting them through is the commonest way a repository monitor cries wolf. |
| Comments | Replies on any thread, closed ones included. Off by default: it is a fourth endpoint and the loudest of the five. Comments from bots are off, and there are "Never from" and "Only if it mentions" filters. |
| Releases | A new release, once, with its tag. Prereleases are a separate switch. |
| Downloads | How many times the release files have been fetched. Off by default. |

The first check of each track writes down where the repository stands and says
nothing, so you are told about what happens next rather than about everything
that already happened. Each track records its own first sighting, so switching
one on later does not announce a backlog.

A check makes one request per endpoint it needs, four at most, plus up to two
more pages of releases when downloads are counted across every release of a
repository with more than 100 of them.

**Rate limits.** GitHub allows 60 requests an hour per address without a token
and 5,000 with one, which is why the template's interval is 15 minutes and why
repeat requests carry the `If-None-Match` from the last answer, so a check that
finds nothing new costs nothing. A personal access token can be saved in
Settings, Checks, GitHub, or on the GitHub step of setup. Repository monitors
also stay on their schedule when a check fails, rather than being retried
quickly, because they spend that shared limit.

#### Counting downloads

GitHub publishes a download count per release file. Nightbell can total the
newest release, or every release the repository has, and can narrow both to the
files you care about: a project shipping an APK beside a signature and a checksum
can count the APKs alone.

Files are matched by extension or by a wildcard, comma separated, and an empty
filter counts everything. Because the filename carries the version and therefore
changes every release, the per file breakdown groups by the name with its digits
replaced: `Nightbell-3.13.0-release.apk` and every other release of the same file
add up under `nightbell-*-release.apk`.

It adds no request on a repository whose releases fit one page. The counts live
inside the release payload, so the call is the one the release watcher was making
anyway, asked for in full. That does make it a bigger response, which is what the
refresh setting is for: **Every check**, **Once an hour** or **Once a day**. With
the data saver on, which is the default, the full call is made at most once an
hour whatever that setting says. A tapped re-check is not held back.

You can be told when the count moves, on any increase or only at round numbers,
or held to one line an hour or a day. Nothing about a download wakes the phone.

Past 300 releases the walk stops and the total is shown as a floor, with the
number of releases it covers, rather than quietly undercounting. Drafts never
count, because nobody but the maintainer can reach them.

**What the number is.** GitHub counts requests for the file, not people. Mirrors,
build servers and crawlers that fetch a whole release are all in it, and the
counter is not updated the moment somebody downloads. Nightbell reports the
movement it sees between two checks and never claims a person did anything.

### Prometheus, PromQL and Alertmanager

One kind with three sources, because the three differ only in which URL is
fetched and how the body is read. The sign-in, the cadence, the TLS trust and
the alert policy are the monitor that already existed.

| Source | Asks for | Healthy when |
| --- | --- | --- |
| **Metrics** | The exporter's own URL, exactly as entered | Every series matching the name and label filter satisfies the comparison |
| **PromQL** | The server's base URL, plus `/api/v1/query` which Nightbell appends | The query returns nothing, returns something, or every value it returns passes the comparison |
| **Alerts** | The Alertmanager's base URL, plus `/api/v2/alerts` which Nightbell appends | No alert past the severity and label filters is firing |

**Comparisons:** above, at least, below, at most, equals, not equal. Written as
what healthy looks like, the way an expected status code is the one you want
back, so a page fires when the value leaves the range. A new metrics monitor
starts at "Below 1".

**Label filters** use PromQL's own syntax and all four of its operators:
`mode="idle"`, `cpu!="0"`, `device=~"sd.*"`, `fstype!~"tmpfs|overlay"`. Regexes
are anchored, as Prometheus anchors them. The field also takes pasted braces,
unquoted values and a trailing comma, because it is a text field a person types
into.

Two failures are worth calling out because they are the ones that would
otherwise be silent. A metrics filter that matches **nothing** fails the check,
since a metric that has stopped being exported is not good news. And a sample of
`NaN` never counts as healthy, whichever comparison is set, because `NaN` means
the exporter had no reading rather than a reading of zero.

A default PromQL monitor is healthy while its query matches no series, which is
how a Prometheus alerting rule is written: the expression *is* the alert
condition, and anything it returns is the page. Prometheus answers a query it
refuses with HTTP 200 and `status: "error"`, so that case is reported as a
configuration problem naming what the server said, rather than as an outage.
A query over 1,800 characters gets a warning at setup.

Alertmanager is asked for active alerts only, both through the request's query
parameters and again on the way in, because some versions and some gateways in
front of them ignore the parameters. Silenced and inhibited alerts stay out by
default (**Count silenced alerts** and **Count inhibited alerts** bring them
back): somebody has already said they know. The severity floor is Any, Info,
Warning or Critical. An alert carrying a severity Nightbell does not rank, or
none at all, passes every severity floor, since hiding what cannot be ranked
would hide outages to keep a filter tidy.

**Finding the metric:** "Browse this endpoint" reads the URL and lists what is
actually exposed, with a filter box over it. A name with one series shows its
current value; a name with several shows the count and the labels that separate
them. Picking one fills the metric field, and fills the label filter too when
there is exactly one series, because then its labels are not a choice. This is
the same problem the element picker solves and the same answer: the exact string
is written down in one place, which is the thing being monitored.

**Seeing the alerts:** what is firing is shown one alert to a row on every
surface that has room for it. The page in the shade draws a row per alert up to
seven and then says how many more, the dashboard card names the first three and
counts the rest, and the detail screen lists them all: worst first and oldest
first inside a severity, each with its severity, the label that separates it
from the others firing on the same rule, its summary annotation and how long it
has been going. Critical is rose, warning is amber, and a severity Nightbell does
not rank is neutral rather than guessed at.

Tap a row on the detail screen and it opens on the `description` annotation, the
time it started and the labels Alertmanager sent with it (up to twelve, without
`alertname` and `severity`, which are already on the row), which is what tells
two alerts on one rule apart. Fifty rows are kept per monitor and the header
says so when there were more, since the list is written back to storage on every
check. The list is replaced on every check and cleared by a check that could not
reach Alertmanager, because a list of what is firing is only true as of the
moment it was read.

**Signing in:** a username and password for HTTP basic auth, which is what
Grafana Cloud, Mimir and a reverse proxy in front of Prometheus take. Anything
else goes in the monitor's headers: a bearer token, a Mimir `X-Scope-OrgID`, an
API gateway's own key. The password is never shown after it is typed, never
logged, and is stripped from an export unless secrets were explicitly included.
Basic auth over plain http gets a warning.

A healthy Prometheus monitor shows what it last read on its dashboard card,
because the number is the thing it exists to watch. Every other kind's passing
message is the round trip, which is not worth a line.

### Health endpoint

A status code tells you the server answered. It does not tell you that card
payments have been failing for four minutes while everything else is fine. Only
the service knows that, so this kind lets it say so.

The service serves one JSON document in the health report format. Its top-level
`status` is the verdict, and the verdict is what pages. Components explain it:
each part has its own status, the conditions that decided it (what was
measured, the observed value and the rule it was held to, such as `>= 10` or
`degraded when > 500, down when > 2000`) and a log of what the service saw on
the way. The full format, the field limits and a drop-in ASP.NET Core writer are
in the [health report format](/docs/health-format).
The shortest valid document is `{ "healthReport": 1, "status": "up" }`.

| Status | Means | Also accepted, in any letter case |
| --- | --- | --- |
| `up` | Working as intended | `ok`, `pass`, `healthy` |
| `degraded` | Working, but worse than it should be: slow, backed up, on a fallback | `warn`, `warning` |
| `down` | Not doing its job for some or all users | `fail`, `error`, `unhealthy` |

Any other word is read as down, with the word quoted, because a status Nightbell
cannot read is not good news. Down is headlined by the report's `message`, or
else by the worst part's. Degraded is amber and goes through the same cooldown
and repeat as a reading over the latency budget, and it still counts as up for
uptime. If `status` says up while a component says down, Nightbell believes
`status` and notes the disagreement on the detail screen: the service knows
which of its parts are critical, Nightbell does not.

#### Reading the response

The body decides, not the HTTP status. A `503` carrying a report is read as that
report, because many health libraries answer `503` when unhealthy and the body
is where the useful part is. A `2xx` whose body is not the format fails the
check as "Not a health endpoint", with the first 200 characters of the body
shown; the usual cause is a deploy that replaced the endpoint, or a web app's
catch-all route answering `200` with its own HTML for every path. A `404`, any
other `4xx`, a `5xx` without a report, a timeout, a refused connection and a TLS
failure are failed checks, as for any other monitor.

Bodies over 256 KB are not read. Across the whole report the first 200
conditions and the first 200 log entries are kept, in document order, and at
most 50 parts, 20 rules a part and 50 log entries a part, because the report is
stored again after every check. Each check that reads a report replaces the
last one, and a check that reads none clears it.

**Treat as down if the report is older than** uses `checkedAt`. It is compared
with the `Date` header of the same response, which comes from the server's own
clock, so a phone with the wrong time cannot make a fresh report look stale.
Without a `Date` header the phone's clock is used, with two minutes of
tolerance. A timestamp that cannot be read is ignored and noted, never a failure
on its own.

A body that is just `Healthy`, `Degraded` or `Unhealthy`, which is what ASP.NET
Core's `UseHealthChecks` returns by default, is read as up, degraded or down
with no parts. An existing ASP.NET health check can be set up, signed in and
tested before anyone writes any JSON.

Whatever the service writes in `message`, part names, log messages and log data
can show up on a lock screen and in any webhook the monitor posts to, exactly as
it was written. Nothing in the report is scrubbed on the way, so secrets and
personal data have to stay out of it at the source.

#### Signing in to the endpoint

The endpoint can sit behind the same protection as the rest of the API, and
Nightbell signs in the way the service's other clients do. When you type the
address, setup tries it once without signing in, so it can tell you whether a
sign-in is needed at all.

| The API expects | Choose |
| --- | --- |
| Nothing | None |
| A fixed key in a header (`Authorization: Bearer …`, `X-Api-Key: …`) | Headers |
| HTTP basic auth | Basic |
| An access token from an OpenID Connect provider (Keycloak, Auth0, Entra ID, Authentik, Zitadel and others) | Sign-on |

Single sign-on keeps its own session, refreshes the access token before it
expires, and sends it as `Authorization: Bearer <token>` on every check. There
are four ways in:

1. **Through the web app.** Nightbell opens the web app in a WebView and you tap
   its own Sign in button. The provider, the client ID and the return address
   are read from that sign-in, so nothing is typed. Nightbell then makes its own
   PKCE request with `offline_access` and `prompt=login`, so the session can
   outlive a browser session where the provider allows it. The callback page is
   never allowed to load, so the web app cannot spend the code first. Google
   blocks its sign-in page inside apps; for Google, paste a token.
2. **Username and password**, for a client that allows the OAuth password
   grant. The password is kept and used to sign in again when the session ends.
3. **Service account**, for a confidential client with client credentials: the
   client ID and secret.
4. **Paste a token**: a refresh token from a session you already have. The
   provider and client are read from the token, and logging out of that session
   ends Nightbell's too.

The API has to accept a bearer token issued by the provider to the client the
web app uses, or to the service account. If it accepts the tokens the web app
sends, it accepts these; if it requires a role, the account Nightbell signs in
with needs that role. Changing the monitor's address to a different site, such
as staging to production, forgets the provider learned from the old one and
asks for a sign-in through the new one.

Tokens, the password and the client secret live in a credential vault outside
the rest of the monitor's data, encrypted with an AES-256-GCM key in
AndroidKeyStore that needs no user authentication, so checks run while the phone
is locked. They never go into a backup. A refresh and the vault write are one
step that cannot be cancelled, so a check cancelled just after the provider
rotated a token cannot lose the new one.

Sign-in trouble is kept apart from outages, because Nightbell's own access is
not the service's health:

| Situation | Outcome | Pages |
| --- | --- | --- |
| Not signed in, session ended, password or secret refused, vault unreadable | No verdict. The card says "Not checking · Sign in needed" in neutral grey, and a notification on "Monitors that can't sign in" is posted when it starts and then once a day | No |
| Provider unreachable or answering 5xx | Down, "Sign-in server unreachable": if Nightbell cannot sign in, neither can your users | Yes |
| Fresh token, API answers 401 after one forced refresh and retry | Down, "Signed in, but the API refused the token" | Yes |
| Fresh token, API answers 403 | Down, "Access refused (403)", with a hint about a missing role | Yes |

A refused password or client secret is tried once per change and never on a
schedule, so it cannot trip a provider's brute force lockout.

#### Testing it and reading the report

**Test** in setup shows the verdict and one line for each part that is not up.
It is not the full report. After you save, the monitor screen carries a card
with all of it:

- A line naming the service, version and environment the report gave, and when
  it was reported.
- **Components**, or "Showing 50 of 64 parts" when the report had more than are
  kept. With more than one part, a bar split by status and a line such as
  "1 down, 1 degraded, 1 up", or "All 3 up".
- One row per part, worst first and then in the service's own order. Only the
  worst part starts open, and only when it is not up: its message, since when it
  has been in that state ("Down since 13:58"), how long its own check took, its rules with the
  observed value against each, and its log. The others open with a tap.
  Opening every failing part at once turned one bad night into a wall of rules
  with the part names lost in it.
- For a monitor that signs in, the provider, client, account, how long the
  session lasts and when it was last refreshed. When the sign-in has ended the
  card says "Not checking · Sign in needed", that nothing is sent to the
  endpoint until you sign in again from Edit, and that this says nothing about
  whether the service is up.

### Assertions and element expectations

**Response-body assertions:** contains, does not contain, exactly equals,
matches regex, JSON field equals, JSON field exists. JSON paths support nesting
and array indices (`data.items[0].state`). **Case sensitive** is off by default.
Exactly equals ignores leading and trailing whitespace.

**Element expectations:** element exists, element is gone, text equals, text
contains, text unchanged (snapshot). You can also compare an attribute (`href`,
`value`, `data-state` and so on) instead of the visible text. Text equals and text
contains ignore case and collapse whitespace; text unchanged is exact.

Each watched element carries its own expectation and, once there are two or
more, an optional nickname. The list is a **conjunction**, one mismatch marks the
monitor down, and the alert names the element that broke. Every element is
resolved against the same single page load, so adding one does not add a load;
past eight, setup notes that the check gets slower to settle.

When a page check fails before the element is even looked for, it says which
part failed: "Can't resolve the host", "Couldn't connect to the page", "The page
never answered", "The page's certificate was refused" or "That address can't be
loaded". A timeout names the stage it stopped at, for example "The page loaded
but the element never appeared".

### Certificates and trust

The **Certificate** setting on a monitor has three choices, shown for an https
address (or an http one that follows redirects) and never for a GitHub repo:

| Choice | What it accepts |
| --- | --- |
| **System CAs** (default) | Anything the phone trusts, including certificate authorities you installed on the phone yourself |
| **Pinned key** | Records the server's key on the next successful check, then requires exactly that key. A change fails with "The certificate key changed", and the monitor screen offers **Trust the new key** |
| **Any certificate** | Anything at all. Shown in rose wherever the setting is summarised |

Pinned key is what a NAS, a homelab box behind a private CA or a Tor hidden
service needs. An `.onion` or `.i2p` address left on System CAs gets a warning
suggesting it.

**Expiry warnings** live under Settings, Alerts, TLS certificates: **Warn before
certificates expire** (on), **Start warning** 14 days before (1 to 90) and
**Urgent below** 2 days (0 to 30). A warning is posted once for each threshold
crossed and then at most once a day, never wakes anyone, and respects mute, the
master switch and quiet hours. The card shows a tag such as "cert 9d", "cert
today" or "cert expired".

A page element monitor cannot see its page's certificate through the browser, so
it has its own opt-in switch, **Watch certificate expiry**, which reads the date
with one extra HEAD request a day.

### Routing through a SOCKS5 proxy

For a Tor or I2P daemon running on the phone, so a hidden service can be watched
without putting the whole phone into VPN mode. Settings, Checks, SOCKS5 proxy:
**Offer a proxy to monitors** (off), **Proxy host** (127.0.0.1), **Proxy port**
(9050) and **Allow routed checks** 60 s (5 to 180), because a routed check is
slow.

Each monitor then has **Route through SOCKS5**, with an optional proxy host and
port of its own (Tor uses 9050, I2P's SOCKS port is 4447), which is what watching
an `.onion` and an `.i2p` service at the same time needs, and its own timeout when
routed. The proxy resolves the name, so it never reaches the phone's own DNS. A
monitor set to route with no usable proxy fails the check rather than going out
directly. An `.onion` or `.i2p` address that is not routed gets a warning at
setup.

Page element monitors and the live preview are routed too. Android's WebView
proxy setting belongs to the whole app, so routed page loads run one at a time;
a routed check that cannot get its turn within 15 seconds fails with "Another
routed page load is in progress" rather than stalling the others. The preview
refuses to open a hidden service directly, and refuses a routed monitor with no
proxy address, because looking the name up leaks it whether or not the page then
loads. GitHub monitors can be routed as well.

### Page checks and mobile data

**Data saver**, Settings, Checks, Background checks, is on by default. Page
element checks load from cache, skip fonts, media, analytics and ad hosts and
video or map embeds, keep stylesheets, and still ask the server for the page on
every check. Each page monitor does one full load a day with no cache, so a broken
file on the site cannot hide behind the cache for longer than that. The switch
shows an estimate of what your monitors cost a month with it on and off. Turn it
off if a page check has to see exactly what a first time visitor sees on every
load.

**Wi-Fi only**, beside it, skips checks on metered mobile data.

## The dashboard

The banner at the top is the fleet's verdict: "All 6 operational", "1 of 6 is
down", "2 of 6 are down", "1 of 6 is slow", "All 6 paused" or "No monitors yet",
with the uptime over the past 24 hours. Offline it reads "Checks paused" and the
button underneath says "Waiting for a connection".

**Check all now** re-checks everything. So does pulling the list down and
holding for two seconds while a ring closes; letting go early cancels.

**Pause monitoring** stops the whole fleet for 30 minutes, 1 hour, 4 hours,
8 hours or "Until I resume". It is for the walk with one bar of signal, where
every check times out at once. A pause does one of two things:

- **Stop checking** runs nothing, so nothing false lands in the uptime history.
  This is the default.
- **Stay silent** keeps checking and keeps the dashboard live, and only the
  alerts stop.

Settings, Alerts, Pause button picks which one the button uses, or **Ask me**.
The banner shows "PAUSED · 2H LEFT" or "SILENT · …" with a **Resume
monitoring** button, a timed pause lifts itself, and any pause also stops urgent
repeats and every webhook.

**Filter and sort.** Show All, Problems, Healthy, Paused or Unacked; order by
Worst first (the default), My order, Name, Slowest, Just checked, Least recent or
Repos first, which puts repositories above everything else and keeps worst first
inside each part. **Search monitors** matches the name, the address, the kind and
element nicknames, ignoring case.

**Rearranging.** Hold a card and drag it. Dropping it somewhere new switches the
order to My order, which then stays put instead of being re-ranked on the next
check. Dragging is off while a filter or a search is narrowing the list. Screen
readers get Move up and Move down actions.

**Selecting.** A hold that does not move selects the card. The bar that comes up
offers Select all, Pause or Resume (whichever fits what is selected), **Mute 1h**,
grouping, removing from a group, and a held **Hold to delete these N monitors**
set apart below a rule. A deleted monitor can be brought back from the **Undo**
on the message that reports it for five seconds, back in its place, its group and
with its history.

**Groups.** A group is one card with one verdict, worst member first: "All 2
operational", "1 of 2 is down" and so on. Pausing one member never decides it
unless every member is paused, so a pause set last week cannot hide an outage.
A group opens and shuts with a tap, and its members are still ordinary cards
inside it. Grouping changes nothing about what is checked or announced. The
group's icon is picked from its members' site icons, an uploaded picture
("Upload a picture", which travels in the backup), or another site's icon.

A Health endpoint monitor whose own sign-in has ended has no verdict to show.
Its card says "Not checking · Sign in needed" in neutral grey rather than rose,
because the service may be perfectly fine.

**Messages** come in three tones: mint when what you asked for worked, amber when
it worked but left you with less cover, rose when it did not happen. They are
opaque and a tap dismisses them.

**Nothing asks whether you are sure.** A control that destroys something has to
be held for a moment instead, with a fill showing how much longer, and letting go
early cancels it.

## The monitor screen

Tap a card to open its monitor. At the top, an uptime ring over the real past 24
hours (or "past 3h", "1 check" while there is less history), and **Check now**,
**Pause** or **Resume**, and **Mute 1h** or **Un-mute**.

- **Response time** charts the last 40 checks with the latency budget drawn
  across it, every check over the budget in amber, and Average, p95 and Checks
  underneath. A repository monitor has no response time chart: the round trip to
  GitHub is not what it watches.
- **Recent checks** lists 24 rows, with **Show all** for the rest. A repository
  monitor lists what happened instead ("13 stars, was 12", "Issue #6 opened"),
  with quiet runs collapsed into one line, and **Every check** brings the raw list
  of polls back. It also offers **Mute 24h**, **Mark seen** and **Open repo**.
- A Prometheus alerts monitor lists every alert firing.
- A Health endpoint monitor shows its last report part by part, with each
  part's rules and log, as described under [Health endpoint](#health-endpoint).
- An URGENT monitor that is paging shows **I've got it, acknowledge**.
- **Posts to** lists every webhook this monitor's events go to, each a link back
  to that webhook's settings.
- **Hold to delete this monitor** is at the bottom.

## Alerting

Every monitor either inherits the global alert policy (Settings, Alerts, Default
alert policy) or overrides it:

| Setting | Default | Range |
| --- | --- | --- |
| Down and recovery notifications | both on | independent switches |
| Sound | Notification tone | Silent, Notification tone, Alarm tone, Ringtone |
| Haptics | Double pulse | Tick, Double pulse, Long buzz, Heartbeat, S · O · S, Escalating |
| Failures before alerting | 1 | 1 to 10 |
| Retry right away | on | shown only when failures before alerting is above 1 |
| Cooldown | 10 min | 0 to 240 |
| Keep reminding me (repeat while down) | off, every 30 min | 5 to 720 |
| Quiet hours ("Silence overnight") | off, 22:00 to 07:00 | wraps past midnight |
| Still notify, but silently | off | quiet hours bypass |

Tapping a haptic style plays it immediately so you can feel it before you trust
it, and **Send a test alert** posts one. The master switch is **All alerts**, the
first card under Settings, Alerts. If notifications are blocked for the app, a
banner above the tabs says so and opens the right system screen.

Down and slow notifications carry **Re-check now** and **Mute 1h**. Mute is 1 hour
everywhere except a repository monitor, where it is 24 hours.

Android freezes sound and vibration onto a notification channel at creation
time, so a single channel could never honour per-monitor choices. Nightbell
materialises one channel per `(sound × vibration style × severity)` combination
on demand and routes each alert to the matching one. Channels are grouped so the
system settings screen stays readable.

### URGENT mode

A per-monitor switch for the things you cannot afford to sleep through.

While an URGENT monitor is down, Nightbell posts one page that loops its sound
and vibration until you acknowledge it, and re-checks the monitor and re-asserts
the page every *N* minutes (default 5, 1 to 120). It is `ongoing`, so it cannot
be swiped away, and on a locked phone it opens a full-screen page when the
full-screen alert permission is granted. The page in the shade carries **Ack**,
**Re-check** and a mute-for-an-hour button; the full-screen page says
**Acknowledge** and **Re-check now**. It can also be acknowledged from the monitor
screen or from the dashboard.

The page follows the ringer switch: sound and haptics on Normal, haptics only on
Vibrate or Silent. Its channel bypasses Do Not Disturb, and urgent channels are
made per vibration style and ringer mode.

Acknowledging stops the repeats **for that outage only**. The monitor stays red,
the ordinary down notification stays where it was, and recovery re-arms the
loop, so the *next* outage shouts again. Urgent overrides cooldown and the
repeat toggle, that is the point, but still honours the master switch, the
per-monitor alert switch, mute, a pause, the failure threshold and quiet hours.
The one exception is quiet hours with **Still notify, but silently** on: that
bypass lets an urgent page through.

The whole state machine is `domain/UrgentAlerts.kt`: pure, and exhaustively
tested.

Because the urgent notification is `ongoing`, deliberately un-swipeable, its
lifecycle is treated as a **reconciliation, not a transition**. A healthy check
always issues a cancel rather than inferring one isn't needed, checks of one
monitor are serialised behind a per-monitor lock, and every tick sweeps
`getActiveNotifications()` against the ids monitors can currently justify. That
last one is the only way to catch a notification belonging to a monitor that has
since been deleted.

### Alert permissions

Paging needs four grants spread over three settings sections, so a screen
gathers them in order:

| Row | Button | What the tap does |
| --- | --- | --- |
| Notifications | Allow notifications | a system dialog |
| Unrestricted battery | Allow unrestricted battery | a system dialog |
| Full-screen alerts | Open full-screen alert settings | a round trip to Settings |
| Get through Do Not Disturb | Open Do Not Disturb access | a round trip to Settings |

"Allow" and "Open" are different words on purpose: one is a dialog, the other
leaves the app. The screen also warns when the ring volume is at zero. It shows at
launch while anything is missing, unless you chose "Don't ask again", and Settings,
Alerts, Alert permissions opens it on purpose, with "N of 4 ready" and a **Check
at launch** switch.

### Spoken alerts

Off by default, and the switch is per monitor: **Say it out loud**, under a
monitor's alert settings, next to its sound and haptics. A monitor left on the
global alert settings follows the default policy's switch, so turning it on
under Settings, Alerts, Default alert policy covers the whole fleet in one go.
Settings, Alerts, Spoken alerts says how many monitors currently speak and has
"Turn on for all" and "Turn off for all" for the case where thirty monitors have
their own policies.

When it fires, the alert reads itself out: "Nightbell alert. Checkout API is
down. Host not found." The sentence is a template you can edit (**What it
says**), and the four placeholders sit under the field as buttons that add
themselves: `{name}`, `{reason}`, `{duration}` and `{others}`, filled in from the
same facts the notification carries. `{others}` is only filled on an URGENT page.
`{duration}` is left out of the default because an ordinary alert fires the
moment a check fails and would say "down for just now"; it earns its place on an
URGENT page. **Say it now** plays the sentence.

A voice pronounces, it does not translate, and Nightbell's alerts are written in
English. So an English voice is the default even on a phone whose own language
is something else, because handing English words to another language's phoneme
set produces something that is neither language. The voice list is still
offered, since someone who writes their own sentence wants a voice to match it,
and the card says plainly that it changes pronunciation rather than language. To
hear an alert in another language, write the sentence yourself and leave out
`{reason}`, which is the one part Nightbell words. When the voice that will
actually be used cannot speak the language the sentence is in, including the
case where the engine ships only one language and there was never a choice, the
card says so.

The voice is the phone's own text-to-speech engine, so nothing about a monitor
leaves the device. Only voices the engine reports as installed and usable
without a connection are offered: a synthesiser that fetches its voice over the
network would be silent during exactly the outage it was installed for. The card
also synthesises one word to a file before claiming the engine works, because an
engine can report an installed voice and then produce no audio at all, and
offers the system screen where voice data is installed when that happens.

An ordinary alert is spoken once when it goes down, and again on each repeat if
"Say it again on every repeat" is on, which it is by default. An URGENT page is
spoken by the service that owns the siren instead: once per page, so it inherits
the urgent repeat interval, with the siren muted for the sentence and restored
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

Give a monitor a **latency budget** (**Custom latency budget**, 100 to 60,000 ms)
or inherit the global one, 2.5 s, under Settings, Checks, Latency budget, and a
successful response slower than it becomes DEGRADED: amber, not red, because the
service answered. GitHub repositories have no latency budget.

Degraded has its own alert track with its own cooldown, its own repeat setting
and its own recovery notification, deliberately independent of the down track:
"slow" and "broken" are different incidents, and a slow morning should not eat
the cooldown an outage needs. An outage always supersedes slowness, so you never
get two notifications for one event.

| Setting | Default | Range |
| --- | --- | --- |
| Notify when it goes slow | **off** | |
| Notify when it speeds up again | on | |
| Latency cooldown | 30 min | 0 to 720 |
| Keep reminding me it's slow | off, every 60 min | 5 to 1440 |

### The latency reference, and how often it is contacted

The baseline needs a control, so Nightbell times a known-good endpoint and
subtracts whatever the connection itself is adding. That endpoint is
`connectivitycheck.grapheneos.network/generate_204` by default, with an Ubuntu
preset and a free text field under Settings, Checks, Latency budget, Reference
endpoint, and none of it happens with **Discount my connection** off.

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

### Telling a lost signal from an outage

With no network that claims internet access, no checks run at all, and the
dashboard says so. Captive portals count as offline. Checks resume the moment the
network comes back, and urgent repeats wait for it too, though a page already
showing stays.

One bar of signal still counts as online, so **Check my connection first**,
Settings, Alerts, on by default, covers the rest. When a check fails without
reaching anything (no DNS answer, no route, no reply), the reference endpoint is
asked as well, with a 4 s timeout. The failure is dropped, with no health change,
no sample and no alert, only if the reference is unreachable too **and** has
answered this phone within the last 6 hours. A reference that a network blocks,
or that was mistyped, never earns the right to vouch for anything, so it cannot
silence a real outage. The switch is greyed out while **Discount my connection**
is off, since it needs the same endpoint.

### How background checks actually run

Three layers, and the app is explicit about what each one can promise:

| Layer | Cadence it can honour | Notes |
| --- | --- | --- |
| Per-monitor `PeriodicWorkRequest` | the monitor's interval, **floored at 15 min** | Android's floor, not a Nightbell setting |
| 15-minute repair sweep | anything overdue, at 15-min granularity | also re-arms missing periodic work |
| Strict foreground service | exactly as configured, down to the 1 minute minimum | costs a permanent notification and battery |

**Sub-15-minute intervals cannot be honoured in the background.**
`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is 15 minutes and WorkManager
clamps anything shorter, silently. Nightbell clamps it *visibly* instead: the
scheduler coerces to the floor, the sweep still picks the monitor up as overdue
on every wake, and Settings, Checks, Checker health says so in as many words.
Strict mode is the only way to get the interval you asked for, and that is the
honest answer rather than a promise the platform will not keep.

Nothing re-arms itself from inside its own execution, and every reconciliation
uses a policy that **cannot cancel work in flight**
(`ExistingPeriodicWorkPolicy.UPDATE` for cadence, `ExistingWorkPolicy.KEEP` for
"check now"). That is not a style preference: an earlier design that replaced
work everywhere cancelled checks in flight for real users.

### Confirming a failure

A threshold above one used to be counted in scheduled checks, and in the
background a scheduled check is 15 minutes away at best. With **Retry right
away** on, a failing check that is still short of the threshold is re-checked
`QuickRetry.GAP_MS` (30 s) later, and again, until the monitor either passes or
reaches the threshold and alerts, so a threshold of three pages about a minute
after the first failure. It sits under Escalation, next to Failures before
alerting, and is on for every monitor, including ones set up before 3.17.0. The
rules are in `domain/QuickRetry.kt`.

- The retries run inside the worker, sweep or reconnect pass that found the
  failure, before it returns, because a delayed one-time work request is
  exactly what Doze holds back. In strict mode the service loop simply wakes
  for them.
- Doze still decides how long that run lives. Measured on API 34 with
  `dumpsys deviceidle force-idle`: an app under battery optimisation has its
  running worker stopped the moment the phone goes idle, and its network cut,
  so the retries left over go back to the schedule, and the next worker that
  runs takes the overdue one first. Exempted from battery optimisation
  (Settings, Checks, Checker health), the same worker kept retrying 30 s apart
  through deep idle and paged on time. The sleeping case is therefore no worse
  than before this existed, and only better with the exemption or strict mode.
- A loop that finds a retry already taken by another (the sweep and the
  monitor's own worker both wake for it) keeps waiting for the next one. It
  only gives a monitor up when nobody checked it.
- One run spends at most `QuickRetry.BUDGET_MS` (7 minutes) on them, enough
  for the nine retries a threshold of 10 needs and still inside WorkManager's
  10 minute limit. Anything left goes back to the schedule. The sweep confirms
  last, after the urgent tick, so a page already repeating never waits on them.
- Nothing is retried that could not end in a phone alert: a muted monitor, a
  switched off master switch, a pause, or a monitor whose own alerts or down
  alerts are off. A monitor that only reports to webhooks therefore keeps
  counting failures in scheduled checks.
- They go through the same gates as any scheduled check: offline, a pause, and a
  dead local network all end the retries without counting them as failures.
- Only a check that produced a failing verdict starts one, so a phone sitting in
  a car park does not re-check every monitor twice a minute.
- GitHub repositories stay on their schedule, because they spend a shared rate
  limit.
- A failing card that has not alerted yet says where it stands: "1 of 3
  failures before it alerts". It does not claim to be retrying, because whether
  a retry is running depends on the network and the budget, which the card
  cannot see.

### Checker health, separately from monitor health

A check can fail to produce an answer for three very different reasons, and
Nightbell keeps them apart:

| | What it means | What the user gets |
| --- | --- | --- |
| **Monitor failure** | the site is down, the selector is gone, the status is wrong | notification + vibration, per policy |
| **System-limited** | Doze, battery saver, no connectivity, background restricted | shown in Settings; **never** a notification |
| **Checker fault** | an exception escaped Nightbell's own checker code | its own silent channel, only once verified |

The middle row is the one that was missing, and its absence is why cancelled
checks used to arrive as outages. A checker fault needs **three consecutive
internal errors with no completed check in between**, inside 45 minutes, before it
says anything. The claim itself is held in memory only, so a restart cannot
inherit a stale one, and it clears the instant any check produces a verdict,
passing *or* failing.

**Coroutine cancellation is not any of the three.** WorkManager replacing work, a
foreground service stopping and a screen going away all cancel checks constantly;
none of them is evidence about anything, and Nightbell records and says nothing.

### Strict foreground monitoring

WorkManager is the right default, battery-friendly, survives reboots, but Doze
can defer work for a long time and its periodic minimum is 15 minutes. A monitor
set to "check every minute" does not check every minute.

Turning on **Strict foreground monitoring** (Settings, Checks, Strict cadence)
runs a foreground service that keeps your intervals exactly. It sleeps until the
next monitor is actually due rather than polling on a fixed tick, and WorkManager
stays armed underneath as a repair sweep, so a service the OS kills still gets
checks eventually.

The tradeoffs, stated plainly in the UI as well as here:

| | Strict off (default) | Strict on |
| --- | --- | --- |
| Cadence | best-effort; Doze may delay | as configured, down to 1 min |
| Battery | negligible | real, a wake per due check |
| Notification | none | permanent, un-dismissable (Android's rule) |
| Survives reboot | yes | yes |
| Survives OS kill | yes | restarted by `START_STICKY` + the sweep |

An unacknowledged URGENT outage starts the same service on its own regardless of
the setting, and stops it the moment you acknowledge or the monitor recovers.

The service declares `foregroundServiceType="specialUse"` rather than
`dataSync`, because `dataSync` is capped at six hours per day from Android 15 (API 35), which
would silently break the one guarantee strict mode exists to make. Shipping this
through Google Play would need that subtype justified in review; sideloading and
F-Droid are unaffected.

## Webhooks

Events can be posted somewhere besides this phone's notification shade: a team
channel, a push service, an automation tool, a script. Settings, Alerts,
Webhooks. Each place is a **target**, and a target has a format, an address,
the events it wants, the monitors it covers and a few switches.

Everything is sent straight from the phone. There is no relay and no account.

### Formats

| Format | Address | What is sent |
| --- | --- | --- |
| Plain JSON | any URL | the documented object below |
| Microsoft Teams | a Workflows webhook URL | an Adaptive Card 1.4 in a `message` envelope, with a fact list and an "Open" action |
| Slack | an incoming webhook URL | `text` plus `blocks`; Mattermost and Rocket.Chat read `text` |
| Discord | a channel webhook URL | one embed as "Nightbell", coloured by severity, with mentions disabled |
| Google Chat | a space webhook URL | `text` |
| ntfy | the topic URL | JSON to the server root, with title, priority, tags and click |
| Gotify | server URL with `?token=` | JSON to `/message` with a priority |
| Telegram | the bot token, plus a chat ID | `sendMessage`, plain text, no parse mode |
| Custom request | any URL, placeholders allowed | your method, content type, headers and body |

The address field is labelled for the format (Bot token, Topic URL, Server URL
with app token, Webhook URL) and each format explains where to get one.
Telegram's **Chat ID** takes a numeric id (negative for groups) or an `@name`,
and a full URL in the token field is used as it is, for a self-hosted Bot API.
ntfy priorities are 5 for down, 4 for a warning and 3 otherwise; Gotify's are 8,
5, 4 and 2 for down, warning, good news and information, and Gotify's token can go in an `X-Gotify-Key` header instead.

Teams' old Office 365 connector addresses (`webhook.office.com`,
`outlook.office.com`) are retired by Microsoft. The editor warns about one and
still lets it be saved.

Every chat format says the same two things, a title and a sentence, so a
monitor reads the same in Teams as in Telegram:

| Event | Title | Sentence |
| --- | --- | --- |
| `down` | Payments API is down | Unexpected status: Got 503, expected = 200 |
| `still_down` | Payments API is still down | Down for 35 min. Unexpected status: Got 503, expected = 200 |
| `recovered` | Payments API is back up | Down for 37 min. Answered in 212 ms. |
| `degraded` | Payments API is slow | Answered in 3.2 s, over its 2.5 s budget. |
| `degraded_recovered` | Payments API is back to normal speed | Answered in 340 ms, inside its 2.5 s budget. |
| `certificate` | Payments API's certificate expires in 3 days | Issued by R11. |
| `github` | the repository notification's own title | its own line |
| `acknowledged` | Payments API: urgent page answered | Acknowledged on On-call Pixel. It is still down. |
| `test` | Test from Nightbell to Ops | If you can read this, the webhook works. Nothing is down. |

### The editor

The editor is in sections: **Where it goes**, **The request** (Custom request
only), **What to send**, **Which monitors**, **How it behaves**, **Headers and
signing** and **Try it**.

- **What to send:** Down, Still down, Back up, Slow, Speed back to normal,
  Certificate expiring, GitHub activity and Urgent page answered. A new target
  sends Down and Back up.
- **Which monitors:** All of them, or only some, picked by group or by monitor.
  A group is followed as it changes, so a monitor added to it later is covered.
- **How it behaves:** Send from this webhook (off throws away anything waiting),
  Follow this phone's quiet hours and mute, Accept any certificate, for a
  self-signed receiver on a homelab box, and Send through the SOCKS5 proxy, for a
  receiver on a hidden service. A target set to use the proxy with none
  configured sends nothing rather than going out directly.
- **Try it:** Send a test message sends at once, from the fields as they are,
  even unsaved, and shows the HTTP status, the time it took and up to 300
  characters of what the service answered.

Saving is refused with no events picked or nothing in scope. The editor also warns
about a Discord address that is not a webhook, a signing secret under 16
characters, a JSON body that is invalid once placeholders are filled, a GET with a
body, and an unknown placeholder.

**Sign messages as**, under the list of webhooks, sets the name shown on every
message (the `sender` field). It is worth setting when two phones post into one
channel. **Send webhooks**, which appears once there is at least one target, is
the switch for all of them.

### When something is sent

Down, still down, back up, slow and back to speed each run on a **track of
their own per target**, through the same escalation as the phone: the
monitor's failure threshold, cooldown and repeat interval.

A target either follows the phone or does not:

- **Not following** (the default) ignores what is about the phone's owner:
  quiet hours, a mute, the master alert switch, and the monitor's own alert
  switches. A channel is read by people who are awake when you are not.
  Ticking "Still down" on such a target turns the repeat on for it at the
  monitor's repeat interval, even where the phone does not repeat.
- **Following** sends only when the phone would notify as well, and repeats
  only when the phone repeats.

Its own track is why a target that ignores quiet hours still posts an outage
once and not on every check of the night: the phone's track stops advancing
while it is quiet, so reading the phone's state would look like a fresh outage
each time.

Certificate notices, repository news and an acknowledged page have no
escalation to run:

- A **certificate notice** goes out only when the phone posts one, to every
  target that asked for it.
- **Repository news** goes to a following target whenever the phone would post
  it, quiet hours included. A target that does not follow the phone also gets it
  while the phone is muted or its alerts are off.
- An **acknowledged page** goes to every target that asked for it.

Three things stop new webhook events regardless:

- a **pause** from the dashboard, because a pause means this phone's signal is
  not to be believed, and a car park should not report outages to a team;
- the phone being **offline**, or an outage that **Check my connection first**
  proves is local, because no check runs at all then;
- the **Send webhooks** switch, which also throws away anything waiting.

A pause does not throw away deliveries already waiting; those still go out.

### Delivery

Events are written to an outbox in the store before anything is sent, so a
check that runs in a WorkManager process Android reclaims a moment later still
gets its events out. The app then tries straight away and arms a WorkManager
wake a minute later as a backstop.

- A 2xx is delivered. No answer, 408, 425, 429 and 5xx are retried at 30 s,
  1, 2, 4, 8 and 16 min, then every 30 min. `Retry-After` in seconds wins, up
  to an hour.
- Any other 4xx is a refusal. The same message would be refused the same way
  forever, so it is dropped and the target's row says why. A request that
  cannot be built at all is refused the same way.
- After a day of retrying a delivery is dropped and counted.
- Deliveries to one target go in the order they happened. A down that is
  backing off holds back the recovery behind it.
- At most 200 deliveries wait in total. Past that the oldest go, and are
  counted against their target.
- Each delivery has an id that survives its retries, sent as
  `X-Nightbell-Delivery` and `delivery_id`. Delivery is at least once, so a
  receiver that cares can drop a repeat.
- A request gives up after 10 s to connect, 15 s to read, 25 s in all.

The settings row under each target says, in order of importance: off; needs
its address; failing, with the receiver's reason; waiting; last delivered;
nothing sent yet. Failing is the only rose one.

### Plain JSON

```json
{
  "schema": 1,
  "event": "down",
  "event_label": "Down",
  "severity": "down",
  "title": "Payments API is down",
  "text": "Unexpected status: Got 503, expected = 200",
  "monitor": {
    "id": "…",
    "name": "Payments API",
    "url": "https://pay.nightbell.app/health",
    "kind": "Status check",
    "groups": ["Production"]
  },
  "check": {
    "reason": "Unexpected status",
    "message": "Got 503, expected = 200",
    "detail": "…",
    "status_code": 503,
    "latency_ms": 412,
    "slo_ms": 2500
  },
  "at": "2026-10-07T14:02:00Z",
  "at_unix": 1791381720,
  "down_since": "2026-10-07T13:57:00Z",
  "down_for_seconds": 300,
  "certificate_days_left": null,
  "link": "https://pay.nightbell.app/health",
  "sender": "On-call Pixel",
  "delivery_id": "…",
  "test": false
}
```

`severity` is `down`, `warning`, `good` or `info`. `schema` changes only if a
field changes meaning. The monitor's address is sent without any `user:pass@`
in it, and `detail` is cut to 500 characters.

### Custom requests and placeholders

Method (POST, PUT, PATCH, GET or DELETE), content type (`application/json` by
default), headers and body are yours. `{{name}}` placeholders work in the
address, in header values and in the body, and the editor offers them as
buttons:

`event`, `event_label`, `severity`, `title`, `text`, `monitor`, `monitor_id`,
`monitor_url`, `link`, `kind`, `reason`, `message`, `detail`, `status_code`,
`latency_ms`, `latency`, `slo_ms`, `at`, `at_unix`, `at_local`, `down_since`,
`down_for`, `down_for_s`, `cert_days_left`, `groups`, `sender`, `delivery_id`,
`test`.

Values are escaped for where they land: JSON string escaping in a JSON body
(write `"{{title}}"` inside quotes), URL encoding in a form body and in the
address, and nothing in any other body, XML included. In a header value a line
break becomes a space. An unknown placeholder is sent as typed, and the editor
names it. An empty body sends the plain JSON object, except on a GET, which
sends no body at all.

### Headers and signing

Every request carries `User-Agent: Nightbell/<version>`, `X-Nightbell-Event`
and `X-Nightbell-Delivery`. Extra headers can be added per target, for ntfy's
`Authorization: Bearer …` or anything else.

With a signing secret set, a request also carries:

```
X-Nightbell-Timestamp: 1791381720
X-Nightbell-Signature: sha256=<hex HMAC-SHA256 of "1791381720.<raw body>">
```

The timestamp is the moment that attempt left, so a retry is signed afresh.
Checking one in Python:

```python
expected = "sha256=" + hmac.new(secret, f"{ts}.{body}".encode(), hashlib.sha256).hexdigest()
```

### Secrets

A webhook address is the permission to post into that channel. Addresses,
header values and the signing secret are treated like the GitHub token. Once
saved, an address is shown shortened (`hooks.slack.com/…wxyz`, or the bot id
and the last four characters for a Telegram token) with a **Replace** button,
and header values and the secret are masked. None of them is ever written to the
diagnostic log, they are scrubbed out of any error message before it is stored
or shown, and they are left out of a backup unless **Include tokens and webhook
addresses** is on. A target imported without its address stays configured and
says it needs the address pasted again. Queues, tracks and delivery history
never travel in a backup.

## Home-screen widget

A worst-first list of monitors, configurable per instance:

- **Theme**: black, white or blue, each a solid rounded surface, because a
  widget has to stay legible on a white beach photo and a black AMOLED wallpaper
  alike, and translucency cannot promise that. Plus Custom, below.
- **Density**: compact (dot, name and a trailing value: latency, a status word,
  or a repository's star count) or detailed, which adds a second line with the
  failure message, or the host when there is none.
- **Header pieces, independently**: Logo, App name, Fleet summary ("1 of 6 is
  down") and Settings button each switch off on their own.
- Show or hide the last-checked footer, and optionally hide healthy monitors
  entirely.
- **Monitors**: automatic, or capped at 1 to 10. Automatic lists as many as the
  size the widget was dragged to can hold. Any overflow is disclosed as "+N more"
  rather than silently truncated.
- **Columns**: automatic, or pinned to 1 to 3. See below.

A new widget starts black, compact, with everything shown and both counts on
automatic. The settings screen has a live preview; its button reads **Add
widget** on a new widget and **Save** on one already placed.

A repository row shows its star count with a gold star while the repository is
up, and a detailed row adds the open issue count and the latest tag underneath.
A row whose monitor is paging urgently is marked with a warning sign.

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
2. **Long-press the widget**, then its settings entry. This is
   `widgetFeatures="reconfigurable"`, added in API 31 and ignored below it.
3. **Settings, Look, Home-screen widgets**, which lists every placed widget with a
   **Configure widget** button each.

Reported as a real problem: once the widget was on the home screen its
configuration was unreachable.

### Columns

A widget's height is whatever the user dragged it to. Flatten one and the old
single-column layout stopped drawing monitors past the fold: they were still in
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
  carries health and the number is one tap away, so the name wins. A repository's
  star count is kept.
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
API 26 to 30. `setColorFilter` plus `setImageAlpha` give an arbitrary colour at an
arbitrary opacity while keeping the rounded corners a flat `setBackgroundColor`
would throw away. The hairline edge is its own layer and fades out with the
surface, so a fully transparent widget has no ring floating around it.

Presets deliberately ignore the opacity slider: they are defined surfaces with
known contrast, and letting a stray opacity value apply to them would quietly
turn a legible preset illegible. Picking a pale custom background moves the text
colour to something readable *unless* you have already chosen one yourself.

## The live strict-monitoring card

On Android 16 the strict-monitoring notice draws its own history. `ProgressStyle` (the
template rideshare and delivery apps use) gives a horizontal line of coloured
segments with milestone points and a tracker riding along it, and it is the only
template the platform will promote to a status-bar chip and expand on the lock
screen. `LiveTimeline` turns the check history into that shape; `LiveCard` hands it
to the notification.

Reading the line:

| What you see | What it is |
| --- | --- |
| Green, amber or red stretch | a **band**, one run of an outcome, as wide as it actually lasted |
| Taller red block | a **marker**, where an outage *began* |
| Grey tail past the dot | the wait until the next check |

The line covers at least 10 minutes and at most 24 hours, and the label at its
left end says how much ("23h"). When something is down the status-bar chip says
how many ("1 DOWN").

The marker is not redundant with the band it sits on. The shortest possible outage
is one bucket, a couple of per cent of the width, which on screen reads as a
rendering artefact: the block is what makes a single failed check findable, and the
band is what makes a long one measurable.

### Three things it used to get wrong

All three were reported from a device and none was visible from reading the code.

**The window was measured from checks it could not draw.** The span came from the
oldest retained sample of any age and was then clamped to 24 hours, so one straggler
older than a day stretched the line to a full day and was immediately skipped for
falling before the window start. Every bucket between the left edge and the first
drawable check stayed unknown, and carrying forward cannot rescue them: it
propagates left to right and there is no earlier bucket to inherit from. Half a bar
of grey under a label claiming a day of history. Samples older than the ceiling are
now dropped *before* the span is measured, so the label describes the line drawn.

**Compression invented outages.** Capping the band count used to absorb the shortest
non-outage band into a neighbour, and the neighbours of an up-band are outages by
construction, since same-tone runs are already fused, so the absorbed uptime could
only ever be handed to an outage, and the adjacency pass then merged the two red runs
into one. Measured on a monitor that alternated pass/fail every half hour for a day:
24 buckets genuinely failed, the line drew 40, and the longest drawn run was 33, a
claimed sixteen and a half hours of continuous downtime that never happened. The old
guarantee that outages are never merged *away* was true and beside the point.
Compression now reports each group's real composition, so the drawn red total matches
the real one exactly, at the cost of approximating order within a group.

**A live outage could be painted over.** Carry-forward ran on the fleet-merged tone,
so a monitor checking every five minutes filled every bucket after an hourly
monitor's failure with green: the line drew green to the right edge under a red
tracker and a "1 DOWN" chip. Each monitor now carries its own last verdict before the
fleet merge.

### The countdown on the line

The tail's width says *how much* of the wait is left; the label at the end of the line
says how much in words: "15m", "4m", "now". Both come from the same `nextCheckInMs`,
so they cannot disagree.

`ProgressStyle` carries no text: segments and points take a colour and nothing else. It
does take an icon at each end, and an icon is a bitmap, so the label is drawn into one.
Three things about that slot were learned the hard way, on a device:

- **It centre-crops to a square.** A bitmap sized to its text is trimmed from both ends,
  "1h20m" rendered as "h20", "now" as "how". The canvas is square and the text is fitted
  into it, so a short label renders larger than a long one.
- **It renders full colour**, not the alpha mask a status-bar small icon gets. Proven by
  posting red/green/blue stripes into it and getting three stripes back.
- **Glyphs must be filled, not knocked out.** The first version punched the text out of a
  pill, which put the card behind it (a colour this code neither chooses nor can
  measure) on one side of the contrast ratio. It measured 2.5:1 and was reported from a
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

It is also paced by **one** monitor (the fastest, ties broken by id) rather than by
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

It ships as a **legacy** icon rather than an adaptive one. That is deliberate, and
the reason is worth writing down because it cost two rounds of chasing the wrong
thing.

The mark was originally drawn on an opaque ink plate. Asked to make the
background transparent, the obvious move is an `<adaptive-icon>` whose
`<background>` is `@android:color/transparent`. It renders solid black.
`AdaptiveIconDrawable.draw()` fills its layer bitmap with `Color.BLACK` before it
composites the background and foreground, because the format assumes the
background is opaque. So the black is not a launcher bug and not a stale icon
cache: it reproduces in Settings' app-info screen, and survives clearing the
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
format carries: `ic_launcher_foreground` and `ic_launcher_monochrome` are kept,
unused, for whenever a plate is wanted back.

`LauncherIconInstrumentedTest` pins the things that are actually in the app's
control: that the manifest does not point at an adaptive icon, and that the
drawable it does point at has transparent corners, draws its trace in the brand
blue, and no longer paints the ring across the top. All of it was invisible to
review: a dark icon on a dark background looks the same either way.

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

As of 2.4.3 each copy is the single trace path (the ring direction 30 drew around
it is gone) fitted to its canvas and vertically centred. The generator computes
that fit from the trace's own bounding box, so `size` still means the drawn mark's
extent and the copies cannot drift apart.

## The cold-start animation

`ui/NightbellSplash.kt`. The bell arrives, rings, and the word wipes in beside it,
over about 1.75 s, on cold start only. It used to run 2.8 s, a figure timed on a
phone whose animation speed was set to half, so it had always played twice as
slowly for everyone else. It is a Compose animation, so the system's animator
duration scale still stretches or shrinks it.

It is one `Animatable` from 0 to 1 with each beat reading its own window out of it,
so the beats cannot drift apart and re-timing the sequence is editing a table
rather than chasing delays through nested coroutines. The mark it draws is
`NightbellMark`, the same composable the dashboard header uses, so the splash
cannot drift from the icon it is introducing. The app is composed underneath from
the first frame and keeps loading while it plays, so the splash covers work that
was happening anyway.

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
- **The last 0.45 s is the finished lockup sitting still.** That hold is the point,
  not padding: an opening that dissolves the instant it resolves reads as a glitch.
- **It does not animate the cut.** An earlier version carved the trace into the
  bell here. It looked good alone and was wrong in sequence, because the system
  splash already shows the finished icon, so the bell arrived whole, lost its
  heartbeat and grew it back.

The whole thing is skipped when `rememberSystemAnimationsEnabled()` is false. A
splash is decoration, and that setting is asking us not to play decoration.

## Moving between installs

**Settings, About, Backup and transfer** writes every monitor, its sample
history, its groups (uploaded group pictures included) and your settings to a
JSON file named `nightbell-backup-<date>-<time>.json`, through the Storage
Access Framework, so Nightbell needs no storage permission, the destination is
yours to pick, and nothing is uploaded anywhere.

Without **Include tokens and webhook addresses**, the export leaves out the GitHub
token, webhook addresses, signing secrets and header values, each page monitor's
saved browser session, and Prometheus passwords. Usernames stay. An export that
did include a token says so in amber. A Health endpoint monitor's single sign-on
tokens, password and client secret are never exported, switch or not: they stay
in the old phone's credential vault, and a restored single sign-on monitor says
it needs signing in.

An import **replaces** what is on the device rather than merging, and says so
before it does it ("Import replaces everything"). What it deliberately does not
carry is any record of notifications already posted: health resets to unknown
until this install has actually checked something, and the alert bookkeeping
resets with it, because importing an in-progress alert state would suppress the
first real outage on the new device. The diagnostic log switch does not travel
either: it is a decision about a device, not about a fleet.

Widget settings are **not** in the backup. They live in their own store, and a
placed widget belongs to the launcher anyway: drag it back on after importing and
set it up again.

It also exists because the app has changed its `applicationId` twice, and that is
not a rename. Android identifies an app by that id, so a build carrying a new one
installs *beside* the old app rather than updating it: new data directory, no
monitors, and no way for it to read the older install's files. No signing key or
manifest setting changes that.

| From | To | How |
| --- | --- | --- |
| 1.x | 1.x, later | updates in place |
| 1.7.0 or later 1.x | 2.0.0 (`me.river.pulse`) | export, install, import |
| 2.x (Pulse) | 3.0.0 (`me.river.nightbell`, renamed Nightbell) | export, install, import; 2.x backups still import |
| 3.0.0 | 3.0.1 | uninstall first, because 3.0.1 changed the signing key |
| 3.0.1 or later | 3.18.0 | updates in place, same signing key |

Export **before** uninstalling anything, because once the old app is gone so is
its data.

## Being told about a new Nightbell

**Settings, About, Nightbell updates**: **Tell me about new versions** switches
the check on (off reads "Off, your installer handles it"), and the card picks the
source, shows the installed and latest versions and offers **Check now**. When a
newer version exists the app says so twice: a notification, once per version,
and a modal notice on the dashboard.

Three sources, at most one check every six hours:

| Source | Reads | Default for |
| --- | --- | --- |
| **nightbell.app** | `nightbell.app/v1/release.json`, a static file naming the current release | Everything except an F-Droid install |
| **GitHub** | `api.github.com`, the newest release on the repository | Nobody, and it stays selectable on purpose |
| **F-Droid** | `f-droid.org/api/v1`, the newest build F-Droid has published | An install whose installer was F-Droid, F-Droid Basic, Droid-ify or Neo Store |

The site is the default since 3.10.0, and an install that predated it is moved off
GitHub once. An F-Droid install is never moved: F-Droid publishes on its own
schedule, so the site's newest tag is as unreachable for them as GitHub's was.

The notification offers **What's new** (or **Open download**), **Remind later**
and **Ignore this version**. Remind later holds for 24 hours, and so does closing
the dashboard notice. An ignored version can be shown again from Settings with
**Show it again**.

### The version check is counted

The request to `nightbell.app` is the only one Nightbell makes that exists partly
for the maintainer's benefit, so it is worth being exact about. It carries a
`User-Agent` of `Nightbell/<version> (Android)`, plus `new` on an install's first
ever successful check and `tap` when the user pressed Check now, so at most
`Nightbell/<version> (Android; new; tap)`. Nothing else. The origin logs a
timestamp, a status and that string, in `census.log`, and the log format holds no
address and no hash of one. `website/scripts/validate.mjs` fails the site build if
`$remote_addr` or an alias appears in it.

That yields two numbers: an exact count of installs, from the word `new` said once
per install per lifetime, and a floor on active installs, from scheduled requests
per day over four. `tap` exists to keep the second one honest, since Check now
passes `force` and skips the interval, so a tapped check is excluded from that
division and counted only in the version histogram. Neither number can be traced
to a device, because there is no per install identifier in the app to log.
Retention curves are the thing this deliberately cannot produce, since linking two
days of the same install is what an identifier is for.

`censusSent` in `UpdateState` is the flag behind the word. It is set only when a
manifest comes back parsed, so an install that is offline for its first three days
is still counted as new on the fourth, and it survives everything the user can
answer about a version. Clearing the app's data resets it, which counts a wipe and
reinstall as two installs.

The switch that stops the check stops the counting with it, and choosing GitHub
moves the request to a third party who will not tell the maintainer anything. Both
of those are the point rather than a loophole: a count nobody can decline is a
different thing than a count.

**The notification opens the app, not a browser**, whenever the release has an
APK behind it. All three sources publish one, so the route turns on whether there
is a file rather than on which source published it. The tap lands on the
dashboard where the notice offers **Install**, and it clears any "Remind later"
deferral on the way, because that deferral quietens the surfaces that appear on
their own and a deliberate tap is not one of those. A release with no APK is the
one case where a page is the only route, and there the notification still opens
it.

Nothing is downloaded until Install is pressed. The download is checked before it
is handed to Android: a different package name or a different signing key is
refused. Android then asks again before it replaces the app. Where Android has not
been told to allow installs, the button reads **Settings** and says what it will
do, rather than being an Install button that opens a settings screen.

## The diagnostic log

**Settings, About, Diagnostic log** records what Nightbell is doing to a text
file the user can export and hand to somebody. It exists because a report used
to arrive as a sentence and get answered with a guess: `adb logcat` is the only
place the app's own account of itself lived, and a phone is not a machine that
can read it.

The switch, **Record a log**, governs the **file** and nothing else. Three sinks,
and only one of them is optional:

| Sink | When | Where |
| --- | --- | --- |
| logcat | always | `adb logcat -s Nightbell` |
| a ring of 500 lines | always | memory only, never disk |
| the file | while the switch is on | `filesDir/diagnostics/`, capped at 192 KB with one rotation |

A crash is the exception and is recorded whichever way the switch is set, to a
file of its own, carrying the stack trace plus the ring. A crash cannot be
reproduced on request, so a switch that has to be on beforehand would record
nothing the first time, which is what
[issue 2](https://github.com/riveerxd/nightbell/issues/2) arrived with.

The switch is off on a fresh install and does not travel in a backup: it is a
decision about a device, not about a fleet.

### What is in a line

Lines are for grepping, not for reading as prose:

```
18:36:25.896 I HTTP  http.request monitor=7f3a1c2e url=https://api.nightbell.app/*2?*1 headers=1
18:36:25.902 W PAGE  page.expired stage=LOADING percent=43 ready=interactive load_event=false requests=87
```

Time is local. Level is at a fixed column, which is what the in-app viewer
colours by. The area names one of the surfaces the app has actually been
reported broken on: `APP`, `SCHED`, `CHECK`, `HTTP`, `PAGE`, `ALERT`, `STORE`,
`NET`, `WIDGET`, `UPDATE`. After the event code, everything is `key=value`.

An exported file, `nightbell-log-<version>-<date>-<time>.txt`, opens with a block
of facts about the device: version and whether the build was minified, API level,
model, **the WebView package and version**, whether battery optimisation is on,
which alert permissions are granted, the network state, and the fleet as counts.
Those are the fields issue threads kept having to ask for one at a time.

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
page. The dialog gets its own scroll and takes at most 0.6 of the height it is
given, so the same screen works on a phone and on a 480 dp head unit.

The viewer shows the newest 1,000 lines, oldest first like the file, and opens
already scrolled to the newest one. **Hold to delete the log**, inside the
dialog, removes both generations and the crash file, and while recording leaves
one line saying so, because a file that silently begins in the middle cannot be
told from one that only just started.

With the switch off the viewer shows nothing, even though the in-memory ring is
still filling for the crash handler's benefit. Showing the ring would contradict
the switch two rows above it.

**Export is blocked while there is nothing to hand anybody**, and the switch's
own subtitle two rows above it says why, so the reason a blocked button is
blocked is on screen permanently rather than behind a tap. A blocked button still
announces as unavailable rather than reading as a caption; see `NightbellButton`.

Export goes through the Storage Access Framework, like the backup export, so
there is no storage permission, no `FileProvider`, no exported component, and the
destination is the user's to pick.

## The element picker

1. Enter a URL and tap **Open live preview**.
2. The page loads in a real WebView. Browse and scroll normally.
3. With the **Select mode** switch off ("Browsing, links are live"), the page is
   live: links work, and so does the button on a cookie banner or an age gate.
   Turn it back on when the view you want is on screen. It starts on.
4. With it on ("Tap any element to select it"), tap the node you care about.
5. Injected JS derives a durable signature and streams it back over a JS bridge:
   `id` → stable data-attribute (`data-testid`, `data-test`, `data-qa`,
   `data-cy`, `aria-label`, `name`, `itemprop`) → shortest unique CSS path →
   absolute XPath → text fingerprint.
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
rather than reporting a missing element. Nothing is ever clicked on your behalf.

## Settings at a glance

| Tab | Cards |
| --- | --- |
| Alerts | All alerts, Check my connection first, Alert permissions, Default alert policy, Webhooks, Pause button, TLS certificates, Spoken alerts |
| Checks | Background checks (Wi-Fi only, Data saver, default interval, timeout and history), Checker health, Strict cadence, Latency budget (with Discount my connection and the reference endpoint), SOCKS5 proxy, GitHub |
| Look | Appearance (System, Dark or Light), Motion, Frosted glass, Site icons, Home-screen widgets |
| About | Nightbell updates, Backup and transfer, Diagnostic log, Help, About |

**Motion** is a slider from off to extra lively; at zero everything snaps into
place. **Frosted glass** (on by default) draws a real backdrop blur behind
floating sheets, and needs Android 12; older phones get solid panes. **Site
icons** shows each page monitor's favicon on its card, cached for a month, with
**Refetch site icons** to start again.

## Architecture

```
domain/    pure Kotlin, no Android: Models, Assertions, AlertDecider,
           UrgentAlerts, QuickRetry, Summary, Validation, MonitorGroup,
           MonitorQuery, Reachability, CertificateWatch, DataSaver,
           GitHub*, Prometheus*, Webhook*, SpokenPage, DiagnosticLog, ...
data/      NightbellStore (DataStore+JSON, forward migration),
           check/ (HttpChecker on OkHttp, ElementChecker in an offscreen
           WebView, GitHubChecker, LatencyReference, TlsTrust, WebViewProxy,
           UpdateChecker, CheckEngine), alerts/ (AlertCenter, UrgentAlarm,
           PageSpeaker, LiveCard), webhook/, work/ (WorkManager scheduling,
           NightbellMonitorService), transfer/, update/, diag/, icons/, net/,
           Nightbell (service locator)
ui/        theme (colours, glass modifiers, motion, backdrop blur),
           icons (hand-authored), components, dashboard, detail,
           setup (+ element picker), settings, permissions, update, urgent
widget/    RemoteViews provider, per-instance config store, config activity
```

The whole decision surface, status matching, body assertions, element
comparisons, and the entire alert escalation matrix, lives in `domain/` as pure
functions, which is why it can be exhaustively unit-tested without a device.

**Design notes**

- `Modifier.glass()` composes the house style: translucent pane, light-catching
  top edge, diagonal specular sweep, and an optional accent rim.
- **Health has its own colours.** Mint, amber and rose mean up, degraded and
  down on cards, charts and the widget; the brand family is aqua, indigo and
  violet. `healthRim()` tints a card's edge for the states worth interrupting
  someone for and returns transparent for the rest: if every card is outlined,
  the broken one stops standing out.
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
- Messages are a **capsule sized to its text**, parked over the wordmark row
  and carrying a genuine drop shadow, rather than a full-width banner across the
  fleet verdict, which is the one line people open Nightbell to read.
- Icons are hand-authored `ImageVector`s on a 24-unit grid with 1.7px round
  strokes, so the whole app shares one optical weight and the APK carries only
  the glyphs it uses.
- **Muted is not broken.** A muted monitor gains a "Muted" pill, and if it is
  down or slow its rim goes amber rather than red; red stays reserved for "this
  needs you now". A wall of red cards that you have already triaged teaches you
  to ignore red.
- **Pull-to-refresh is a two-stage gesture.** Re-checking every monitor fires
  real requests at every endpoint at once, so it should not be reachable by an
  accidental over-scroll. The pull is rubber-banded through
  `maxPull · (1 - e^(-raw/maxPull))` and every visual (puck scale, ring sweep,
  glyph rotation, label) is a pure function of that one distance, so the
  indicator is welded to the finger. Past the threshold you must *hold* for two
  seconds while a ring closes; releasing early springs back and cancels. With
  animations off the hold is skipped.
- **Real backdrop blur on API 31+** (`ui/theme/Backdrop.kt`). The scrolling
  subtree records itself into a `GraphicsLayer`; a floating sheet draws that
  layer, translated and clipped to its own bounds, so it shows a genuinely
  blurred copy of the pixels underneath. Two layers, not one: a layer's
  `renderEffect` applies every time it is drawn, so a single blurred layer would
  put the *screen* out of focus too. Sinks are invalidated by the source via
  `DrawModifierNode.invalidateDraw()`, because a node that draws a layer
  somebody else records reads no state and would otherwise freeze mid-scroll.
  Below API 31, or with Frosted glass off, it degrades to an opaque pane.
  Messages stay fully opaque either way.

## Build

minSdk 26, targetSdk 36, application id `me.river.nightbell`. The debug build
installs as `me.river.nightbell.debug` beside a release one.

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # minified + resource-shrunk, signed
./gradlew :app:assembleReleaseTest  # minified but debug-signed, for smoke-testing R8
./gradlew :app:testDebugUnitTest    # 1237 JVM tests
./gradlew :app:connectedDebugAndroidTest   # 567 on-device tests
```

**Release is minified.** R8 plus resource shrinking keeps the 3.18.0 release APK
to about 2.7 MB. `proguard-rules.pro` documents every keep and why R8 cannot see
the reference itself: the WebView JS bridge (called from JavaScript by method
name), kotlinx.serialization's generated serializers, WorkManager workers
(instantiated by class name from a database that survives app updates), and the
widget provider (referenced by an `AppWidgetProviderInfo` the *launcher* persists
outside our APK).

The `releaseTest` variant exists so the R8 configuration can be exercised on a
device without replacing the installed release build. Everything reflective was
verified against it: monitors round-tripped through a process kill, WorkManager
jobs scheduled, the JS bridge returned a picked selector, the foreground service
entered `specialUse`, and the notification actions resolved.

**Baseline profile.** `app/src/main/baselineProfiles/baseline-prof.txt` is
hand-authored and covers four flows: cold launch, the monitor list, the detail
screen and setup. AGP merges it into `assets/dexopt/baseline.prof` and rewrites it
through the R8 mapping, so the source names in the file are correct even though
the shipped classes are obfuscated.

Release signing reads `keystore/keystore.properties`; when it is absent the
release build is simply unsigned. Regenerate with:

```bash
keytool -genkeypair -v -keystore keystore/nightbell-release.jks -alias nightbell \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass <pw> -keypass <pw> -dname "CN=Nightbell, OU=river, O=river, L=Prague, C=CZ"
```

## Testing

**1237 JVM + 567 on-device = 1804 automated tests**, counted from the source.
The table names the older core suites and the families added since; it is not
every class, and its rows have not been recounted one by one since they were
first written.

| Suite | Count | Covers |
| --- | ---: | --- |
| `AssertionsTest` | 20 | status modes, all six body assertions, JSON paths, element modes |
| `AlertDeciderTest` | 17 | thresholds, cooldown, repeat, recovery, quiet-hour wrap, runtime folding |
| `HttpCheckerTest` | 15 | real sockets: 200/503/418, body + JSON assertions, POST echo, timeout, DNS, refused, redirects |
| `ValidationAndPersistenceTest` | 20 | URL, header, assertion, cadence and element validation; snapshot round-trip and forward compatibility |
| `UrgentAlertsTest` | 19 | the urgent state machine: start, repeat gap, acknowledge, recovery re-arm, suppression |
| `DegradedAlertTest` | 12 | latency threshold, independent cooldown/repeat/recovery, quiet hours, down-track regression guard |
| `QuickRetryTest`, `QuickRetryInstrumentedTest` | 16 + 11 | confirming a failure 30 s apart, its budget and its gates |
| `CheckerHealthTest` | 33 | the checker-health machine: cancellation raises nothing, the three-error bar, streak windows, clearing, expiry |
| `CancellationIsNotAFailureTest` | 8 | real sockets: a cancelled check throws instead of returning a verdict, and real IO failures are still classified |
| `CheckerLimitsTest` | 14 | why checks are late, and the precedence between offline / metered / restricted / saver / Doze |
| `ReachabilityTest` | 11 | when a failed check may be blamed on the phone's connection |
| `LegacyCrashRepairTest` | 14 | scrubbing 1.5.0's fabricated crash state without touching a genuine outage |
| `DueCheckTest` | 7 | the due-ness rule, including a wall clock that jumps backwards |
| `WidgetLayoutTest` | 27 | the widget's column and row planning |
| `WidgetPaletteTest` | 13 | preset and custom widget colours, opacity clamping, fully-transparent surfaces |
| `BackupTest` | 18 | the transfer format, its refusals, and what an import must not carry over |
| `NetworkBaselineTest` | 19 | the latency-reference maths and its four trust states |
| `MultiElementTest` | 14 | target list, 1.0.0 migration, per-element validation, SLO/urgent validation |
| `SummaryTest` | 10 | worst-first ranking shared by dashboard, widget and service |
| `MonitorGroupTest`, `MonitorGroupInstrumentedTest` | 32 + 34 | group verdicts and the group UI |
| `GitHub*`, `RealGitHubInstrumentedTest` (11 classes) | 225 | parsing, events, downloads, the checker and setup |
| `Prometheus*` (4 classes) | 73 | the checker, validation, setup and end to end |
| `Webhook*`, `RealWebhook*` (9 classes) | 88 | payloads, routing, the outbox, sending, backup and settings |
| `SpokenPageTest`, `SpokenPageInstrumentedTest` | 34 + 16 | the spoken sentence and the engine |
| `LogSentinelTest` | 15 | no secret or monitor name survives into the diagnostic log |
| `NightbellE2ETest` | 9 | full UI journeys on-device |
| `ElementMonitorTest` | 10 | real WebView DOM: locate, fallbacks, picker capture |
| `GatedElementInstrumentedTest` | 5 | [issue 8](https://github.com/riveerxd/nightbell/issues/8) driven through the real UI: press through a gate, follow a link, pick, and have the check agree; plus the same check from a wiped browser store, a `localStorage`-only wall, the gate-shaped failure message, and a session refusing another origin |
| `GatedPageTest` | 14 | which page a pick belongs to, which origin a captured session may be replayed at, and what an export leaves behind |
| `AlertsInstrumentedTest` | 12 | real notifications, channels, escalation, mute, actions |
| `UrgentModeInstrumentedTest` | 13 | urgent end-to-end through the real engine + notification action |
| `CheckerCancellationInstrumentedTest` | 5 | the reported bug through the real graph: a cancelled check, and a cancelled six-monitor pass, must announce nothing |
| `WidgetInstrumentedTest` | 34 | RemoteViews **inflation**, ordering, every theme/density, custom colours at three opacities, the settings cog, config persistence and id remapping, the planner's heights against the views that get drawn |
| `WidgetConfigUiTest` | 3 | the widget's own settings screen, driven |
| `ScreenshotTest` | 4 | drives every screen and writes PNGs |
| `HealthFormatTest` | 42 | the health report format: the ASP.NET outputs, aliases, unknown words, the marker, size, timestamps, staleness, caps, conditions and the log |
| `HealthEndpointCheckTest` | 9 | real sockets: headers sent, 503 with a report, the size limit, staleness by the server's clock, a vault sign-in replacing a typed header |
| `HealthEndToEndTest` | 6 | broker, checker and a fake provider together: one refresh on a 401, token refused, 403, signed out sends nothing, provider down, basic |
| `TokenBrokerTest` | 16 | cached tokens, the 30 s margin, rotation, `invalid_grant` per grant, one password try, `invalid_scope`, provider down, one refresh for two callers, cancellation after the answer, an unreadable vault, rediscovery |
| `OidcRequestsTest` | 12 | PKCE (the RFC 7636 vector), the authorization URL, recognising the web app's request, issuer discovery, the callback, token answers |
| `JwtPeekTest`, `SignInReminderTest`, `HealthValidationTest` | 12 | pasted tokens, the once-a-day notice, what setup refuses |
| `MonitorKindDecodingTest`, `StoreCorruptionTest` | 9 | a kind from a newer build and an unreadable store, neither costing every monitor |
| `HealthEndpointSetupInstrumentedTest` | 6 | setup driven against a local server: no sign-in needed, needs a sign-in, a fixed key, typing alone is enough, a login page instead of a report, a changed address |
| `HealthComponentsInstrumentedTest` | 7 | the report card with none, one, three, 64 parts, a disagreement, not checking, rotation |
| `CredentialVaultInstrumentedTest` | 3 | AndroidKeyStore round trip, a lost key reading as signed out, pruning |
| `SsoSignInInstrumentedTest` | 4 | browser sign-in through a fake web app and provider: code caught and exchanged once, refusal, offline access refused, a moved address signing in through the new web app |

`WidgetInstrumentedTest` calls `RemoteViews.apply()` rather than asserting on
the builder, because that runs the same inflation the launcher's process runs:
an unsupported view, a method that isn't `@RemotableViewMethod`, or a missing
resource fails there instead of showing "Problem loading widget" on a home
screen.

`TinyHttpServer` (in `src/testShared`) is a small dependency-free HTTP server
shared by both suites, so the checker is always tested against real sockets
rather than a mock. `TinySocks5` and `TinyTls` do the same for the proxy and for
certificates.

## Known limitations

- **Sub-15-minute background intervals are impossible, not merely unreliable.**
  `PeriodicWorkRequest`'s minimum period is 15 minutes and there is no supported
  way around it. Nightbell clamps to the floor, lets the 15-minute repair sweep pick
  up anything overdue, and says so in Settings, Checks, Checker health. Manual and
  foreground-service checks are exact; strict mode makes the background ones exact
  too, at the cost of a permanent notification and real battery.
- **Doze and App Standby can delay even a 15-minute interval.** Exempting Nightbell
  from battery optimisation (offered in Settings, Checks, Checker health) helps and
  is not a guarantee. Only the foreground service is. Delay is reported in the UI
  and is never notified about: it is not an outage.
- **No Wear OS tile yet.** The shared roll-up it would need (`domain/Summary.kt`)
  exists and is tested; there is no Wear module.
- **Widget rows are capped, not scrollable.** RemoteViews collections would buy
  scrolling at the cost of a second process hop and a class of stale-data bugs;
  a short worst-first list is what is actually glanceable. Overflow is disclosed.
- **Real blur is API 31+.** Below that, and when Frosted glass is off, floating
  sheets are opaque. Nothing becomes see-through in either mode.
- **Element checks need a renderable page.** After `onPageFinished` a page is
  looked at five times over about 4 s; pages that hydrate slower than that, or
  that hard-block offscreen WebViews, may report "element not found".
- **A captured browser session expires when the site says it does.** Nightbell
  replays what the preview was holding, it does not renew it. The age gate on
  the site in issue 8 lasts fourteen days, after which the check reports the
  gate and the fix is to open the preview and press through it again. Nothing
  is replayed on the user's behalf: a recorded click that re-answered a consent
  dialog every interval was considered and refused.
- **No custom sound-file picker.** Sound choice is silent, notification, alarm or
  ringtone; per-channel fine-tuning is handed off to system settings.
- **Widget settings are not in a backup.** See "Moving between installs".
- **Storage is local only, and there is no sync.** What leaves the device is
  what you set up, plus a short list Nightbell decides on itself:
  - your monitors' own requests, including everything a monitored page loads;
  - `api.github.com`, for repository monitors;
  - the sign-in provider of a Health endpoint monitor set to single sign-on,
    for its discovery document and its tokens;
  - webhook deliveries, to the addresses you gave;
  - the reference endpoint, unless Discount my connection is off;
  - site icons for page monitors and group icons, from the sites themselves;
  - the six-hourly version check described under "Being told about a new
    Nightbell", unless it is switched off, and an APK download only when you
    press Install.
