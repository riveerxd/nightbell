#!/usr/bin/env node
/**
 * Pull every binary the site serves out of the repository it documents.
 *
 * Nothing under `public/media`, `public/fonts` or `src/assets` is hand-placed.
 * They are copies, and this script is the copy: run it and the site is holding
 * the current screenshots, the current render and the current mark. That is the
 * point: a landing page that quietly keeps showing the app from three releases
 * ago is worse than no landing page.
 *
 *   node scripts/sync-assets.mjs            # copy and derive
 *   node scripts/sync-assets.mjs --check    # verify only, non-zero if stale
 *
 * Derived files (a poster, an Open Graph frame, the icon set) are made with
 * ffmpeg and rsvg-convert. Both are already required to build the promo video,
 * so this adds no new tooling. If either is missing the script says which
 * artefact it could not make and leaves the previous one alone.
 *
 * `assets.lock.json` records the size and SHA-256 of every source file at the
 * time of the last sync, so `--check` can tell "the video was re-rendered" from
 * "the video is fine".
 */
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, copyFileSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const SITE = resolve(HERE, '..');
const REPO = resolve(SITE, '..');

/**
 * Whether the Remotion project that renders the promo is in this working copy.
 *
 * It is not in the repository. `promo-video/` is 52 MB before `node_modules`,
 * almost all of it intermediate WAVs under `audio/build`, and none of it is
 * needed to build or serve this site. What ships instead is the render, at
 * `docs/video/nightbell-promo.mp4`, which is what the page actually plays.
 *
 * Two derived files used to be read straight out of that project and now cannot
 * be, so they are committed instead: `public/fonts/*.woff2` and the chapter
 * table in `src/media.json`. When the project is present those two are rebuilt
 * from source and cross-checked exactly as before. When it is absent the
 * committed copies are used as-is, and that is a normal build rather than a
 * broken one, which is why nothing below pushes a problem for it.
 *
 * The honest cost: with the project absent, this script can no longer prove the
 * fonts and the chapter table match the cut they came from. Re-render with the
 * project in place, which is the same machine that produced the mp4 anyway.
 */
const HAS_CUT = existsSync(join(REPO, 'promo-video/src'));

const check = process.argv.includes('--check');
const problems = [];
const notes = [];

const rel = (p) => p.replace(REPO + '/', '');
const sha256 = (p) => createHash('sha256').update(readFileSync(p)).digest('hex');

function ensureDir(p) {
  mkdirSync(dirname(p), { recursive: true });
}

/** Copy a file from the repo into the site, recording its source hash. */
const lock = {};

/**
 * The lock as it was written last time.
 *
 * Read so that a source this run deliberately skipped, because the video project
 * is not in the tree, can keep the hash it was recorded with instead of dropping
 * out of the lock entirely. Without this, assets.lock.json gains and loses its
 * two typeface entries depending on who ran the script last, which is diff noise
 * that says nothing about the assets.
 */
const previousLock = (() => {
  const path = join(SITE, 'assets.lock.json');
  if (!existsSync(path)) return {};
  try {
    return JSON.parse(readFileSync(path, 'utf8')).files ?? {};
  } catch {
    return {};
  }
})();
function take(from, to) {
  const src = join(REPO, from);
  const dst = join(SITE, to);
  if (!existsSync(src)) {
    problems.push(`missing source: ${from}`);
    return false;
  }
  lock[from] = { bytes: statSync(src).size, sha256: sha256(src), copiedTo: to };
  if (check) {
    if (!existsSync(dst) || sha256(dst) !== lock[from].sha256) {
      problems.push(`stale copy: ${to} does not match ${from}`);
      return false;
    }
    return true;
  }
  ensureDir(dst);
  copyFileSync(src, dst);
  notes.push(`${from}  ->  ${to}`);
  return true;
}

