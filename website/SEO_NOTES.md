# SEO notes

What this page is trying to rank for, what it emits, and the one thing that is
still blocked on a decision nobody has made yet.

## The domain, first

`SITE_URL` in `site.config.mjs` is `https://nightbell.app`. It feeds the
canonical, the Open Graph URL, three JSON-LD `@id`s and the sitemap, and it is
the only place any of them is configured.

The name was chosen because the previous one could not be defended. Every
plausible `pulse*` domain in this category already belongs to a shipping
product: Pulse Pager and Pulse UpTime both sell uptime monitoring, and two more
sit alongside them. A search for "pulse uptime monitoring" returned four
competitors before it returned this. "Nightbell" collides with nothing in
software, so the brand term is winnable from the first day it is indexed, and
the category terms are left to the title, the headings and the body copy where
they actually carry weight.

Three indexing locks were active while the origin was a reserved `.example`
host, and all three released automatically when it became real:

1. `robots.txt` no longer serves `Disallow: /`.
2. The page no longer carries `<meta name="robots" content="noindex, follow">`.
3. `npm run build` and `npm run validate` no longer warn.

**All of that is done.** The domain is registered, the DNS is on Cloudflare, the
origin serves it over HTTPS with a Let's Encrypt certificate covering the apex and
`www`, and the site is live. See `deploy/README.md` for what is running and why.

Three things are left, and none of them is a code change:

1. Submit `https://nightbell.app/sitemap.xml` to Search Console and Bing Webmaster
   Tools.
2. Set the repository's homepage field to the same URL.
3. Re-check the Open Graph card in a real unfurl, since the image is regenerated
   from whatever the current promo render is.

## Title and description

```
Nightbell: Android uptime monitoring with no server or account
```

62 characters. It leads with the product name, because the name is what people
type after seeing the repository or a post, and follows with the two facts that
separate it from every other result on the page. "Android uptime monitoring" is
the head term worth being on. "no server and no account" is the differentiator
and reads as a benefit rather than as keywords.

```
Nightbell is an open-source Android uptime monitor. Checks run on your phone, API
tokens stay on the device, and an outage arrives as a full-screen page with a
looping alarm instead of a notification you swipe away.
```

211 characters, which will be truncated in some results and is written so the
first 155 stand on their own. It repeats none of the title's phrasing verbatim.

## What the page is realistically competing for

This is a niche tool with one star and no backlinks. The honest read from
`docs/GROWTH.md` applies here too: a landed launch plus store listings is
hundreds of people, not thousands. So the terms worth targeting are the long,
specific ones where the page can actually be the best answer:

| Term | Where the page answers it |
| --- | --- |
| uptime monitoring on Android | H1, the hero lede, section 03 |
| uptime monitor without a server | H1, section 02's comparison table |
| private uptime monitoring, self-hosted alternative | Section 03, the FAQ answer on what leaves the phone |
| free UptimeRobot alternative | Section 02's comparison, the FAQ answer that names both |
| monitor a website from your phone | Sections 03 and 04 |
| full-screen alert Android, alarm until acknowledged | Section 06 |
| monitor a page element for changes | Section 04, the page-element row |
| Android 15 minute background limit | Section 07, and the FAQ answer on cadence |

UptimeRobot and Uptime Kuma are named twice, in the comparison caption and in
one FAQ answer, and in both places the answer says what those tools are better
at. That is deliberate. A comparison that only flatters the thing selling itself
is the kind of page a reader stops trusting, and the honest version is more
likely to be linked to.

There is no keyword-stuffed paragraph anywhere. Every term above is in copy that
would be there regardless.

## Heading structure

One `h1`, carrying the canonical positioning verbatim. Nine `h2`s, one per
section, each written as a sentence rather than a label. `h3`s inside sections
for the three check kinds, the four setup steps, the install channels and two
subheads in the prose. `npm run validate` fails the build if a second `h1`
appears or a heading level is skipped.

## Structured data

Five entities in the page head, all emitted as JSON-LD and all validated as parsing
by `npm run validate`.

| Type | What it claims |
| --- | --- |
| `SoftwareApplication` | Name, category, `Android 8.0+`, version, file size, download URL, licence, `offers` at price 0, feature list, code repository, help URL |
| `WebSite` | The site itself, linked to the publisher and to the application |
| `Person` | The author, linked to the GitHub profile |
| `VideoObject` | The promo: name, description, poster, duration, dimensions, licence, linked to the app and the author |
| `FAQPage` | All ten questions and answers, generated from the same array the visible section renders from |

`VideoObject` earns its place because every field in it is measured or written down
rather than inferred: `PT40S` and `1920x1080` come from ffprobe by way of
`src/media.json`, the poster is a real frame cut from the render, and the file is
served from this origin. A video on a page is also the one thing here eligible for
a rich result the page does not otherwise qualify for.

It is emitted on the landing page only, through a `video` prop on `Base.astro` that
defaults to false. The 404 does not contain the video, and a 404 telling Google it
has a forty second film on it is a claim the page cannot support. The `og:video`
tags are gated by the same prop for the same reason.

