# Deploying nightbell.app

Static site, Nginx, Cloudflare's proxy in front. This describes what is actually
running, not a recommended setup: every version number, path and setting below was
read off the live host, and the checks at the bottom were run against the public
URL.

```
deploy/
  nginx/
    nightbell.app.conf            the server blocks
    nightbell.app.bootstrap.conf  port 80 only, used once to get the first cert
    snippets/nightbell/
      security-headers.conf       CSP, HSTS and the rest, included per location
      cloudflare-real-ip.conf     the edge ranges, so logs show visitors
  scripts/
    deploy.sh                     build, upload, switch a symlink, prune
```

## The origin is not a spare VPS

This matters more than anything else on this page. The host is a **shared
production box serving fifteen sites**, several of them somebody's business:
`clinicm`, `nclinic.cz`, `ocnipetriny.cz`, `faktury.riveer.cz` and others.

- **A bad `nginx -t` takes all fifteen down together.** Always test before
  reloading. `deploy.sh` does; do the same by hand.
- **The address is deliberately not written down here.** The repository is public,
  and the address of an origin behind a proxy is the one piece of infrastructure
  worth not publishing: knowing it is the difference between having to go through
  Cloudflare and being able to skip it. `deploy.sh` takes it from
  `NIGHTBELL_HOST`.
- Keep a backup before touching anything: `sudo tar czf /root/nginx-backup-$(date -u +%Y%m%d-%H%M%S).tgz /etc/nginx`

## What is on the host

| | |
| --- | --- |
| OS | Ubuntu 22.04 LTS |
| Nginx | 1.18.0 |
| TLS | Let's Encrypt via certbot, one cert covering `nightbell.app` and `www.nightbell.app` |
| Renewal | the existing `certbot.timer`, which already renews thirteen other certs |
| Document root | `/var/www/nightbell.app/current`, a symlink into `releases/<timestamp>/` |
| ACME webroot | `/var/www/nightbell.app/acme` |
| Site config | `/etc/nginx/sites-available/nightbell.app.conf` |
| Snippets | `/etc/nginx/snippets/nightbell/` |

Three consequences of Nginx being 1.18, all of them already handled in the config
and all of them easy to reintroduce by copying a newer example from the internet:

- `ssl_reject_handshake` does not exist until 1.19.4. Not used.
- `http2 on` does not exist until 1.25.1. The `listen ... http2` form would work,
  but is not used either: Cloudflare speaks HTTP/1.1 to origins, so origin HTTP/2
  is unreachable in practice and not worth a duplicate-listen-options risk on a
  socket shared with fourteen other sites.
- **Ubuntu 22.04 packages no brotli module at all.** `brotli_static` is commented
  out in the config and must stay that way here; an unknown directive is fatal to
  startup, which on this box means fifteen sites. Debian 13 and Ubuntu 24.04 have
  `libnginx-mod-http-brotli-static` if the host is ever rebuilt.

## Cloudflare

Both names are proxied, orange cloud:

```
nightbell.app        A      <origin>     proxied
www.nightbell.app    CNAME  nightbell.app proxied
```

**The SSL/TLS mode is Full, not Full (strict).** That was established by
observation rather than from the dashboard: Cloudflare returned 200 for
`nightbell.app` while the origin was presenting a certificate for an unrelated
host, and Full (strict) would have answered 526. Two things follow:

1. The port 80 redirect in the config is safe. On **Flexible** it would not be:
   Flexible fetches the origin over plaintext, would receive the 301, fetch again
   and loop, with the site hard down. If the mode is ever changed to Flexible,
   delete the redirect and serve the site from the port 80 block.
2. **Full (strict) is worth turning on and is not yet.** Non-strict means
   Cloudflare accepts any certificate from the origin, so the encrypted
   edge-to-origin hop is not authenticated. The origin now has a real, publicly
   trusted Let's Encrypt certificate, so strict will simply work. One dropdown.

### The cache will defeat you if you forget it

The zone serves `Cache-Control: s-maxage=31536000` on some routes. **A deploy is
not live until the edge cache is purged**, and the failure mode is that the site
looks unchanged for a year. `deploy.sh` does not purge, because purging needs a
Cloudflare token and the token does not belong on the build machine by default:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"purge_everything":true}' \
  "https://api.cloudflare.com/client/v4/zones/<zone-id>/purge_cache"