/** Run a tool, returning false (with a readable note) rather than throwing. */
function run(bin, args, what) {
  try {
    execFileSync(bin, args, { stdio: ['ignore', 'ignore', 'pipe'] });
    return true;
  } catch (err) {
    problems.push(`could not build ${what}: ${bin} failed (${String(err.stderr || err).trim().split('\n').slice(-1)[0]})`);
    return false;
  }
}

// ---------------------------------------------------------------- typefaces
//
// Two variable faces, latin subset only, lifted from the promo video's
// node_modules so the page is set in the same metal as the film that plays in
// its hero. 88 KB for both, which is why they are preloaded rather than
// swapped in.
//
// Committed rather than derived, because the node_modules they were lifted from
// left the repository with the rest of the video project. See HAS_CUT above.
const FONTS = 'promo-video/node_modules/@fontsource-variable';
const FACES = [
  [`${FONTS}/inter/files/inter-latin-wght-normal.woff2`, 'public/fonts/inter-latin-wght-normal.woff2'],
  [
    `${FONTS}/jetbrains-mono/files/jetbrains-mono-latin-wght-normal.woff2`,
    'public/fonts/jetbrains-mono-latin-wght-normal.woff2',
  ],
];

for (const [from, to] of FACES) {
  if (HAS_CUT && existsSync(join(REPO, from))) {
    take(from, to);
  } else if (existsSync(join(SITE, to))) {
    if (previousLock[from]) lock[from] = previousLock[from];
    notes.push(`${to}  (committed, no cut in tree)`);
  } else {
    // This one is worth failing on either way. A missing face is a headline
    // that renders in a fallback, which is the layout shift the preload exists
    // to prevent, and neither source can supply it now.
    problems.push(`missing typeface: ${to} is not committed and ${FONTS} is not in the tree`);
  }
}

// -------------------------------------------------------------------- video
//
// The full promo and the payoff cut on its own. Both are served from public/
// untouched: they are already h.264 High / yuv420p, which every browser plays,
// and re-encoding a finished grade to save a few hundred kilobytes is how a
// gradient turns into a staircase.
// The served names do not carry the cut's version and are not going to. They are
// what `/media/*` caches for thirty days in a browser, so a rename is a cache
// decision rather than a naming one; the version lives on the source side.
const gotPromo = take('docs/video/nightbell-promo-v3.mp4', 'public/media/nightbell-promo.mp4');
const gotPayoff = take(
  'docs/video/nightbell-alert-firing-v3.mp4',
  'public/media/nightbell-alert-firing.mp4',
);

// The caption track, generated from the narration table rather than written.
//
// It used to be a committed file somebody maintained, and it went stale in the
// worst available way: the timings drifted a few tenths, which nobody notices,
// and one cue still read "Every check runs on the phone" over what is now the
// repository scene. A caption track is what a deaf viewer has instead of the
// audio, so one that states a claim the picture is not making is worse than
// none. It is derived now, and it lives under audio/build/ with the rest of the
// generated sound, which is outside a clean checkout for the same reason the
// typefaces are.
const CAPTIONS = 'promo-video/audio/build/vo-v3.vtt';
const CAPTIONS_TO = 'public/media/nightbell-promo.en.vtt';
if (HAS_CUT && existsSync(join(REPO, CAPTIONS))) {
  take(CAPTIONS, CAPTIONS_TO);
} else if (existsSync(join(SITE, CAPTIONS_TO))) {
  if (previousLock[CAPTIONS]) lock[CAPTIONS] = previousLock[CAPTIONS];
  notes.push(`${CAPTIONS_TO}  (committed, no cut in tree)`);
} else {
  problems.push(`missing captions: ${CAPTIONS_TO} is not committed and ${CAPTIONS} is not in the tree`);
}

// ------------------------------------------------------------- screenshots
//
// Real screens off a real device. These go into src/assets so Astro can size
// and re-encode them per breakpoint; the video does not, because Astro has no
// opinion about mp4.
take('docs/screens/urgent-b.png', 'src/assets/screens/urgent.png');
take('docs/screens/setup-b.png', 'src/assets/screens/pager-setup.png');
take('docs/screens/create-monitor.png', 'src/assets/screens/create-monitor.png');
take('docs/screens/widget-243.png', 'src/assets/screens/widget.png');
take('docs/screens/settings-group.png', 'src/assets/screens/settings.png');
take('docs/screens/hero-b.png', 'src/assets/screens/group.png');

