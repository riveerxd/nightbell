#!/usr/bin/env node
/**
 * The build's own regression suite.
 *
 * It runs against `dist/`, not against the source, because the source is not
 * what Nginx serves. Every check here exists because it is a mistake that is
 * invisible in a browser: a canonical pointing at the wrong host looks
 * identical to a correct one, a second H1 looks like a heading, an image with
 * no alt looks like an image.
 *
 *   npm run build && npm run validate
 *   npm run verify                       # both, in order
 *
 * Errors fail the run. Warnings do not, and are the things that are wrong to
 * ship but right to have while a domain is still being decided.
 */
import { parse } from 'parse5';
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { join, dirname, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SITE = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DIST = join(SITE, 'dist');

const errors = [];
const warnings = [];
const passes = [];

const fail = (m) => errors.push(m);
const warn = (m) => warnings.push(m);
const ok = (m) => passes.push(m);

if (!existsSync(DIST)) {
  console.error('No dist/. Run `npm run build` first.');
  process.exit(1);
}

// ------------------------------------------------------------------ helpers

/** Walk the parse5 tree, calling `fn` for every element node. */
function walk(node, fn) {
  if (node.tagName) fn(node);
  for (const child of node.childNodes ?? []) walk(child, fn);
}

const attr = (node, name) => node.attrs?.find((a) => a.name === name)?.value;

function textOf(node) {
  let out = '';
  walk(node, (n) => {
    for (const c of n.childNodes ?? []) if (c.nodeName === '#text') out += c.value;
  });
  return out.replace(/\s+/g, ' ').trim();
}

function allFiles(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) allFiles(p, out);
    else out.push(p);
  }
  return out;
}

const files = allFiles(DIST);
const htmlFiles = files.filter((f) => f.endsWith('.html'));

if (!htmlFiles.length) fail('dist/ contains no HTML.');

// ------------------------------------------------------------------- the em
//
// The house rule for this project's writing: no em dashes, anywhere a reader
// can reach. Checked over the built HTML and over every source file the page is
// written in, because a dash added to a comment today is a dash in the copy
// next week.
// Written as escapes so this file does not trip its own check.
const DASHES = [
  ['\u2014', 'em dash'],
  ['\u2013', 'en dash'],
];

function scanDashes(label, text, sink) {
  for (const [char, name] of DASHES) {
    let index = text.indexOf(char);
    while (index !== -1) {
      const line = text.slice(0, index).split('\n').length;
      const context = text
        .slice(Math.max(0, index - 45), index + 45)
        .replace(/\s+/g, ' ')
        .trim();
      sink(`${name} in ${label}:${line}  ...${context}...`);
      index = text.indexOf(char, index + 1);
    }
  }
}

// `deploy` is in here because the Nginx config and the deploy scripts are prose as
// much as they are configuration, they are read by whoever is fixing production at
// the time, and a house rule that stops at the directory boundary is a house rule
// with an exception nobody remembers.
const SOURCE_DIRS = ['src', 'scripts', 'public', 'deploy'];
const SOURCE_EXTS = new Set([
  '.astro',
  '.ts',
  '.mjs',
  '.js',
  '.css',
  '.md',
  '.vtt',
  '.json',
  '.conf',
  '.sh',
]);
for (const dir of SOURCE_DIRS) {
  const abs = join(SITE, dir);
  if (!existsSync(abs)) continue;
  for (const f of allFiles(abs)) {
    if (!SOURCE_EXTS.has(extname(f))) continue;
    scanDashes(f.replace(SITE + '/', ''), readFileSync(f, 'utf8'), fail);
  }
}
for (const f of ['site.config.mjs', 'astro.config.mjs', 'README.md', 'DESIGN_NOTES.md', 'SEO_NOTES.md']) {
  const abs = join(SITE, f);
  if (existsSync(abs)) scanDashes(f, readFileSync(abs, 'utf8'), fail);
}
for (const f of htmlFiles) {
  scanDashes(f.replace(DIST + '/', 'dist/'), readFileSync(f, 'utf8'), fail);
}
if (!errors.length) ok('No em dashes or en dashes in source or output.');

// -------------------------------------------------------------- per document

