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
 * When a release ships, update `version`, `tag`, `apkName`, `apkUrl`, `apkBytes`,
 * `apkSha256` and `versionCode` together, from the artifact as GitHub serves it.
 *
 * ## The failure this block already had
 *
 * This sat at 3.0.2 while 3.0.3, 3.0.4 and 3.0.5 shipped, and it is the block the
 * download button is built from, so from 10 August 2026 the live site handed out
 * 3.0.2 to everybody who pressed it. Old GitHub asset URLs never stop resolving,
 * so nothing broke and nothing complained: the button worked, it just returned a
 * release three versions old, under a page printing that release's size and that
 * release's digest as though they described the current one. Every number in this
 * block was internally consistent, which is what stopped it looking wrong.
 *
 * `npm run verify` catches a generated snippet that disagrees with this block. It
 * cannot catch this block disagreeing with GitHub, because nothing here knows what
 * the latest tag is. The only defence is that the release checklist ends here, so
 * `docs/growth-prep/distribution/09-release-verification-checklist.md` names this
 * file, and the digest below is measured off the download rather than the build.
 *
 * `versionCode` is here because F-Droid identifies a build by it rather than by
 * the version name, so it is the number to quote in a store submission or a bug
 * report about which build somebody is running.
 */
export const RELEASE = {
  version: '3.1.1',
  tag: 'v3.1.1',
  apkName: 'Nightbell-3.1.1-release.apk',
  apkUrl:
    'https://github.com/riveerxd/nightbell/releases/download/v3.1.1/Nightbell-3.1.1-release.apk',
  apkBytes: 2185657,
  apkSha256: '96315c16d680f7df90ea96e9669bf6b0cf108a5ac16ce434925c09c306fee548',
  versionCode: 29,
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
 * Where Nightbell can actually be installed from, and where it cannot.
 *
 * Every entry here is a claim a visitor can check in one click, so the `live`
 * flag is the important field rather than the URL. A channel listed as working
 * that is not working costs more trust than a channel that is honestly missing,
 * which is the mistake this object exists to stop repeating.
 *
 * ## What happened before this existed
 *
 * Nightbell was accepted into official F-Droid on 13 August 2026 and the page
 * kept saying "Not submitted. IzzyOnDroid first, then F-Droid proper." for the
 * next fortnight, under a line reading "There is no store listing for Nightbell
 * anywhere. If you find one, it is not mine." By then f-droid.org was the single
 * largest source of traffic to the repository. The site was denying its own best
 * install route and warning people off it, and telling everyone who arrived to
 * sideload an APK with no update path instead.
 *
 * The reason it went unnoticed is that nothing in the build could know. The
 * listing lives on someone else's server, so there is no local file to diff and
 * no check that fails. That makes it a hand-maintained fact, which is exactly the
 * kind that rots, so it is written down once, here, with the date it became true.
 *
 * ## On the F-Droid entry
 *
 * `devSigned` is the part worth advertising and the part that took four rounds of
 * CI to earn. F-Droid builds from source and normally signs the result with the
 * F-Droid key; here it builds from source, compares its output against the APK
 * attached to the GitHub release, and publishes the developer's APK because the
 * two match byte for byte. That is why `signingCertSha256` above is the same
 * number whichever channel an install came from, and why moving between them does
 * not mean uninstalling and losing your monitors. The choice is one directional,
 * so see `docs/FDROID.md` before touching anything a build depends on.
 */
export const CHANNELS = {
  fdroid: {
    live: true,
    name: 'F-Droid',
    url: 'https://f-droid.org/en/packages/me.river.nightbell/',
    packageId: 'me.river.nightbell',
    /** The day the listing went live, from the "Added on" line on that page. */
    addedOn: '2026-08-13',
    /** The merge request that landed it, kept for the paper trail. */
    mergeRequest: 'https://gitlab.com/fdroid/fdroiddata/-/merge_requests/45381',
    /** Metadata path inside `fdroid/fdroiddata`. */
    metadata: 'metadata/me.river.nightbell.yml',
    devSigned: true,
  },
  obtainium: {
    live: true,
    name: 'Obtainium',
    /** Obtainium tracks the repository itself, so the URL is the repo. */
    url: 'https://github.com/riveerxd/nightbell',
  },
  github: {
    live: true,
    name: 'GitHub Releases',
    url: 'https://github.com/riveerxd/nightbell/releases/latest',
  },
  izzyondroid: {
    live: false,
    name: 'IzzyOnDroid',
    /**
     * Never submitted. It was meant to come first, as the fast route, and then
     * the slow route landed instead, which removed the urgency without removing
     * the reason: it is a second index with its own audience and its own search
     * results. The tracker moved from GitLab to Codeberg, see
     * `docs/growth-prep/distribution/01-izzyondroid-metadata.md`.
     */
    url: null,
  },
  play: {
    live: false,
    name: 'Google Play',
    /**
     * Not a "not yet". The foreground service declares `specialUse`, because
     * `dataSync` is capped at six hours a day from API 34 and that cap would
     * quietly break the guarantee strict mode exists to make. Shipping through
     * Play review would mean justifying the subtype. See the FAQ on the page.
     */
    url: null,
  },
};

/**
 * The path the download buttons point at, on this origin, instead of pointing
 * straight at GitHub.
 *
 * ## Why the indirection exists
 *
 * The APK is served by GitHub, so the bytes never touch this box and nothing here
 * can observe the transfer. A button linking directly to
 * `github.com/.../Nightbell-x.y.z-release.apk` therefore produces exactly zero
 * evidence that anybody downloaded anything: the click is a cross-origin
 * navigation, and the only party that counts it is GitHub.
 *
 * Sending the click through `/download` first makes it a request to this server,
 * which means one line in one log file, and then a 302 to the same GitHub URL as
 * before. The user gets the identical file from the identical host. What changes
 * is that the click is now countable without a single byte of JavaScript, without
 * a third party, without a cookie and therefore without a consent banner, on a
 * site whose Content-Security-Policy says in as many words that there is no
 * analytics on it. That claim stays true: nothing was added to the page.
 *
 * The secondary reason is that this is a stable URL. `nightbell.app/download` is
 * short enough to say out loud, survives every version bump, and is the thing to
 * put in a Reddit comment or a QR code, where a versioned GitHub URL would rot at
 * the next release.
 *
 * ## What it cannot tell you
 *
 * A click is not a download. Somebody can click and cancel, and GitHub's own
 * counter is the only thing that knows whether bytes moved. The two numbers are
 * meant to be read side by side, which is what `deploy/scripts/downloads.sh`
 * prints. Neither one is a count of people: see the log format in
 * `deploy/nginx/nightbell.app.conf` for what is deliberately not recorded.
 *
 * Changing this value changes the generated Nginx snippet on the next
 * `npm run build`, and the new path has to be added to the server block by hand,
 * because a `location` cannot be generated from here without templating the whole
 * file. `npm run verify` fails if the two have drifted.
 */
export const DOWNLOAD_PATH = '/download';

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