// -------------------------------------------------------- single-phone crops
//
// There used to be a table of crop boxes here. Two of the source shots are
// contact sheets, hero-b.png being three phones on one plate and
// create-monitor.png the four wizard steps side by side, and the page needs the
// phones one at a time.
//
// The boxes were read off the plates by hand and stepped a fixed 562 px, and by
// the time anybody looked the fourth wizard phone had moved: step four of the
// setup section was shipping with its right hand edge sliced off, and nothing in
// the build said so, because a crop box cannot tell whether it landed on
// anything.
//
// `scripts/clean-screens.mjs` now finds the phones on the plate instead of
// being told where they are, and it fails loudly if it finds a different number
// of them than it expects. It also takes the render's ground and drawn shell
// off, which is the other half of why nothing on this page draws a frame around
// a screenshot any more: the frame is the stylesheet's.
//
// So the plates are copied whole here, and `npm run screens` does the cutting.

// ----------------------------------------------------------------- derived
//
// Frame times are chosen against the cut in promo-video/README.md, not by eye:
// 30.5 s is a second and a half after the full-screen page has finished taking
// over the phone, so the poster is the payoff at rest rather than mid-wipe.
const posterAt = '30.5';
const poster = join(SITE, 'public/media/nightbell-promo-poster.webp');
const og = join(SITE, 'public/media/nightbell-og.jpg');
const payoffPoster = join(SITE, 'public/media/nightbell-alert-firing-poster.webp');

/**
 * The two video posters are WebP. The Open Graph image below stays JPEG.
 *
 * The posters are read by browsers, all of which have supported WebP for years,
 * and at the same 1280 px width WebP is 53 per cent smaller than the JPEG these
 * used to be: 51 kB became 24 kB, and 37 kB became 17 kB. That was the one scored
 * failure in a Lighthouse run that otherwise passed, and on a `preload="none"`
 * video the poster is the only image bytes the section costs until somebody
 * presses play.
 *
 * Quality 78 rather than the default. The frame is a flat red alert plate, which
 * is exactly what a low-quality setting blocks up, and it was checked at 72, 78
 * and 84 rather than assumed: 78 is where the plate stops showing banding and
 * the type stays crisp.
 *
 * The Open Graph image cannot follow. Social scrapers are not browsers, several
 * still refuse anything but JPEG or PNG, and a card that fails to render is worse
 * than a card that is 50 kB. It keeps its 1200x630 JPEG.
 *
 * 1280 px is kept for both. The player is `width: 100%` inside a content column
 * that maxes at 972 px, so 1280 covers a 1x desktop with a little margin. A
 * `poster` attribute takes one URL and has no srcset, so a single size has to
 * serve a 370 px phone and a 972 px desktop, and erring toward the desktop is the
 * right way round: the phone wastes bytes, the desktop would waste sharpness.
 */
const WEBP = ['-c:v', 'libwebp', '-quality', '78', '-compression_level', '6'];

if (!check && gotPromo) {
  ensureDir(poster);
  run('ffmpeg', ['-y', '-v', 'error', '-ss', posterAt, '-i', join(SITE, 'public/media/nightbell-promo.mp4'),
    '-frames:v', '1', '-vf', 'scale=1280:-2', ...WEBP, poster], 'the promo poster');

  // Open Graph wants 1.91:1. Cropping 1080 to 1008 takes 36 px off each edge of
  // a 16:9 frame, which on this composition is empty ground on both sides.
  run('ffmpeg', ['-y', '-v', 'error', '-ss', posterAt, '-i', join(SITE, 'public/media/nightbell-promo.mp4'),
    '-frames:v', '1', '-vf', 'crop=1920:1008,scale=1200:630', '-q:v', '3', og], 'the Open Graph image');
}

