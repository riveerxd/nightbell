# website

The Nightbell landing page. Static HTML out of Astro, meant to be dropped behind
Nginx.

One page, one stylesheet, 9.4 kB of JavaScript covering the video player and
every instrument on the page, and the page is complete with none of it running.
The only runtime dependency is `astro`; `parse5` and `sharp` are dev
dependencies, used by the validator and by the screenshot pipeline.

## Commands

```bash
cd website
npm install

npm run assets        # copy the screenshots, video and fonts out of the repo
npm run screens       # cut the phones out of the plates, drop the baked ground
npm run dev           # dev server on :4321
npm run build         # static output into dist/
npm run preview       # serve dist/ on :4321
npm run validate      # check dist/ for the things a browser hides
npm run precompress   # write .br and .gz next to every compressible file in dist/
npm run verify        # assets:check + screens:check + build + validate + precompress
./scripts/shoot.sh    # inspection screenshots at 1440x900 and 390x844

NIGHTBELL_HOST=user@host ./deploy/scripts/deploy.sh    # build, upload, switch
```

`npm run assets` is not part of `build`. It shells out to `ffmpeg` and
`rsvg-convert` and takes a second or two, and the inputs only change when the
app ships a new release, so it is a step you run rather than a tax on every
build. `npm run verify` calls `assets:check`, which fails if a copy has drifted
from its source in the repository.

`npm run screens` is the second half of that, and it is the reason no screenshot
on this page arrives with a background. The shots the app pipeline produces are
opaque rasters with a blue-black gradient and a drawn phone shell baked into
them, composed for the README. This cuts the phones off the plates, crops each
one to its screen, and writes transparent assets into
`src/assets/screens/clean/`, which is what the page imports. Sources are never
edited. Every frame, bezel, rim, shadow and radius you see around a screenshot is
drawn by `global.css`, so it can answer to the section it is in.

`npm run screens:check` asserts that every derived asset still has four
transparent corners, which is what fails the build if a ground ever gets baked
back in.

`npm run precompress` writes a `.br` and a `.gz` beside every compressible file in
`dist/`, at Brotli quality 11 and gzip level 9 rather than the much lower quality
worth paying for on a per-request basis. Nginx's `gzip_static` prefers them and
falls back to compressing live when one is absent, so nothing here is load-bearing
for correctness, which is what makes it safe to run last. `index.html` goes out as
34 kB instead of 155 kB.

Worth being accurate about the size of that win: behind Cloudflare's proxy the edge
decompresses and recompresses for the visitor, so a browser receives Cloudflare's
Brotli either way. What precompressing buys is origin CPU and a smaller
origin-to-edge hop, not the number a browser sees. The `.br` files are written
regardless because they cost 26 kB on disk and they are what serves the site
correctly if the proxy is ever turned off.

## What is where

```
site.config.mjs        the domain, the repo URLs, the release facts, the promo
                       facts, the SEO strings
astro.config.mjs       static output, inlined CSS, image handling
src/
  layouts/Base.astro   head, metadata, JSON-LD, landmarks. Takes `robots` and
                       `video` props so each page states its own truth
  pages/index.astro    the whole landing page, and the FAQ array both the section
                       and the FAQPage schema are generated from
  pages/404.astro      the 404. One number, one status line, one sentence, one
                       button. See DESIGN_NOTES.md
  pages/robots.txt.ts  generated, and aware of the placeholder domain
  pages/sitemap.xml.ts generated from one list, normalised to match the canonical
  pages/site.webmanifest.ts
  components/          Mark and Glyph (the app's own icon paths), Footer, Section,
                       Trace, Instrument, Shade, PathDiagram, Wizard, Escalation,
                       Player
  scripts/player.ts    the player's behaviour
  scripts/motion.ts    the reveals, the masthead readout, the pinned payoff stage,
                       the hero replay and the setup stepper. Both scripts ship in
                       one module and the page is complete with neither running
  styles/global.css    the entire design system
  media.json           measured clip durations, sizes, dimensions and chapter
                       marks. Committed, because its source is not in the repo
  assets/screens/      the plates, exactly as the app pipeline produced them
  assets/screens/clean/ what the page actually imports: product UI, no ground
scripts/
  sync-assets.mjs      pulls binaries out of the repo, derives posters and icons
  clean-screens.mjs    finds the phones on the plates and takes the render's
                       ground and drawn shell off them
  validate.mjs         the regression suite that runs against dist/
  precompress.mjs      writes the .br and .gz siblings Nginx serves
  shoot.sh             screenshots
deploy/                the Nginx config, the snippets and the deploy script.
                       Start at deploy/README.md
screenshots/           inspection output, gitignored and regenerable
DESIGN_NOTES.md        the research, the decisions, and what was refused
SEO_NOTES.md           title, description, structured data, and the measurements
```