for (const file of htmlFiles) {
  const label = file.replace(DIST + '/', '');
  const raw = readFileSync(file, 'utf8');
  const doc = parse(raw);

  const els = [];
  walk(doc, (n) => els.push(n));
  const byTag = (t) => els.filter((e) => e.tagName === t);
  const meta = (name) =>
    els.find((e) => e.tagName === 'meta' && (attr(e, 'name') === name || attr(e, 'property') === name));

  // -- structure -----------------------------------------------------------
  const html = byTag('html')[0];
  if (!html || !attr(html, 'lang')) fail(`${label}: <html> has no lang attribute.`);

  const h1s = byTag('h1');
  if (h1s.length === 0) fail(`${label}: no <h1>.`);
  else if (h1s.length > 1) fail(`${label}: ${h1s.length} <h1> elements. There must be exactly one.`);
  else ok(`${label}: one H1, "${textOf(h1s[0]).slice(0, 58)}..."`);

  if (byTag('main').length !== 1) fail(`${label}: expected exactly one <main> landmark.`);
  if (!byTag('header').length) warn(`${label}: no <header> landmark.`);
  if (!byTag('footer').length) warn(`${label}: no <footer> landmark.`);
  if (!byTag('nav').length) warn(`${label}: no <nav> landmark.`);

  // Heading levels must not skip: an h4 under an h2 is a screen reader landing
  // in the middle of an outline that does not exist.
  let previous = 0;
  for (const el of els) {
    const level = /^h([1-6])$/.exec(el.tagName ?? '')?.[1];
    if (!level) continue;
    const n = Number(level);
    if (previous && n > previous + 1) {
      fail(`${label}: heading jumps from h${previous} to h${n} at "${textOf(el).slice(0, 40)}".`);
    }
    previous = n;
  }

  // -- ids -----------------------------------------------------------------
  const ids = new Map();
  for (const el of els) {
    const id = attr(el, 'id');
    if (!id) continue;
    ids.set(id, (ids.get(id) ?? 0) + 1);
  }
  for (const [id, count] of ids) if (count > 1) fail(`${label}: id "${id}" used ${count} times.`);

  // -- head metadata -------------------------------------------------------
  const title = byTag('title')[0] ? textOf(byTag('title')[0]) : '';
  if (!title) fail(`${label}: no <title>.`);
  else if (title.length > 65) warn(`${label}: title is ${title.length} characters, over 65.`);
  else ok(`${label}: title, ${title.length} characters.`);

  const description = meta('description') && attr(meta('description'), 'content');
  if (!description) fail(`${label}: no meta description.`);
  else if (description.length < 70 || description.length > 320) {
    warn(`${label}: meta description is ${description.length} characters.`);
  } else ok(`${label}: meta description, ${description.length} characters.`);

  const canonical = els.find((e) => e.tagName === 'link' && attr(e, 'rel') === 'canonical');
  if (!canonical) fail(`${label}: no canonical link.`);
  else {
    const href = attr(canonical, 'href');
    if (!/^https?:\/\//.test(href ?? '')) fail(`${label}: canonical "${href}" is not absolute.`);
    else if (new URL(href).hostname.endsWith('.example')) {
      warn(`${label}: canonical still points at the placeholder host (${href}). See site.config.mjs.`);
    } else ok(`${label}: canonical ${href}`);
  }

  for (const required of [
    'og:title',
    'og:description',
    'og:image',
    'og:url',
    'og:type',
    'twitter:card',
    'twitter:image',
  ]) {
    if (!meta(required)) fail(`${label}: missing ${required}.`);
  }
  const ogAlt = meta('og:image:alt');
  if (!ogAlt || !attr(ogAlt, 'content')) fail(`${label}: og:image has no alt text.`);
  if (!meta('theme-color')) warn(`${label}: no theme-color.`);
  if (!els.some((e) => e.tagName === 'link' && (attr(e, 'rel') ?? '').includes('icon'))) {
    fail(`${label}: no favicon link.`);
  }
  if (!els.some((e) => e.tagName === 'link' && attr(e, 'rel') === 'manifest')) {
    warn(`${label}: no web manifest link.`);
  }

  // -- structured data -----------------------------------------------------
  const jsonLd = els.filter(
    (e) => e.tagName === 'script' && attr(e, 'type') === 'application/ld+json',
  );
  if (!jsonLd.length) fail(`${label}: no JSON-LD.`);
  const types = [];
  for (const block of jsonLd) {
    const text = block.childNodes?.[0]?.value ?? '';
    try {
      const parsed = JSON.parse(text);
      types.push(parsed['@type']);
    } catch (err) {
      fail(`${label}: JSON-LD block does not parse (${err.message}).`);
    }
  }
  for (const wanted of ['SoftwareApplication', 'WebSite']) {
    if (!types.includes(wanted)) fail(`${label}: JSON-LD is missing a ${wanted} entity.`);
  }
  if (types.length) ok(`${label}: JSON-LD entities ${types.join(', ')}.`);

  // -- images and media ----------------------------------------------------
  for (const img of byTag('img')) {
    const src = attr(img, 'src') ?? '(no src)';
    if (attr(img, 'alt') === undefined) fail(`${label}: <img src="${src}"> has no alt attribute.`);
    else if (attr(img, 'alt').trim() === '' && attr(img, 'role') !== 'presentation') {
      warn(`${label}: <img src="${src}"> has an empty alt. Intentional only if decorative.`);
    }
    // Dimensions are how the browser reserves the box before the bytes land.
    if (!attr(img, 'width') || !attr(img, 'height')) {
      fail(`${label}: <img src="${src}"> has no width/height, so it will shift the layout.`);
    }
  }

  /*
    The custom player is an enhancement, and the whole argument for building it
    was that the fallback stays intact. So the fallback is a checked invariant:
    every player figure must ship a `<video controls>` and a control bar that is
    `hidden` until script unhides it. Get either backwards and a reader with no
    JavaScript gets a poster they cannot press, which is exactly the failure a
    browser never shows you.
  */
  for (const fig of els.filter((e) => attr(e, 'data-player') !== undefined)) {
    const inner = [];
    walk(fig, (n) => inner.push(n));
    const video = inner.find((e) => e.tagName === 'video');
    const bar = inner.find((e) => (attr(e, 'class') ?? '').includes('player-bar'));
    const cover = inner.find((e) => attr(e, 'data-cover') !== undefined);
    if (!video) fail(`${label}: a [data-player] figure has no <video>.`);
    else if (attr(video, 'controls') === undefined) {
      fail(`${label}: player "${attr(video, 'id')}" ships without controls, so it has no no-script fallback.`);
    }
    if (!bar) fail(`${label}: player "${attr(video, 'id')}" has no control bar.`);
    else if (attr(bar, 'hidden') === undefined) {
      fail(`${label}: player "${attr(video, 'id')}" ships its control bar visible; it must start hidden.`);
    }
    if (cover && attr(cover, 'hidden') === undefined) {
      fail(`${label}: player "${attr(video, 'id')}" ships its poster overlay visible; it must start hidden.`);
    }
    if (!attr(fig, 'data-duration')) {
      fail(`${label}: player "${attr(video, 'id')}" has no data-duration, so its readout cannot work before load.`);
    }
  }

  for (const video of byTag('video')) {
    const src = textOf(video) || attr(video, 'id') || 'video';
    if (!attr(video, 'width') || !attr(video, 'height')) {
      fail(`${label}: <video> "${src}" has no width/height.`);
    }
    if (!attr(video, 'poster')) fail(`${label}: <video> "${src}" has no poster.`);
    const captions = (video.childNodes ?? []).filter((c) => c.tagName === 'track');
    const isMuted = attr(video, 'muted') !== undefined;
    if (!captions.length && !isMuted) {
      fail(`${label}: <video> "${src}" carries audio and has no caption track.`);
    }
    if (attr(video, 'controls') === undefined) {
      warn(`${label}: <video> "${src}" has no controls.`);
    }
  }

  // -- links ---------------------------------------------------------------
  const anchors = byTag('a');
  for (const a of anchors) {
    const href = attr(a, 'href');
    if (!href) {
      fail(`${label}: <a> with no href ("${textOf(a).slice(0, 30)}").`);
      continue;
    }

    // Every link needs a name a screen reader can read out.
    const name = textOf(a) || attr(a, 'aria-label') || '';
    if (!name.trim()) fail(`${label}: <a href="${href}"> has no accessible name.`);

    if (href.startsWith('#')) {
      const target = href.slice(1);
      if (target && !ids.has(target)) fail(`${label}: "${href}" points at an id that is not on the page.`);
      continue;
    }
    if (/^(https?:|mailto:|tel:)/.test(href)) {
      if (attr(a, 'target') === '_blank' && !(attr(a, 'rel') ?? '').includes('noopener')) {
        fail(`${label}: target="_blank" without rel="noopener" on ${href}.`);
      }
      continue;
    }
    // Local: it has to exist in dist.
    const local = join(DIST, href.split('#')[0].split('?')[0]);
    if (!existsSync(local)) fail(`${label}: broken local link ${href}.`);
  }

  // -- every local asset reference ----------------------------------------
  for (const el of els) {
    for (const name of ['src', 'poster']) {
      const value = attr(el, name);
      if (!value || /^(https?:|data:|#)/.test(value)) continue;
      if (!existsSync(join(DIST, value.split('?')[0]))) {
        fail(`${label}: <${el.tagName} ${name}="${value}"> does not exist in dist.`);
      }
    }
    if (el.tagName === 'link') {
      const href = attr(el, 'href');
      if (href && !/^(https?:|data:|#)/.test(href) && !existsSync(join(DIST, href.split('?')[0]))) {
        fail(`${label}: <link href="${href}"> does not exist in dist.`);
      }
    }
    if (el.tagName === 'source') {
      const srcset = attr(el, 'srcset');
      for (const candidate of (srcset ?? '').split(',')) {
        const url = candidate.trim().split(/\s+/)[0];
        if (!url || /^(https?:|data:)/.test(url)) continue;
        if (!existsSync(join(DIST, url))) fail(`${label}: srcset entry ${url} does not exist in dist.`);
      }
    }
  }

  // -- buttons -------------------------------------------------------------
  for (const button of byTag('button')) {
    if (!(textOf(button) || attr(button, 'aria-label'))) {
      fail(`${label}: <button> has no accessible name.`);
    }
  }

  // -- weight --------------------------------------------------------------
  const bytes = statSync(file).size;
  if (bytes > 200_000) warn(`${label}: ${(bytes / 1024).toFixed(0)} kB of HTML.`);
  else ok(`${label}: ${(bytes / 1024).toFixed(0)} kB of HTML.`);
}

// -------------------------------------------------------------- site files

for (const required of ['robots.txt', 'sitemap.xml', 'site.webmanifest', 'favicon.svg']) {
  if (!existsSync(join(DIST, required))) fail(`dist/${required} is missing.`);
}

const robots = existsSync(join(DIST, 'robots.txt'))
  ? readFileSync(join(DIST, 'robots.txt'), 'utf8')
  : '';
if (/^Disallow: \/$/m.test(robots)) {
  warn('robots.txt is serving Disallow: / because SITE_URL is still the placeholder.');
} else if (!/^Sitemap:/m.test(robots)) {
  fail('robots.txt has no Sitemap line.');
}

try {
  JSON.parse(readFileSync(join(DIST, 'site.webmanifest'), 'utf8'));
  ok('site.webmanifest parses.');
} catch (err) {
  fail(`site.webmanifest does not parse (${err.message}).`);
}

// The whole point of a static build is that the first load is one document.
const scripts = htmlFiles
  .map((f) => readFileSync(f, 'utf8'))
  .join('')
  .match(/<script(?![^>]*ld\+json)[^>]*>/g);
const jsBytes = files
  .filter((f) => f.endsWith('.js'))
  .reduce((total, f) => total + statSync(f).size, 0);
if (jsBytes > 30_000) warn(`${(jsBytes / 1024).toFixed(1)} kB of JavaScript in dist.`);
else ok(`${(jsBytes / 1024).toFixed(1)} kB of external JavaScript, ${scripts?.length ?? 0} script tag(s).`);

// -------------------------------------------------------------------- report

const pad = (s) => '  ' + s;
if (passes.length) {
  console.log('\nPASS');
  console.log(passes.map(pad).join('\n'));
}
if (warnings.length) {
  console.log('\nWARN');
  console.log(warnings.map(pad).join('\n'));
}
if (errors.length) {
  console.log('\nFAIL');
  console.log(errors.map(pad).join('\n'));
  console.log(`\n${errors.length} error(s), ${warnings.length} warning(s).`);
  process.exit(1);
}
console.log(`\nAll checks passed. ${warnings.length} warning(s).`);
