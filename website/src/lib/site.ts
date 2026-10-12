// Ported from website/site.config.mjs. The long reasoning behind each value
// lives there; this copy only carries the values so the two can be diffed.
export const SITE_URL = 'https://nightbell.app';


export const REPO = {
  url: 'https://github.com/riveerxd/nightbell',
  owner: 'riveerxd',
  name: 'nightbell',
  issues: 'https://github.com/riveerxd/nightbell/issues',

  bugReport: 'https://github.com/riveerxd/nightbell/issues/new?template=bug_report.yml',
  featureRequest: 'https://github.com/riveerxd/nightbell/issues/new?template=feature_request.yml',
  releases: 'https://github.com/riveerxd/nightbell/releases',
  latestRelease: 'https://github.com/riveerxd/nightbell/releases/latest',
  license: 'https://github.com/riveerxd/nightbell/blob/master/LICENSE',
  readme: 'https://github.com/riveerxd/nightbell#readme',
  reference: 'https://github.com/riveerxd/nightbell/blob/master/docs/reference.md',
  healthFormat: 'https://github.com/riveerxd/nightbell/blob/master/docs/health-format.md',
};

export const RELEASE = {
  version: '3.18.0',
  tag: 'v3.18.0',
  apkName: 'Nightbell-3.18.0-release.apk',
  apkUrl:
    'https://github.com/riveerxd/nightbell/releases/download/v3.18.0/Nightbell-3.18.0-release.apk',
  apkBytes: 2699560,
  apkSha256: '9ad2ee741f1b1350079c0f092a64173308ca07a12fe00a1452c1328b0a5215ff',
  versionCode: 47,
  minSdk: 26,
  minAndroid: '8.0',
  targetSdk: 36,
  license: 'Apache-2.0',

  signingCertSha256: '20d8abdaa8416a9a751e3ea144ef1523d7ddbaaeee9ec6be01d63a65574a70de',
  signingCertDn: 'CN=Nightbell, OU=river, O=river, L=Prague, C=CZ',
};

export const CHANNELS = {
  fdroid: {
    live: true,
    name: 'F-Droid',
    url: 'https://f-droid.org/en/packages/me.river.nightbell/',
    packageId: 'me.river.nightbell',

    addedOn: '2026-08-13',

    mergeRequest: 'https://gitlab.com/fdroid/fdroiddata/-/merge_requests/45381',

    metadata: 'metadata/me.river.nightbell.yml',
    devSigned: true,
  },
  obtainium: {
    live: true,
    name: 'Obtainium',

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

    url: null,
  },
  play: {
    live: false,
    name: 'Google Play',

    url: null,
  },
};

export const DOWNLOAD_PATH = '/download';

export const MEDIA = {
  promoUploaded: '2026-08-28T23:59:47+02:00',
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
  // A new name rather than the old one redrawn: chat apps cache a preview by
  // image URL for weeks, so the same path would keep showing the old card.
  ogImage: '/media/nightbell-share.jpg',
  ogImageWidth: 1200,
  ogImageHeight: 630,
  ogImageAlt:
    'The Nightbell urgent alert filling a phone screen in red, beside the words "The uptime monitor that actually wakes you up."',
  themeColor: '#000000',
  locale: 'en',
};

// Decimal megabytes, the unit Android's file manager and every browser download
// bar use. Dividing by 1024 twice gave 2.50, which is mebibytes labelled MB and
// smaller than the 2.6 a phone then shows for the same file.
export const APK_MB = (RELEASE.apkBytes / 1e6).toFixed(1);
