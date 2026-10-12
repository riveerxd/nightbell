# nightbell.app

The site, live since 2026-10-12. Next.js with static export, Tailwind 4, Magic UI
components installed through the shadcn registry, three.js through
react-three-fiber for the hero phone, and Lenis for wheel scrolling. The Astro
site it replaced is in history: the last commit that has it is the one before
this directory changed over.

## When a release ships

This is steps 9 and 10 of `docs/FDROID.md`, "Releasing a new version", written
out for this directory.

1. Edit `RELEASE` in `src/lib/site.ts`: `version`, `tag`, `apkName`, `apkUrl`,
   `apkBytes`, `apkSha256`, `versionCode`, together. Take the byte count and the
   digest from the asset downloaded back off the GitHub release, not from the
   local build:

   ```bash
   curl -sL -o /tmp/nb.apk https://github.com/riveerxd/nightbell/releases/download/vX.Y.Z/Nightbell-X.Y.Z-release.apk
   stat -c %s /tmp/nb.apk && sha256sum /tmp/nb.apk
   ```

2. `npm run build`. It rewrites `public/v1/release.json`, which every installed
   app reads every six hours, and `download-target.generated.conf`, the
   `/download` redirect, both from that block. Commit `public/v1/release.json`
   with the bump: the app's JVM suite reads it to prove the shapes still match.
3. If the release changed what a page says (a new monitor kind, a new limit, a
   renamed setting), change the copy too. The reference page is
   `content/reference.md`, the FAQ is `src/lib/faq.ts`, and the homepage sections
   are in `src/components/site/`.
4. `NIGHTBELL_HOST=<origin> ./deploy/scripts/deploy.sh`. The origin address is
   deliberately not written down in this public repository. The script stops if
   `RELEASE` disagrees with GitHub's latest tag.
5. Purge Cloudflare:

   ```bash
   source ~/.config/cloudflare/nightbell.env
   curl -s -X POST "https://api.cloudflare.com/client/v4/zones/$CLOUDFLARE_ZONE_ID/purge_cache" \
     -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" -H "Content-Type: application/json" \
     --data '{"purge_everything":true}'
   ```

6. Check it from outside:

   ```bash
   curl -s https://nightbell.app/v1/release.json            # the new version
   curl -sI https://nightbell.app/download | grep -i location # the new APK
   curl -sI https://nightbell.app/v1/release.json | grep -i 'cache-control\|cf-cache-status'
   ```

   `no-store` and `DYNAMIC` on the manifest are what keep the install count
   working. A `HIT` means Cloudflare answers for the origin and nothing gets
   counted.

For a page change with no release, it is step 2 to step 6 without the bump.

## Running it

```bash
cd website
export PATH=$HOME/.nvm/versions/node/v22.18.0/bin:$PATH
npm run dev                 # http://localhost:3000
npm run build               # static output in out/
npx serve out -l 4173       # what the e2e suite runs against
# four workers, set in the config: headless Chromium renders the hero's WebGL
# on the CPU, and eleven at once starved each other into timeouts
npx playwright test --project=desktop-chrome --project=laptop-chrome \
  --project=tablet-chrome --project=mobile-chrome --project=mobile-small \
  --project=desktop-firefox --project=mobile-firefox

# WebKit needs libraries this distro does not ship, so it runs in the
# official image against the same mounted build
docker run --rm --network host --ipc=host -u "$(id -u):$(id -g)" -e HOME=/tmp \
  -v "$PWD":/work -w /work mcr.microsoft.com/playwright:v1.64.0-noble \
  npx playwright test --project=desktop-safari --project=iphone-safari
```

## What changed and what did not

Every fact on the old site is still here: the release numbers, the digests, the
channel list, the limits, all sixteen FAQ answers and the full reference. The
copy around them was tightened, and the layout is new.

The facts live in `src/lib/site.ts`, a straight port of
the old `site.config.mjs` without its comments. When a release ships, that file
needs the same edit the old one did.

The hero is the product doing its job. A WebGL phone rests beside the headline
showing the real monitor list; as you scroll it turns to face you while 03:14
comes up behind it, the lockscreen alert lands, the screen goes red and it
buzzes, and then the camera falls into the screen until the urgent page is the
page. Acknowledge works. The three screens are frames cut from
`public/media/nightbell-alert-firing.mp4` into `public/hero/`. Without WebGL,
before it loads, and under reduced motion, the phone is an image and the stage
is an ordinary first screen. The scene draws on demand and stops once the alarm
covers it.

Depth is the visual system: cards sit at three elevations (`shadow-elev-1` to
`3`, tokens in `globals.css`) instead of hairline borders, screenshots are real
phones in CSS frames tilted in 3D, and `Parallax` in `components/site/depth.tsx`
moves layers at different speeds. The comparison is a sticky stack, the three
kinds of check pan sideways on desktop, and the wizard is a fanned deck.

Type is Archivo throughout: pulled to its narrowest width in capitals for
display, normal width for reading. Doto for clocks and counts, JetBrains Mono for
labels.

## Magic UI components that were changed

Several came out of the registry broken, so they were fixed in place rather than
used as shipped.

- `android.tsx`: the screen image was offset from its clip, the viewBox was wider
  than the phone, the camera sat under the screen and every phone shared one
  clipPath id. Also takes live HTML for the screen now.
- `number-ticker.tsx`: shipped its start value in the HTML, so with no script the
  page claimed the wrong number.
- `magic-card.tsx`: read the theme from a provider this site does not have.
- `terminal.tsx`: set state synchronously inside effects. No longer on the page:
  a typed-out terminal is decoration, so the install section shows the real
  command with a copy button instead.
- `accordion.tsx`: now native `<details>`. The Radix version unmounted closed
  panels, so FAQ answers were missing from the HTML the FAQPage data describes.
- `animated-theme-toggler.tsx`: drew the moon for a frame on every load before
  it learned the theme.
- `shimmer-link.tsx` is `shimmer-button.tsx` as an anchor, because the download is
  a link and a button inside a link is invalid.

## Deploying

```bash
NIGHTBELL_HOST=<origin> ./deploy/scripts/deploy.sh
```

It builds, checks that `RELEASE` in `src/lib/site.ts` matches GitHub's latest
tag, refuses if any log format would record an address, installs the nginx
config from `deploy/nginx/` and the three logrotate rules, with a backup of
`/etc/nginx` taken first and restored if `nginx -t` fails, uploads `out/` into
`releases/<stamp>/` and moves the `current` symlink onto it in one rename. Then
purge the Cloudflare cache.

Every build also writes the files the server needs from that same `RELEASE`
block and from the pages themselves: `public/v1/release.json`, which every
installed app reads and the install census counts; `download-target.generated.conf`,
the `/download` redirect; `csp.generated.conf`, the CSP with this build's inline
script hashes, without which no page hydrates under the site's `script-src
'self'`; and a `.gz` beside every text file for `gzip_static`.

`/download` and `/v1/release.json` are nginx locations with their own logs in
`/var/log/nginx/nightbell/`, so a deploy never touches the download or install
counts.

## Screenshots

Every app screen on the site is a real capture off the app, 1440 by 3200, kept
in `public/screens-hd/` (kept locally, not in git) and exported to 1080 wide webp
under `public/hero/` and `public/screens/`. `tools/shots/` has the
instrumented test and script that took them. The e2e suite fails if a
screenshot under 1080 wide comes back.
