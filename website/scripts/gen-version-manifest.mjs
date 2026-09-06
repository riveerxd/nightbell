#!/usr/bin/env node
/**
 * Writes the release manifest the app itself reads.
 *
 *   node scripts/gen-version-manifest.mjs            # write the manifest
 *   node scripts/gen-version-manifest.mjs --check    # fail if it is stale
 *
 * ## What reads this and why it is not on GitHub
 *
 * Every install of Nightbell with update checks on asks, four times a day, which
 * version is current. Until 3.10.0 it asked GitHub's API or F-Droid's, which meant
 * a third party learned an address and a rhythm from every user of a monitoring
 * app whose entire pitch is that nothing leaves the phone. This file is the same
 * answer, served by the same site the app is published from, so the request goes
 * to the one host that has a reason to be told.
 *
 * The second thing it does is count. One log line per request, holding a
 * timestamp, a status and a version, and nothing else: see the `nightbell_census`
 * format in deploy/nginx/nightbell.app.conf for the fields and for the four that
 * are deliberately missing. Requests per day divided by the per install rate is
 * how many copies are running, which is a question this project had no way to
 * answer at all.
 *
 * ## Why generated
 *
 * It is the fifth place a version number lives, after site.config.mjs, the release
 * tag, the asset filename and the /download redirect. The first four are already
 * documented as needing to move together, and the redirect is generated for
 * exactly this reason: a hand-typed copy goes stale at the next bump and fails
 * silently. This one fails worse than the redirect would. A stale redirect hands
 * out an old APK to people who press a button; a stale manifest tells every
 * install in the world that an old version is the newest one, and they believe it.
 *
 * So it comes off RELEASE, and `--check` runs inside `npm run verify`, which
 * deploy.sh runs before it uploads. It lands in public/, so Astro copies it into
 * dist/ and deploy.sh ships it with the site. There is no new release step.
 *
 * ## The check this adds that nothing had before
 *
 * `--check` also asks GitHub what the latest tag actually is, which closes the one
 * hole docs/FDROID.md names and calls a human's job: nothing in the build knew
 * what the newest release was, so RELEASE could sit three versions behind and every
 * generated file would agree with it. That is what happened for sixteen days.
 *
 * A network failure or a rate limit warns and passes, because a deploy must not
 * depend on GitHub being reachable. Only a clear answer that disagrees fails.
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { RELEASE, REPO } from '../site.config.mjs';

const SITE = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = resolve(SITE, 'public/v1/release.json');
const REL = 'public/v1/release.json';

const CHECK = process.argv.includes('--check');

/**
 * Flat, and every field a string or a number.
 *
 * The app's parser is twenty lines and has no array to walk, no field whose
 * meaning depends on a sibling and no optional nesting, which is the opposite of
 * both other sources it supports. That is on purpose: the payload an install
 * trusts about its own updates is the last place to be clever.
 *
 * `schema` is here so a future shape can be recognised rather than guessed at,
 * though the real answer to a breaking change is /v2/release.json, because the
 * oldest install in the wild reads whichever path was compiled into it forever.
 *
 * No build timestamp. It would make this file differ on every run and turn
 * `--check` from a byte comparison into nothing.
 */
const manifest = {
  schema: 1,
  version: RELEASE.version,
  versionCode: RELEASE.versionCode,
  tag: RELEASE.tag,
  url: `${REPO.url}/releases/tag/${RELEASE.tag}`,
  notes: `Nightbell ${RELEASE.version}`,
  apkUrl: RELEASE.apkUrl,
  apkSize: RELEASE.apkBytes,
  apkSha256: RELEASE.apkSha256,
};

const body = `${JSON.stringify(manifest, null, 2)}\n`;

async function githubTag() {
  const url = `https://api.github.com/repos/${REPO.owner}/${REPO.name}/releases/latest`;
  const signal = AbortSignal.timeout(8000);
  const response = await fetch(url, {
    signal,
    headers: {
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': `${REPO.name}-website-verify`,
    },
  });
  if (!response.ok) throw new Error(`GitHub answered ${response.status}`);
  const json = await response.json();
  if (typeof json.tag_name !== 'string' || !json.tag_name) {
    throw new Error('no tag_name in the response');
  }
  return json.tag_name;
}

if (CHECK) {
  if (!existsSync(OUT)) {
    console.error(`Missing ${REL}. Run \`npm run version-manifest\`.`);
    process.exit(1);
  }
  const have = readFileSync(OUT, 'utf8');
  if (have !== body) {
    console.error(
      [
        `${REL} is stale.`,
        '',
        `It does not match RELEASE ${RELEASE.version} in site.config.mjs, so every`,
        'install that checks for updates would be told the version it names is the',
        'newest one there is.',
        '',
        'Fix with:  npm run version-manifest',
      ].join('\n'),
    );
    process.exit(1);
  }
  console.log(`ok  ${REL} matches RELEASE ${RELEASE.version}`);

  let tag;
  try {
    tag = await githubTag();
  } catch (error) {
    console.warn(
      `warn  could not ask GitHub for the latest tag (${error.message}).\n` +
        `      RELEASE says ${RELEASE.tag}. Nothing here can confirm that, so confirm it by hand.`,
    );
    process.exit(0);
  }
  if (tag !== RELEASE.tag) {
    console.error(
      [
        `RELEASE in site.config.mjs says ${RELEASE.tag}. GitHub's latest release is ${tag}.`,
        '',
        'One of two things is true and both are worth stopping for:',
        '',
        `  - ${tag} shipped and this block was never updated. Every file generated`,
        '    from it agrees with it, which is what makes this invisible, and the site',
        '    would hand out the old APK while the app reported the old version as',
        '    current. This is the failure that ran for sixteen days across 3.0.3,',
        '    3.0.4 and 3.0.5.',
        '',
        `  - ${RELEASE.tag} has not been published yet, so the release is half done.`,
        '    Finish step 6 of "Releasing a new version" in docs/FDROID.md, then run',
        '    this again.',
        '',
        'Update RELEASE from the asset as GitHub serves it, taking apkSha256 and',
        'apkBytes from the file downloaded back off the release, then:',
        '',
        '  npm run version-manifest && npm run download-redirect',
      ].join('\n'),
    );
    process.exit(1);
  }
  console.log(`ok  RELEASE ${RELEASE.tag} is GitHub's latest release`);
  process.exit(0);
}

mkdirSync(dirname(OUT), { recursive: true });
const changed = !existsSync(OUT) || readFileSync(OUT, 'utf8') !== body;
writeFileSync(OUT, body);
console.log(
  changed
    ? `wrote  ${REL}  ->  ${RELEASE.version} (${RELEASE.versionCode})`
    : `ok     ${REL} already current for ${RELEASE.version}`,
);