if (!check && gotPayoff) {
  ensureDir(payoffPoster);
  // 1.05 s in: the dashboard has finished assembling and every monitor is still
  // green. A poster is a promise about what the clip contains, and the promise
  // this clip keeps is that the green becomes red, so the still is the green.
  run('ffmpeg', ['-y', '-v', 'error', '-ss', '1.05', '-i', join(SITE, 'public/media/nightbell-alert-firing.mp4'),
    '-frames:v', '1', '-vf', 'scale=1280:-2', ...WEBP, payoffPoster], 'the payoff poster');
}

// -------------------------------------------------------------------- icons
//
// The PNG icon set is the brand master itself, read off disk rather than
// transcribed, so it cannot drift from the launcher the way a copied path would.
// docs/brand/android_assets.py recomputes the same geometry for each Android
// canvas; this is the one consumer that can use the 512-unit drawing unchanged.
//
// These three keep the plate. apple-touch-icon has to, because iOS composites
// transparency onto black and does not honour a corner radius it did not draw.
// The two manifest icons keep it because a home-screen icon sits on a launcher,
// which is what the plate is for.
//
// favicon.svg and favicon.ico are NOT written here, and this is the one place
// somebody would reasonably add them back, so: they are generated by
// docs/brand/web_favicon.py and committed, and re-adding them here would
// silently overwrite them on the next `npm run assets`.
//
// The reason they moved out is that a tab is not a launcher and the two want
// opposite things. This file used to write favicon.svg with `plate: true`,
// arguing the ink plate made the mark findable at 16 px against light chrome. The
// opposite is true in a dark tab strip, where a near-black plate disappears into
// the chrome and leaves the inset mark reading as a speck. So the favicon is
// transparent, full bleed, and brand blue, which reads on both, and it ships
// alongside a .ico because Safari and iOS support no SVG favicon at all. Neither
// of those is a plate flag on the launcher drawing, so neither belongs here.
const MASTER_MARK = 'docs/brand/nightbell-mark-icon.svg';
const PLATE_RECT = '<rect width="512" height="512" rx="112" fill="#0B0E13"/>';
const iconSvg = (plate) => {
  const src = readFileSync(join(REPO, MASTER_MARK), 'utf8');
  return plate ? src : src.replace(`  ${PLATE_RECT}\n`, '');
};

if (!check) {
  const tmp = join(SITE, 'public/.icon-src.svg');
  ensureDir(tmp);
  writeFileSync(tmp, iconSvg(true));
  for (const [size, name] of [[192, 'icon-192.png'], [512, 'icon-512.png'], [180, 'apple-touch-icon.png']]) {
    run('rsvg-convert', ['-w', String(size), '-h', String(size), '-o', join(SITE, 'public', name), tmp],
      `public/${name}`);
  }
  try { execFileSync('rm', ['-f', tmp]); } catch { /* nothing to clean */ }
}

// --------------------------------------------------------------- the chapters
//
// The player's scrubber carries a marker at every scene boundary, and the six
// chapter buttons under it seek to those boundaries. Both come from the cut
// itself: SCENES and OVERLAPS are parsed out of promo-video/src/NightbellPromo.tsx
// and the start frames are computed with the same fold narration.mjs uses.
//
// Parsed rather than copied for the reason voiceover.mjs gives for doing the
// same thing: audio and video are rendered by different tools, and a scene that
// has been re-budgeted must not leave a stale table sitting in another file. If
// the arithmetic here stops agreeing with the rendered duration, that is a
// problem worth failing on rather than a chapter list that is quietly wrong.
// The 40 s cut, which is what the site plays. `NightbellPromo.tsx` is the 61 s
// one and is still built from the same scenes; pointing this at the wrong file
// is not a cosmetic error, because the drift check below compares the chapter
// table it produces against the real duration of the mp4 and would fail.
const CUT_FILE = join(REPO, 'promo-video/src/NightbellPromoShort.tsx');
const ROOT_FILE = join(REPO, 'promo-video/src/Root.tsx');