`Footer.astro` exists because it was written inline in `index.astro` and then a
second, shorter footer was written for the 404, which is how a site ends up with two
footers that disagree about what the licence is. There is one footer, both pages
render it, and the only thing that differs is where the brand lockup points.

## The domain

`SITE_URL` is `https://nightbell.app`, and it is live. The DNS is on Cloudflare with
both the apex and `www` proxied, the origin serves it over HTTPS with a Let's
Encrypt certificate covering both names, and the three indexing locks that used to
be active have all released: the build does not warn, the page does not carry
`noindex`, and `robots.txt` does not serve `Disallow: /`.

`.app` is on the HSTS preload list, so there is no plaintext fallback and no way to
click through a certificate warning. Any host serving this origin needs a real
certificate from the first request.

Changing the origin again is one line in `site.config.mjs`. See `SEO_NOTES.md` for
what reads from it, and `deploy/README.md` for what is actually running.

## Assets, and where they come from

Almost nothing under `public/media`, `public/fonts` or `src/assets` is authored
here. `scripts/sync-assets.mjs` copies what it can out of the repository and records
the size and SHA-256 of each source in `assets.lock.json`:

| Site file | Source |
| --- | --- |
| `public/media/nightbell-promo.mp4` | `docs/video/nightbell-promo.mp4` |
| `public/media/nightbell-alert-firing.mp4` | `docs/video/nightbell-alert-firing.mp4` |
| `src/assets/screens/*.png` | `docs/screens/*.png` |
| `src/assets/screens/clean/*.png` | the row above, via `npm run screens` |

Derived by the same script: `nightbell-promo-poster.webp` and
`nightbell-alert-firing-poster.webp` (ffmpeg at chosen frame times, WebP quality
78), `nightbell-og.jpg` (1200 x 630, cropped from 16:9, and **a JPEG on purpose**
because social scrapers are not browsers), `favicon.svg` (the brand master
`docs/brand/nightbell-mark-icon.svg`, read off disk rather than transcribed), and
the 192, 512 and 180 pixel PNG icons.

### Two files are committed rather than derived

`public/fonts/*.woff2` and `src/media.json` are **in the repository**, which breaks
the rule above, and the reason is that their source is not.

The two faces were lifted out of `promo-video/node_modules/@fontsource-variable`
and the chapter table was parsed out of `promo-video/src/NightbellPromo.tsx`, and
`promo-video/` is not committed: it is 52 MB of Remotion project and intermediate
audio that exists to produce one mp4, and that mp4 is committed on its own under
`docs/video/`. 96 kB of derived files here is the cheaper half of that trade, and it
is the half the site cannot build without.

So `sync-assets.mjs` detects whether the cut is in the working tree:

- **Present**: the fonts are re-copied and the chapter table is rebuilt and
  cross-checked against the measured duration, exactly as before.
- **Absent**: the committed copies are used as-is, and that is a normal build rather
  than a broken one. `assets.lock.json` keeps the typeface hashes it recorded last
  time so the lock does not churn depending on who ran the script.

The chapter table is **merged forward** rather than overwritten in the absent case.
`media.json` is rebuilt from ffprobe on every run, so writing it out with the video
project missing would delete the six chapters and take the scrubber markers and
every chapter button off the player, silently, because a player with no chapters
still plays.

The honest cost: with the project absent, the script can no longer prove the fonts
and the chapter table match the cut they came from. Re-render with the project in
place, which is the same machine that produced the mp4 anyway.

### Swapping the promo video

The hero plays whatever is in `docs/video/nightbell-promo.mp4`. It was re-cut from
55.5 s to 40 s while this page was being built, which is exactly why nothing
about it is typed into the copy:

- Duration and file size are measured by `sync-assets.mjs` into `src/media.json`
  and printed from there. The player also uses the measured duration for its
  readout, so it says `0:00 / 0:40` before a byte of video is fetched.
- The poster and the Open Graph image are cut from the current file at build
  time, so they cannot show a frame the video no longer contains.
- The player's chapter buttons and scrubber markers come from `SCENES` and
  `OVERLAPS`, parsed out of `promo-video/src/NightbellPromo.tsx` and folded into start
  frames the same way `narration.mjs` does it. Re-budget a scene and the chapters
  move with it.
- `sync-assets.mjs` cross-checks the frame arithmetic against the measured
  duration and reports a problem if they disagree by more than 0.15 s, which is
  how you find out the file on disk is a different render from the source tables.

To swap in a new render:

```bash
cd promo-video && npm run render        # writes ../docs/video/nightbell-promo.mp4
cd ../website && npm run assets && npm run verify
```