Deliberately absent, because they would be untrue or unsupportable:

- **`aggregateRating`.** There are no reviews. Fabricating one is the single most
  common way a small site earns a manual action.
- **`interactionStatistic`.** The real download count is in single digits.
  Publishing it would be honest and unhelpful; omitting it is honest and neutral.
- **`screenshot`.** The images on the page are already crawlable with real alt
  text, and padding the field out with the same picture twice buys nothing.
- **`Clip`, for the six video chapters.** The timings are real and sitting in
  `src/media.json`, so this one is tempting. Google's Clip markup requires each part
  to carry a URL that starts playback at that offset, and the player does not read a
  time fragment, so every one of those URLs would be a link that does not do what
  the markup promises. Teaching the player `?t=` is the prerequisite, not the
  markup.

On `FAQPage` specifically: Google retired FAQ rich results for most sites in 2023,
so this is not expected to produce stars or accordions in the SERP. It is included
because it is accurate, it costs about 3 kB, and it is still read by other
consumers. Do not treat it as a ranking tactic.

## Metadata inventory

Emitted from `src/layouts/Base.astro`, all derived from `site.config.mjs`:

- `<title>`, `<meta name="description">`, `<link rel="canonical">`
- `robots`, as **one tag per page** rather than a default plus an override. The
  landing page sends `index, follow, max-image-preview:large, max-snippet:-1,
  max-video-preview:-1`; the 404 sends `noindex, follow` through the same prop. The
  three `max-` directives are Google's own opt-ins for a large thumbnail, an
  untruncated snippet and an unlimited video preview, and there is nothing here
  worth withholding from a preview. Emitting a default and then adding a second,
  contradicting tag through the head slot is the failure mode this prop exists to
  avoid.
- `hreflang` `en` and `x-default`, both self-referencing. One language, said out
  loud, so a crawler is not inferring an unlabelled locale from the content.
- `theme-color` (`#000000`), `color-scheme`, `author`, `viewport`, `lang="en"`
- Open Graph: `type`, `site_name`, `title`, `description`, `url`, `locale`, `image`,
  `image:secure_url`, `image:type`, `image:width`, `image:height`, `image:alt`
- Open Graph video, landing page only: `video`, `video:secure_url`, `video:type`,
  and `video:width`/`video:height` taken from the measured render rather than typed.
  They were briefly hardcoded as 1280x720, which is the poster's size and not the
  video's; `sync-assets.mjs` now records the real 1920x1080 into `src/media.json`.
- Twitter: `summary_large_image`, `title`, `description`, `image`, `image:alt`
- `icon` (SVG), `apple-touch-icon` (180 px), `manifest`
- Two font preloads

The Open Graph image stays a **JPEG** while the two video posters are now WebP.
Browsers all support WebP, but social scrapers are not browsers and several still
refuse anything but JPEG or PNG, and a card that fails to render is worse than a
card that is 50 kB. Its alt text is written out, not generated.

`Content-Type` carries `; charset=utf-8` from Nginx (`charset utf-8`), not just from
the `<meta>` tag. The header is the authoritative declaration and the meta tag is
the fallback; auditors flag the missing header specifically, and they are right to.

## Sitemap and robots

`src/pages/sitemap.xml.ts` writes the sitemap by hand from a one-entry list.
`@astrojs/sitemap` is the right call the moment there is a second page; for one URL
it is a dependency and a config block to produce eight lines of XML.

The sitemap applies **the same normalisation as the canonical link**, and has to:
`new URL('/', ...)` produces a trailing slash while the canonical strips it, so the
sitemap was submitting `https://nightbell.app/` for a page whose canonical said
`https://nightbell.app`. Search engines reconcile that themselves, but a sitemap
whose whole job is to state the canonical URL should not need reconciling.

`lastmod` is omitted on purpose. Stamping it at build time would say "this page
changed" every time anything in the repository is rebuilt, which is exactly the
signal `lastmod` exists to carry and exactly the one it would then stop carrying.
Add it from real edit dates or not at all. The same reasoning governs
`MEDIA.promoUploaded`, which is a written-down constant rather than `new Date()`.

`robots.txt` is generated from the same config value, so it cannot point at a
sitemap on a different host than the canonical claims.

### robots.txt and the CSP, which is worth knowing about

Lighthouse reported **"robots.txt is not valid"** and docked eight SEO points while
the file was served correctly at 200 with the right content type. The real message
underneath was *"unable to download a robots.txt file"*: Lighthouse fetches it with
a `fetch()` from the page context, and the CSP said `connect-src 'none'`, which
blocked it.

Real crawlers request `robots.txt` directly over HTTP and were never affected, so
the ranking impact was nil, but a permanent false failure in the one report anybody
runs is its own kind of cost. `connect-src` is now `'self'`. See the note at the end
of `deploy/README.md` for what that trades away, which is less than it sounds.

## Crawlability and rendering

