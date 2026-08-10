#!/usr/bin/env node
/**
 * Write a .br and a .gz next to every compressible file in dist/.
 *
 *   node scripts/precompress.mjs
 *   node scripts/precompress.mjs --check    # verify only, non-zero if missing
 *
 * ## Why this exists
 *
 * Nginx can compress on the fly, and for a page this size that is the wrong
 * trade twice over. Brotli at the quality that matters costs real CPU, so the
 * on-the-fly setting nobody regrets is about quality 4, and it pays that cost
 * again on every single request for a file that changes once a release.
 *
 * Compressing once at build time buys the quality-11 number instead of the
 * quality-4 one, and buys it at zero request cost. On this build that is the
 * difference between a 154 kB document and a 26 kB one:
 *
 *   index.html    154 kB  ->  gzip 34 kB  ->  brotli 26 kB
 *
 * `gzip_static on` and `brotli_static on` make Nginx prefer these files and fall
 * back to compressing live when one is absent, so a file this script skips is
 * still served compressed, just less well. Nothing here is load-bearing for
 * correctness, which is the property that makes it safe to run last.
 *
 * ## Why both
 *
 * Brotli is smaller and is supported by every browser that matters. gzip is for
 * everything else that speaks HTTP: curl without `--compressed`, old corporate
 * middleboxes, and the Cloudflare-to-origin hop when the edge asks for gzip.
 * Both are cheap to keep because both are written once.
 */
import { readdirSync, readFileSync, writeFileSync, statSync, existsSync, utimesSync } from 'node:fs';
import { join, resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync, brotliCompressSync, constants } from 'node:zlib';

const SITE = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DIST = join(SITE, 'dist');
const check = process.argv.includes('--check');

/**
 * What is worth compressing.
 *
 * Everything absent from this list is already compressed by its own container:
 * woff2 carries Brotli inside the font, and mp4, webp, jpg and png are all
 * lossy-coded already. Running Brotli over an mp4 spends 6 MB of CPU to make the
 * file about 0.2 per cent smaller, and Nginx would then have to hold both.
 */
const COMPRESSIBLE = new Set([
  '.html',
  '.css',
  '.js',
  '.mjs',
  '.json',
  '.webmanifest',
  '.xml',
  '.txt',
  '.svg',
  '.vtt',
  '.map',
]);

/**
 * Below this, skip.
 *
 * A single TCP segment carries about 1.4 kB, so shaving 40 bytes off a 200 byte
 * file saves no round trip and costs a filesystem probe on every request for it.
 * robots.txt is the case that proves it: 67 bytes raw, and 94 bytes gzipped.
 */
const MIN_BYTES = 1024;

/** Text mode tells Brotli to use its text context model, which is worth a per cent or two. */
const brotli = (buf) =>
  brotliCompressSync(buf, {
    params: {
      [constants.BROTLI_PARAM_QUALITY]: constants.BROTLI_MAX_QUALITY,
      [constants.BROTLI_PARAM_MODE]: constants.BROTLI_MODE_TEXT,
      [constants.BROTLI_PARAM_SIZE_HINT]: buf.length,
    },
  });

const gzip = (buf) => gzipSync(buf, { level: 9 });

function allFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...allFiles(p));
    else out.push(p);
  }
  return out;
}

if (!existsSync(DIST)) {
  console.error('No dist/. Run `npm run build` first.');
  process.exit(1);
}

const rows = [];
const missing = [];
let rawTotal = 0;
let brTotal = 0;
let gzTotal = 0;
let skipped = 0;

for (const file of allFiles(DIST)) {
  const ext = extname(file);
  if (ext === '.br' || ext === '.gz') continue;
  if (!COMPRESSIBLE.has(ext)) continue;

  const raw = readFileSync(file);
  if (raw.length < MIN_BYTES) {
    skipped += 1;
    continue;
  }

  const { mtime, atime } = statSync(file);
  const label = file.replace(DIST + '/', '');
  const encoded = { br: brotli(raw), gz: gzip(raw) };
  const written = {};

  for (const [kind, buf] of Object.entries(encoded)) {
    const target = `${file}.${kind}`;

    // Never write a compressed file that is not smaller. Nginx would serve it
    // in preference to the original and every client would pay for the privilege
    // of receiving more bytes and then having to decode them.
    if (buf.length >= raw.length) continue;

    if (check) {
      if (!existsSync(target)) missing.push(`${label}.${kind}`);
      written[kind] = buf.length;
      continue;
    }

    writeFileSync(target, buf);

    // Match the source's timestamps. Nginx derives Last-Modified and the ETag
    // from whichever file it actually serves, so leaving these at "now" makes a
    // document's freshness depend on which encoding a client negotiated.
    utimesSync(target, atime, mtime);
    written[kind] = buf.length;
  }

  rawTotal += raw.length;
  brTotal += written.br ?? raw.length;
  gzTotal += written.gz ?? raw.length;
  rows.push({ label, raw: raw.length, ...written });
}

// ------------------------------------------------------------------- report

const kb = (n) => `${(n / 1024).toFixed(1)} kB`;
const pct = (from, to) => `${(100 - (to / from) * 100).toFixed(0)}%`;

rows.sort((a, b) => b.raw - a.raw);
for (const r of rows.slice(0, 12)) {
  console.log(
    `  ${r.label.padEnd(52)} ${kb(r.raw).padStart(9)}` +
      `  gz ${kb(r.gz ?? r.raw).padStart(8)}` +
      `  br ${kb(r.br ?? r.raw).padStart(8)}  ${pct(r.raw, r.br ?? r.raw).padStart(4)}`,
  );
}
if (rows.length > 12) console.log(`  ... and ${rows.length - 12} more`);

if (check && missing.length) {
  console.error(`\n  ! ${missing.length} precompressed file(s) missing:`);
  console.error(missing.map((m) => `    ${m}`).join('\n'));
  console.error('\nRun `npm run precompress`.');
  process.exit(1);
}

console.log(
  `\n${check ? 'Verified' : 'Compressed'} ${rows.length} file(s), ${skipped} under ${MIN_BYTES} B skipped.` +
    `\n${kb(rawTotal)} raw  ->  ${kb(gzTotal)} gzip  ->  ${kb(brTotal)} brotli` +
    `  (${pct(rawTotal, brTotal)} off the wire)`,
);
