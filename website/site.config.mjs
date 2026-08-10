/**
 * The one place the site's identity is configured.
 *
 * `SITE_URL` is the origin every absolute URL on the site is built from: the
 * canonical link, the Open Graph and Twitter card URLs, the JSON-LD `@id`s,
 * `sitemap.xml` and the `Sitemap:` line in `robots.txt`. Change it here and
 * nothing else needs touching.
 *
 * ## The domain
 *
 * `nightbell.app` is the settled name. It replaced a `pulse.example` placeholder
 * when the app was renamed from Pulse in 3.0.0, and it replaced the name itself
 * because every reasonable `pulse*` domain in this category is already a live
 * product: Pulse Pager, Pulse UpTime and two others all ship uptime monitoring
 * under the same word. Nightbell collides with nothing, so a search for it
 * returns this and only this.
 *
 * The `.app` TLD is on the HSTS preload list, so this origin is HTTPS-only with
 * no plaintext fallback and no way to click through a certificate warning. Any
 * host serving it needs a real certificate from the first request.
 *
 * While `SITE_URL` was a reserved `.example` host, two safeguards were active:
 * `robots.txt` served `Disallow: /` so an accidental deploy could not be indexed
 * under a canonical that did not exist, and the build printed a warning. Both
 * released automatically the moment this became a real origin, which is why the
 * check below keys off `.example` rather than a hand-maintained flag.
 */
export const SITE_URL = 'https://nightbell.app';

/** True while `SITE_URL` is still the reserved placeholder host. */
export const IS_PLACEHOLDER_DOMAIN = new URL(SITE_URL).hostname.endsWith('.example');

/**
 * Everything the page says about the repository, in one place, so a version
 * bump is one edit rather than nine.
 *
 * Grounded in the real remote (`git remote -v`). The repository was renamed from
 * `pulse-monitoring` to `nightbell` in 3.0.0; GitHub redirects the old paths, but
 * these are written as the current ones so the visible link text is not a lie.
 */
export const REPO = {
  url: 'https://github.com/riveerxd/nightbell',
  owner: 'riveerxd',
  name: 'nightbell',
  issues: 'https://github.com/riveerxd/nightbell/issues',
  releases: 'https://github.com/riveerxd/nightbell/releases',
  latestRelease: 'https://github.com/riveerxd/nightbell/releases/latest',
  license: 'https://github.com/riveerxd/nightbell/blob/master/LICENSE',
  readme: 'https://github.com/riveerxd/nightbell#readme',
  reference: 'https://github.com/riveerxd/nightbell/blob/master/docs/reference.md',
};

/**
 * Every number here describes the asset that is on GitHub right now.
 *
 * `apkBytes` and `apkSha256` were measured by downloading the published file back
 * off the release and hashing it, not by hashing the local build and assuming the
 * upload matched. The page prints that digest for people to verify against, so it
 * is the one value in this file that must never be predicted or copied forward.
 *
 * When a release ships, update `version`, `tag`, `apkName`, `apkUrl`, `apkBytes`
 * and `apkSha256` together, from the artifact as GitHub serves it.
 */
export const RELEASE = {
  version: '3.0.2',
  tag: 'v3.0.2',
  apkName: 'Nightbell-3.0.2-release.apk',
  apkUrl:
    'https://github.com/riveerxd/nightbell/releases/download/v3.0.2/Nightbell-3.0.2-release.apk',
  apkBytes: 2160440,
  apkSha256: '6a5a2a858ac3e245657b7c6a284596626a78f58eddf749f3af550f099be11115',
  minSdk: 26,
  minAndroid: '8.0',
  targetSdk: 36,
  license: 'Apache-2.0',
  /**
   * The signing certificate, read out of the published APK with
   * `apksigner verify --print-certs`. Android refuses an update signed by a
   * different key, so this is the number that says "the next version is from the
   * same person", and it is the one worth publishing.
   *
   * New in 3.0.1. Releases from 2.0.0 to 3.0.0 were signed by a key whose subject
   * read `CN=Pulse Monitor`, and a certificate subject cannot be edited, so the
   * only way to stop shipping the old name inside the signature was a new key.
   * The consequence is real and one-way: a 3.0.0 install cannot update to 3.0.1,
   * it has to be uninstalled first. Paid an hour after 3.0.0 shipped rather than
   * at any scale later. The old key is archived and still verifies everything up
   * to 3.0.0.
   */
  signingCertSha256: '20d8abdaa8416a9a751e3ea144ef1523d7ddbaaeee9ec6be01d63a65574a70de',
  signingCertDn: 'CN=Nightbell, OU=river, O=river, L=Prague, C=CZ',
};

/**
 * The promo, described for the things that read structured data.
 *
 * `seconds` and the chapter table are measured into `src/media.json` by
 * `scripts/sync-assets.mjs`, so the duration below is derived from that rather
 * than typed twice. Only the publication date lives here, because it is the one
 * fact about the file that cannot be measured from the file.
 *
 * `uploaded` is deliberately a written-down constant and not `new Date()`. The
 * same argument the sitemap makes about `lastmod`: a date stamped at build time
 * says "published today" on every rebuild, which is exactly the signal the field
 * carries and exactly the one it would then stop carrying. Set it once, to the
 * day the video actually became public, and edit it when a new cut replaces this
 * one.
 */
export const MEDIA = {
  promoUploaded: '2026-08-10',
  promoPoster: '/media/nightbell-promo-poster.webp',
  promoFile: '/media/nightbell-promo.mp4',
  promoName: 'Nightbell: uptime monitoring that runs on your phone',
  promoDescription:
    'A 40 second walkthrough of Nightbell: adding a monitor in four steps, checks running on the device, and the full-screen alert firing with its alarm when the service goes down.',
};

export const SEO = {
  title: 'Nightbell: Android uptime monitoring with no server or account',
  description:
    'Nightbell is an open-source Android uptime monitor. Checks run on your phone, API tokens stay on the device, and an outage arrives as a full-screen page with a looping alarm instead of a notification you swipe away.',
  ogImage: '/media/nightbell-og.jpg',
  ogImageWidth: 1200,
  ogImageHeight: 630,
  ogImageAlt:
    'The Nightbell urgent alert filling a phone screen in red, beside the words "When it breaks, Nightbell wakes you up."',
  themeColor: '#000000',
  locale: 'en',
};