Then update `public/media/nightbell-promo.en.vtt` by hand. The cue text is the
narration verbatim from `promo-video/scripts/narration.mjs`, cue starts are the
`at` frames divided by 30, and cue ends are the start plus the measured length
of the matching take in `promo-video/audio/vo/`. `npm run voiceover` in
`promo-video/` prints that table. This is the one thing the sync script cannot
do for you, and a caption track that has drifted from the audio is worse than
none.

## What `npm run validate` checks

It runs against `dist/`, because that is what Nginx serves, and it walks **every**
HTML file there, so the 404 is held to the same invariants as the landing page.

- Em dashes and en dashes, in every source file and in the built HTML. The scan
  covers `src`, `scripts`, `public` and `deploy`, the last because the Nginx config
  and the deploy scripts are prose as much as configuration and a house rule that
  stops at a directory boundary is a house rule with an exception nobody remembers
- Exactly one `h1`, exactly one `main`, and no skipped heading levels
- `lang`, `title` length, description length, an absolute canonical
- Open Graph and Twitter tags, including `og:image:alt`
- The favicon and manifest links, and that the manifest parses
- JSON-LD parses, and includes both `SoftwareApplication` and `WebSite`
- Every `img` has alt text and explicit dimensions
- Every `video` has a poster, dimensions, controls, and a caption track unless it is
  muted
- Every player figure keeps its no-script fallback: a `<video controls>`, a control
  bar that ships `hidden`, and a `data-duration` for the readout
- Every local `href`, `src`, `poster` and `srcset` entry exists in `dist/`
- Every link and button has an accessible name
- Duplicate ids, in-page anchors pointing at ids that are not there
- `robots.txt`, `sitemap.xml`, `site.webmanifest` and `favicon.svg` exist
- The JavaScript budget

Warnings do not fail the run. Errors do.

What it does **not** check, and what caught real bugs anyway: HTML validity against
the spec, and Lighthouse's audits. Both are worth running by hand after a change of
any size, and both are open source:

```bash
# W3C Nu validator, against the built page
curl -sS -H 'Content-Type: text/html; charset=utf-8' \
     --data-binary @dist/index.html 'https://validator.w3.org/nu/?out=json'

# Lighthouse, against a preview or the live URL
npx lighthouse http://localhost:4321/ --view
```

The validator found four things `validate.mjs` had no opinion about: a `figcaption`
sitting mid-`figure`, seven `<time>` elements whose text was not a machine-readable
time, an `aria-label` on a `<p>`, and `hidden` on an `<svg>`. All four are fixed and
documented where they live. Both pages are now 0 errors, 0 warnings.

## Deploying

**`deploy/README.md` is the real runbook.** What is written there is what is
running: Nginx 1.18 on Ubuntu 22.04, a Let's Encrypt certificate covering the apex
and `www`, Cloudflare proxying in front on SSL mode Full, and an origin that is a
shared production box serving fifteen sites.

```bash
NIGHTBELL_HOST=user@host ./deploy/scripts/deploy.sh
```

That runs `npm run verify`, uploads `dist/` to `releases/<utc-timestamp>/`, moves
the `current` symlink with a single rename so no request sees a half-written root,
reloads Nginx after testing the config, and prunes to the last five releases. It
prints the rollback command when it finishes.

Then **purge the Cloudflare cache**, or the edge keeps serving the previous build.
That step is not in the script because it needs a token, and the token does not
belong on the build machine by default.

Three things about that setup are easy to get wrong and are covered in detail there:

- `add_header` does not inherit. A location block with one `add_header` of its own
  silently drops every inherited one, which is how a site loses its CSP on exactly
  the paths that set a `Cache-Control`.
- `build.format` is `'file'`, so routes are emitted as `index.html`, `404.html`,
  `robots.txt`, `sitemap.xml` and `site.webmanifest` at the root. There is no
  `$uri.html` fallback in the config: two documents exist and the only
  extensionless path that resolves is `/`, so an extensionless request is a mistake
  and belongs at the 404 rather than being guessed at.
- The host address is not in this repository, on purpose.

## Facts on the page that are worth re-checking on a release

All of these live in `site.config.mjs`:

- `RELEASE.version`, `tag`, `apkName`, `apkUrl`
- `RELEASE.apkBytes` and `apkSha256`, printed on the page as a verification
  block. Regenerate with `sha256sum` against the asset attached to the release,
  not against a local build.
- `RELEASE.signingCertSha256`, read with
  `apksigner verify --print-certs <apk>`. This only changes if the signing key
  does, which would be a much bigger event than a release.
- `minAndroid`, `targetSdk`

The test counts, the 15 minute floor and the four grants are in the page copy
and come from `README.md` and `docs/reference.md`. If those change, the copy
changes with them.