The page is static HTML. No client-side rendering, no hydration, no framework
runtime. The only script is the 4.5 kB video player, and every word of copy is in
the document before it runs; both videos are playable before it runs too, on the
browser's own controls. The FAQ answers are inside `<details>` elements, which are
in the DOM whether or not they are open.

## Images, media and layout stability

- Screenshots go through `astro:assets`: WebP, four widths each, `sizes` computed
  from the real content column width, and `width`/`height` on every `img` so the box
  is reserved before the bytes arrive.
- **Everything below the fold is `loading="lazy"`, and now that is actually true.**
  It was written here as a claim while eight images across `Escalation.astro`,
  `Wizard.astro` and `index.astro` carried an unexplained `loading="eager"`. The
  result was twenty requests and 401 kB fetched on first load for content nobody
  had scrolled to. Removing the overrides, which is all it took because Astro's
  `<Image>` already defaults to lazy, took the first load to nine requests and
  176 kB and mobile Lighthouse performance from 95 to 98. If an `eager` is ever
  added back, it needs a comment saying why.
- Both video posters are **WebP**, cut at 1280 px, quality 78. At the same width
  that is 53 per cent smaller than the JPEGs they replaced: 51 kB became 24 kB and
  37 kB became 17 kB. It was the one scored failure in an otherwise passing
  Lighthouse run, and on a `preload="none"` video the poster is the only image bytes
  the section costs until somebody presses play. Quality was checked at 72, 78 and
  84 rather than assumed, because the frame is a flat red alert plate and that is
  exactly what a low setting bands.
- 1280 px is kept even though Lighthouse calls it oversized on a phone. A `poster`
  attribute takes one URL and has no `srcset`, so a single size has to serve a
  370 px phone and a 972 px desktop, and erring toward the desktop is the right way
  round: the phone wastes bytes, the desktop would waste sharpness.
- Both videos carry `width`, `height`, a poster and `preload="none"`, so the hero
  costs a poster rather than 6 MB.
- The two fonts are preloaded, which is the only preload on the page. Nothing else
  is, because a preload that is not on the critical path is a request stolen from
  one that is.
- `npm run validate` fails if any image is missing dimensions or alt text.

## Measured, not assumed

Run against the live URL with Lighthouse 12 and the W3C Nu validator, both open
source. Numbers move between runs; these are stable across three.

```
              perf   a11y   best-practices   SEO
desktop        100    100         100        100
mobile          98    100         100        100

TBT 0 ms   CLS 0   desktop LCP 0.5 s   first load 176 kB
W3C Nu validator: 0 errors, 0 warnings on both index.html and 404.html
```

Getting there fixed real things: the `connect-src` false failure above, the eager
images, JPEG posters, a missing charset header, and four HTML validity bugs (a
`figcaption` sitting mid-`figure`, seven `<time>` elements with no machine-readable
value, `aria-label` on a `<p>`, and `hidden` on `<svg>`).

**Mobile performance is 98 and that is where it stops.** The remaining gap is LCP
around 2.3 s against the ~1.4 s that would score 100, and the cause is measured
rather than guessed: 512 ms of observed Style & Layout, 1087 ms of total main-thread
work, multiplied by Lighthouse's 4x CPU throttle. It is style and layout over 1,074
DOM elements against roughly 100 kB of inlined CSS.

Two candidate fixes were tried and **both failed, and both were reverted**:

- `content-visibility: auto` on off-screen sections, which is the textbook fix for
  exactly this. LCP render delay 1503 ms before, 1503 ms after. No effect.
- `font-display: optional`, to test whether the headline repainting when Inter
  arrives was driving LCP. Also 1503 ms. Not the font either.

What would move it is splitting the CSS so less of it is parsed before first paint,
which contradicts the `inlineStylesheets: 'always'` decision and risks a flash of
unstyled content, or cutting DOM size, which is deleting content. Neither is worth
two points that no visitor experiences: TBT is 0 ms and CLS is 0.

## Internal and external links

Eleven outbound links, all to the project's own repository: the releases page,
the direct APK, the reference docs, the README, the licence, the issue tracker
and the author's profile. Nine in-page anchors, each verified by the validator
to point at an id that exists.

There are no partner links, no link exchanges, no footer link farm and no
directory listings. When IzzyOnDroid and F-Droid go live, their listings belong
in the install section's third channel, replacing the "not yet" copy that is
there now.

## Two documents now, not one

The 404 is a real page with real metadata, so the invariants apply to it too:
`npm run validate` walks every HTML file in `dist/`, and both pass one `h1`, one
`main`, no skipped heading levels, an absolute canonical, and parsing JSON-LD.

The differences are deliberate. The 404 sends `noindex, follow`, carries no
`VideoObject` and no `og:video` because it contains no video, and its `h1` is the
numeral with the accessible name supplied by a visually hidden span, so a screen
reader hears "404, page not found" rather than three digits.

It is served through Nginx's `error_page 404` with a real 404 status, and
`/404.html` is not fetchable directly. Ranking a soft 404 for a brand term is the
one bad outcome available on a page that small.