```

A token scoped to this zone with **Zone → DNS → Edit**, **Zone → Cache Purge →
Purge** and **Zone → Zone → Read** is enough. Note that **Zone Settings → Read is
not included in that set**, which is why the SSL mode above had to be measured
instead of read.

Browser caches cannot be purged, which is why the media tier is thirty days rather
than a year. See "Caching" below.

## First-time setup

Skip to "Deploying" if the site is already running. This section is the record of
how it was brought up, and the recipe if the host is ever rebuilt.

The ordering problem: the real config needs a certificate, and getting a
certificate needs a server that can answer the ACME challenge. Hence two phases.

**1. Directories.**

```bash
sudo mkdir -p /var/www/nightbell.app/releases /var/www/nightbell.app/acme/.well-known/acme-challenge
sudo chown -R "$USER:www-data" /var/www/nightbell.app
sudo chmod -R 755 /var/www/nightbell.app
sudo mkdir -p /etc/nginx/snippets/nightbell

# The /download click log lives here. nginx refuses to start if the directory
# does not exist, which on a shared box takes all fifteen sites with it.
sudo mkdir -p /var/log/nginx/nightbell
```

**2. Upload the site**, so the bootstrap has something to serve:

```bash
NIGHTBELL_HOST=user@host ./deploy/scripts/deploy.sh
```

**3. Install the config, and enable the bootstrap only.**

```bash
sudo install -m 644 deploy/nginx/snippets/nightbell/*.conf /etc/nginx/snippets/nightbell/
sudo install -m 644 deploy/nginx/nightbell.app.conf deploy/nginx/nightbell.app.bootstrap.conf /etc/nginx/sites-available/
sudo ln -sfn /etc/nginx/sites-available/nightbell.app.bootstrap.conf /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

The bootstrap is port 80 only and **never redirects**, which is what makes it safe
under every SSL mode. Under Full it is not in the path of ordinary traffic at all,
so installing it changes nothing for visitors.

**4. Get the certificate.**

```bash
sudo certbot certonly --webroot -w /var/www/nightbell.app/acme \
  -d nightbell.app -d www.nightbell.app \
  --cert-name nightbell.app --non-interactive --agree-tos --dry-run
```

Drop `--dry-run` once it passes. Two details worth keeping:

- **`certonly`, not `--nginx`.** The nginx plugin rewrites config files in place.
  On a box serving fifteen sites, certbot should fetch a certificate and touch
  nothing else.
- **Every name in the request must resolve.** `www` had no DNS record at first, and
  including it would have failed the whole request, apex included. Add the record
  first, verify against the authoritative nameservers rather than a local resolver
  (`dig @rob.ns.cloudflare.com www.nightbell.app`), because a cached NXDOMAIN will
  lie to you for a few minutes.

HTTP-01 works through the Cloudflare proxy here; it is what the other thirteen
certificates on this box use, and they renew. If it ever stops working, a probe
file under `/var/www/nightbell.app/acme/.well-known/acme-challenge/` fetched over
plain `http://` tells you in one request whether the path still reaches the origin.

**5. Swap the bootstrap for the real config.**

```bash
sudo rm -f /etc/nginx/sites-enabled/nightbell.app.bootstrap.conf
sudo ln -sfn /etc/nginx/sites-available/nightbell.app.conf /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

Then purge the Cloudflare cache, or the edge keeps serving whatever it had.

The bootstrap file is left in `sites-available`, unlinked. It costs nothing and it
is what you want on hand if the certificate ever has to be reissued from scratch.

**6. Firewall, optional and worth it.** Only Cloudflare needs port 443:

```bash
sudo ufw default deny incoming
sudo ufw allow OpenSSH
for ip in $(curl -fsSL https://www.cloudflare.com/ips-v4) $(curl -fsSL https://www.cloudflare.com/ips-v6); do
  sudo ufw allow from "$ip" to any port 443 proto tcp
done
```

The stronger version is **Authenticated Origin Pulls**: turn it on for the zone and
uncomment the `ssl_verify_client` block in the site config, and Nginx rejects
anything that cannot present Cloudflare's client certificate. Without one of these
two, anyone who learns the origin address can bypass the edge, which makes every
Cloudflare rate limit, WAF rule and bot rule optional from an attacker's side. This
is per-server, so it does not affect the other fourteen sites.

## Deploying

```bash
NIGHTBELL_HOST=user@host ./deploy/scripts/deploy.sh
NIGHTBELL_HOST=user@host ./deploy/scripts/deploy.sh --dry-run
```

It runs `npm run verify` first, which checks the assets are in sync, builds, runs
the validator and then writes the precompressed files. It refuses to deploy if
`dist/index.html.gz` is missing, because `gzip_static` would then have nothing to
serve and every visitor would pay 155 kB for a 34 kB document.

Each build goes to `releases/<utc-timestamp>/` and `current` is moved onto it with
a single rename, so no request can observe a half-written root. The last five
releases are kept. Rollback is one symlink move and the script prints the exact
command when it finishes.

Then purge the Cloudflare cache.

## Counting downloads

The download buttons point at `nightbell.app/download`, not at GitHub. Nginx logs
the request and answers `302` to the APK, so the click is countable without any
JavaScript, any third party, any cookie or any consent banner, and the site's claim
that it carries no analytics stays literally true. Nothing was added to the page.

```bash
NIGHTBELL_HOST=user@host ./deploy/scripts/downloads.sh
NIGHTBELL_HOST=user@host ./deploy/scripts/downloads.sh --days 7
```

That prints two numbers side by side, and they answer different questions:

| | Source | Knows | Cannot know |
| --- | --- | --- | --- |
| **Clicks** | `/var/log/nginx/nightbell/download.log` on the origin | Dates, referrer, country | Whether the file actually arrived |
| **Downloads** | GitHub's asset `download_count` over the public API | The bytes moved | Where from, when, or by whom |

Clicks above downloads means people are cancelling. Downloads well above clicks
means the release is being fetched from somewhere that is not this site, which for
a project posted to Reddit and heading for an F-Droid listing is both expected and
the better direction.

**Neither is a count of people.** The log format stores no IP address on purpose:
it is personal data, keeping it starts a retention obligation and a lawful-basis
question on a site that currently has neither, and the questions worth answering
here are how many, from where, and from which link. The price is that clicks cannot
be deduplicated, so one person on a flaky connection looks like five people. The
full reasoning is in the `log_format` comment in `nightbell.app.conf`.

### Installing it

Three files, one of them generated:

```bash
npm run download-redirect     # regenerate snippets/nightbell/download-target.conf
sudo mkdir -p /var/log/nginx/nightbell
sudo install -m 644 deploy/nginx/snippets/nightbell/*.conf /etc/nginx/snippets/nightbell/
sudo install -m 644 deploy/nginx/nightbell.app.conf /etc/nginx/sites-available/
sudo install -m 644 deploy/logrotate/nightbell-download /etc/logrotate.d/
sudo nginx -t && sudo systemctl reload nginx
```

One thing the list above does not tell you, found on the first real install: nginx
creates `download.log` itself, as `root:root 0644`, because the master process opens
its logs before dropping privileges. The `create 0640 www-data adm` line in the
logrotate stanza only takes effect at the first rotation, which is a month away, so
until then the file is looser than every other log on the box and does not match
the policy this repo documents for it. Nothing breaks, `downloads.sh` uses `sudo`
either way, but the state and the documentation disagree for a month. Fix it once,
at install time:

```bash
sudo chown www-data:adm /var/log/nginx/nightbell/download.log
sudo chmod 640 /var/log/nginx/nightbell/download.log
```

Safe while nginx is running: it holds the file by descriptor, so ownership changes
do not interrupt writing and no reload is needed.

Check it from outside, and expect a `302` to a GitHub URL carrying the current
version:

```bash
curl -sI https://nightbell.app/download | grep -i '^HTTP\|^location\|^cache-control'
sudo logrotate --debug /etc/logrotate.d/nightbell-download   # dry run, no rotation
```

`deploy.sh` uploads `dist/` only, so **the Nginx config and the snippet are not
deployed by it.** After a release, `download-target.conf` changes and has to be
copied to the box and Nginx reloaded, or `/download` keeps handing out the previous
APK. `npm run verify` fails if the snippet has drifted from `RELEASE`, and the
validator fails if the page links to `/download` while the server block has no
`location` for it, so both halves of that mistake are caught before a deploy. What
neither can see is the config sitting un-copied on this machine.

### Why not Google Analytics

It cannot answer the question. The APK is served by GitHub, so a direct link makes
the click a cross-origin navigation and GA would only ever record that somebody
left. It also costs a `googletagmanager.com` and `google-analytics.com` exception in
a CSP whose whole point is `default-src 'self'`, plus a cookie banner under GDPR and
ePrivacy, on a landing page whose argument is that this app sends nothing anywhere.
For visitor counts and referrers beyond the download itself, the Cloudflare
dashboard already reports requests, unique visitors and countries for this zone with
no script on the page at all.

## Caching, in three tiers

Which tier a file is in depends only on whether its name changes when its contents
do.

| Path | Header | Why |
| --- | --- | --- |
| `/_astro/*` | `max-age=31536000, immutable` | Astro content-hashes these. A changed file is a different URL, so this can never be stale. |
| `/fonts/*` | `max-age=31536000, immutable` | Pinned latin subsets at fixed weights. Their bytes are not a moving target, and replacing a face is a deliberate act at which point the filename changes too. |
| `/media/*` | `max-age=2592000` | **Deliberately not a year.** These names stay the same when the promo is re-cut, so `immutable` would pin last month's frame in people's browsers for a year, where no Cloudflare purge can reach it. Lighthouse docks a point or two for this; that is the right trade. |
| `*.html`, `/` | `no-cache` | Permits storing, requires revalidating, so a release is live on the next request. A 304 is about 150 bytes. |
| `robots.txt`, `sitemap.xml`, `site.webmanifest` | `max-age=3600` | Cloudflare raises anything under its four hour browser-TTL floor, so these are observed as 14400. Harmless. |

The principled fix for the media tier is to route those files through
`astro:assets` so their names carry a content hash, at which point they move to the
`/_astro/` tier and can take the year safely. That is a change to the asset
pipeline, not to a cache header.

## The one Nginx footgun in here

`add_header` does not accumulate down the tree. **A location block containing a
single `add_header` of its own silently discards every `add_header` inherited from
its parent**, and the page still works. Every security header would simply stop
being sent for the paths that set their own `Cache-Control`.

That is why `security-headers.conf` is an include and why it appears in the server
block *and* in every location that sets a header of its own. Add a location, add
the include. The test that catches the mistake:

```bash
curl -sI https://nightbell.app/_astro/<any-hashed-file> | grep -ci content-security-policy
```

Anything other than `1` means that location is serving with no CSP.

## Verifying a deploy from outside

```bash
curl -sI https://nightbell.app/ | grep -iE 'HTTP|content-type|cache-control|strict-transport'
curl -s -o /dev/null -w '%{http_code}\n' https://nightbell.app/no-such-page      # 404
curl -sI https://www.nightbell.app/ | grep -i location                            # apex
curl -s -H 'Accept-Encoding: gzip' -o /dev/null -w '%{size_download}\n' https://nightbell.app/
```

The full suite that was run at cutover, all passing: `/` 200, a missing path 404
with the real 404 page, `www` 301 to apex, `/index.html` 301 to `/` with no
redirect loop, `.gz` and `.br` siblings not directly fetchable, `/404.html` not
directly fetchable, dotfiles 404, seven security headers present on 200 **and** on
404 **and** in every cache-tier location, range requests on the mp4, and an unknown
`Host` not served.

## What is deliberately not here

- **No `default_server`.** This box has none across all fifteen sites, so an
  unmatched `Host` is served by whichever block loads first, alphabetically. That
  is `anketa.conf`, and it is why `nightbell.app` served a coffee survey before
  this config existed. Adding a default here would change the fallback for every
  other domain aimed at this address, which is a decision for the box and not for a
  landing page. The suggested block is written out at the bottom of
  `nightbell.app.conf`; note that a default on 443 also needs a throwaway
  certificate, because 1.18 has no `ssl_reject_handshake`.
- **No `ssl_stapling`.** No public client ever sees the origin certificate;
  Cloudflare terminates TLS for every visitor and validates the chain itself.
- **No `Cross-Origin-Resource-Policy`.** `same-origin` is the header that stops
  other origins embedding `/media/nightbell-og.jpg`, which is what an Open Graph
  card is for. The protection it offers a site with no credentialed endpoints is
  close to nil, so the unfurl wins.
- **No CSP `report-uri`.** It needs an endpoint that collects reports, this site
  has no backend, and a policy reporting to nowhere reads like monitoring while
  being none.
- **No analytics on the page.** Not Google Analytics, not a cookieless hosted
  beacon, not a self-hosted one. Download counting is a `302` through `/download`
  logged by Nginx, which is server side by construction: no script, no cookie, no
  consent banner, no CSP exception, and nothing about a visitor leaving this box.
  See "Counting downloads". The deliberate cost is that there is no funnel and no
  session, only clicks with a referrer.
- **No CI deploy.** The APK hash in `site.config.mjs` has to be measured from the
  artifact as GitHub serves it, which is a step a human does. A pipeline that
  deployed without it would publish an unverified hash, and that is the one number
  on the site that must never be wrong.

## The honest weak points

**`style-src` carries `'unsafe-inline'`.** Astro is configured with
`inlineStylesheets: 'always'`, so the stylesheet arrives in a `<style>` block, and
the page carries 121 `style="..."` attributes. A hash covers the block but
attributes need `'unsafe-hashes'` plus one hash each, and 121 hashes in a header is
not a security control. What it costs is narrow: CSS injection stays possible if
markup injection is ever possible, and on a site built from static files with no
user input, no query parameters and no backend, markup injection has no route in.
`script-src` is strict with no `'unsafe-inline'` and no hashes, verified against the
build rather than assumed: it emits zero inline handlers and zero inline scripts.

**`connect-src` is `'self'` rather than `'none'`.** The page makes no fetch, no XHR
and no WebSocket, so `'none'` was correct and is what shipped first. It was
relaxed for one measurable reason: auditors read `robots.txt` with a `fetch()` from
the page, `'none'` blocks that, and every Lighthouse run reported "unable to
download a robots.txt file" and docked the SEO score by eight points for a file
that is served correctly. Real crawlers request `robots.txt` directly and were
never affected. What the relaxation gives up is that an injected script could read
same-origin URLs; what it keeps is that nothing can be sent anywhere else, because
no other origin is allowed.