/**
 * Short labels for the scrubber, and a longer one for the button's title.
 *
 * Keyed on the scene component's own name with `Scene` stripped, so a renamed
 * scene shows up as an unlabelled chapter rather than a mislabelled one.
 *
 * The labels name what you will see, not what the scene is for. They used to be
 * the cut's own internal names, Hook / Wedge / Proof / Cta, which are useful when
 * you are budgeting scenes and meaningless to somebody deciding where to skip to.
 */
const CHAPTER_NAMES = {
  Hook: ['Intro', 'The claim'],
  Wedge: ['On your phone', 'Monitoring, on your phone'],
  Setup: ['Add a monitor', 'Four steps to add a monitor'],
  Repos: ['A repository', 'Watching a GitHub repository'],
  Groups: ['Groups', 'Many monitors, one verdict'],
  Proof: ['Checks running', 'Every check runs on the device'],
  Homelab: ['Self-hosted', 'Self-signed, or behind Tor'],
  Alert: ['The alert', 'The alert firing'],
  Cta: ['Download', 'Where to get it'],
};

function readChapters() {
  // No cut in the tree is the ordinary case now: the chapter table in
  // src/media.json is committed, and the merge below keeps it rather than
  // overwriting it with a media.json that has no chapters at all.
  if (!HAS_CUT) return null;
  if (!existsSync(CUT_FILE)) {
    problems.push('missing source: promo-video/src/NightbellPromoShort.tsx, chapters not rebuilt');
    return null;
  }
  const cut = readFileSync(CUT_FILE, 'utf8');

  const numbers = (name, source) => {
    const match = new RegExp(`${name}\\s*=\\s*\\[([^\\]]*)\\]`).exec(source);
    return match ? match[1].split(',').map((n) => Number(n.trim())).filter(Number.isFinite) : null;
  };

  const scenes = numbers('SCENES', cut);
  const overlaps = numbers('OVERLAPS', cut);
  if (!scenes || !overlaps) {
    problems.push('could not parse SCENES / OVERLAPS out of NightbellPromoShort.tsx');
    return null;
  }

  // Scene components in the order the TransitionSeries plays them.
  const order = [...cut.matchAll(/<(\w+)Scene\s*\/>/g)].map((m) => m[1]);

  const fps = Number(/fps=\{(\d+)\}/.exec(existsSync(ROOT_FILE) ? readFileSync(ROOT_FILE, 'utf8') : '')?.[1]) || 30;

  // The same fold narration.mjs uses: each scene starts where the previous one
  // ended, minus the frames the cross-fade overlaps them by.
  const starts = [0];
  for (let i = 0; i < scenes.length - 1; i += 1) {
    starts.push(starts[i] + scenes[i] - (overlaps[i] ?? 0));
  }

  const totalFrames =
    scenes.reduce((a, b) => a + b, 0) - overlaps.reduce((a, b) => a + b, 0);

  return {
    fps,
    totalFrames,
    expectedSeconds: totalFrames / fps,
    chapters: starts.map((frame, i) => {
      const key = order[i];
      const [label, title] = CHAPTER_NAMES[key] ?? [key ?? `Scene ${i + 1}`, `Scene ${i + 1}`];
      return { at: Math.round((frame / fps) * 1000) / 1000, label, title };
    }),
  };
}

// ------------------------------------------------------- measured media facts
//
// The page prints how long each clip runs and how big it is. Those numbers are
// measured here rather than typed into the copy, because the render is a moving
// target: the promo went from 55.5 s to 40 s during this page's own build, and
// a caption that says the wrong length is the kind of small lie a reader
// notices immediately.
const media = {};
for (const [key, file] of [
  ['promo', 'public/media/nightbell-promo.mp4'],
  ['payoff', 'public/media/nightbell-alert-firing.mp4'],
]) {
  const path = join(SITE, file);
  if (!existsSync(path)) continue;
  let seconds = null;
  let width = null;
  let height = null;
  try {
    // Pixel dimensions as well as duration, because the Open Graph video tags in
    // Base.astro have to state them and an unfurler that is handed the wrong
    // aspect ratio letterboxes or crops the card. They were typed as 1280x720 for
    // exactly one commit, which is the poster's size and not the render's.
    const probe = execFileSync('ffprobe', [
      '-v', 'error',
      '-select_streams', 'v:0',
      '-show_entries', 'stream=width,height,duration',
      '-of', 'csv=p=0',
      path,
    ]).toString().trim();
    const [w, h, d] = probe.split(',').map(Number);
    width = w;
    height = h;
    seconds = d;
  } catch {
    problems.push(`could not measure ${file}: ffprobe failed`);
  }
  media[key] = {
    file: '/' + file.replace('public/', ''),
    bytes: statSync(path).size,
    seconds: Number.isFinite(seconds) ? Math.round(seconds * 10) / 10 : null,
    width: Number.isFinite(width) ? width : null,
    height: Number.isFinite(height) ? height : null,
  };
}

const cut = readChapters();
if (cut && media.promo) {
  // The one cross-check that matters: a chapter list computed from the source
  // tables is worthless if the file on disk is a different render.
  const drift = Math.abs(cut.expectedSeconds - (media.promo.seconds ?? 0));
  if (media.promo.seconds != null && drift > 0.15) {
    problems.push(
      `the cut in NightbellPromoShort.tsx is ${cut.expectedSeconds.toFixed(2)} s but ` +
        `nightbell-promo.mp4 runs ${media.promo.seconds.toFixed(2)} s. Re-render the ` +
        `video (cd promo-video && npm run render:v3) before trusting the chapters.`,
    );
  }
  media.promo.fps = cut.fps;
  media.promo.chapters = cut.chapters;
}

/**
 * Carry the committed chapter table forward when there was no cut to read it
 * from.
 *
 * `media` is rebuilt from ffprobe on every run, so it starts each time with a
 * `promo` that has bytes and seconds and no chapters. Writing that out with the
 * video project absent would delete the six chapters from src/media.json and
 * take the scrubber markers and every chapter button off the player, and it
 * would do it silently, because a player with no chapters still plays. So the
 * previous file is read back and its chapter fields are kept.
 */
if (!cut && media.promo) {
  const existing = join(SITE, 'src/media.json');
  if (existsSync(existing)) {
    try {
      const previous = JSON.parse(readFileSync(existing, 'utf8'));
      if (previous.promo?.chapters?.length) {
        media.promo.fps = previous.promo.fps ?? media.promo.fps;
        media.promo.chapters = previous.promo.chapters;
        notes.push('src/media.json chapters kept (committed, no cut in tree)');
      }
    } catch (err) {
      problems.push(`src/media.json does not parse, chapters cannot be kept (${err.message})`);
    }
  }
  if (!media.promo.chapters?.length) {
    problems.push('no chapter table: src/media.json has none and promo-video/ is not in the tree');
  }
}

if (!check && Object.keys(media).length) {
  writeFileSync(join(SITE, 'src/media.json'), JSON.stringify(media, null, 2) + '\n');
  notes.push('measured   ->  src/media.json');
}

// ------------------------------------------------------------------- report
if (!check) {
  writeFileSync(
    join(SITE, 'assets.lock.json'),
    JSON.stringify({ syncedFrom: rel(REPO), files: lock }, null, 2) + '\n',
  );
}

for (const n of notes) console.log('  ' + n);
if (problems.length) {
  console.error('\n' + problems.map((p) => '  ! ' + p).join('\n'));
  console.error(
    check
      ? '\nRun `npm run assets` to refresh.'
      : '\nSome derived files could not be made. The build will still run.',
  );
  process.exit(check ? 1 : 0);
}
console.log(check ? '\nAssets are in sync.' : `\nSynced ${Object.keys(lock).length} files.`);
