/**
 * Screen assets, with the render's own ground and drawn frame taken off them.
 *
 * ## Why this exists
 *
 * The screenshots under `src/assets/screens/` are the ones the README and the
 * promo pipeline produce, and they are composed for those: opaque rasters with a
 * blue-black gradient baked in behind a drawn phone shell.
 * `docs/screens/hero-b.png` measures `#070910` in the corners and rises to about
 * `#1E2850` at the top edge.
 *
 * That gradient is a design frame, and laying the page out around it meant the
 * page was arranging somebody else's composition: a rounded blue rectangle
 * sitting on a black document, with a second frame drawn inside it. It read as a
 * cropped README asset rather than as a product object on this page.
 *
 * So this takes the raster apart. What comes out is only the pixels the app
 * itself drew. Every frame, rim, bezel, shadow, radius and ground around them
 * belongs to `global.css`, where it can answer to the section it is in.
 *
 * ## Nothing here is measured by eye
 *
 * Two of the sources are contact sheets: `create-monitor.png` is the four wizard
 * steps side by side and `group.png` is three phones on one plate. The phones on
 * them are found rather than cut at hardcoded offsets, which is the part worth
 * writing down, because the hardcoded version had drifted: the wizard boxes
 * stepped a fixed 562 px and the fourth phone had genuinely moved, so step four
 * shipped with its right hand edge sliced off and nothing said so.
 *
 * The ground is found by flooding inward from the four corners, and a
 * neighbouring pixel joins it only if it is within `TOLERANCE` of the pixel it
 * was reached from. A local test rather than a global one, on purpose: the
 * gradient steps by a level or two per pixel so the flood crosses all of it, and
 * it stops dead at the edge of a phone or a card, which is a step of thirty or
 * more. What the flood could not reach is then labelled into connected pieces,
 * and the pieces that are taller than they are wide and big enough to be a
 * device are the phones, left to right.
 *
 * Each phone is then cropped to its screen. The shell is measured, not assumed:
 * from each edge of the phone the scan walks inward across the bright hairline
 * the render draws and the crop starts where that hairline ends.
 *
 * ## It is safe to re-run and it edits nothing
 *
 * Sources stay exactly as `sync-assets.mjs` wrote them. Output goes to
 * `src/assets/screens/clean/`. Re-shoot a screen, run `npm run assets` and then
 * `npm run screens`, and every derived asset follows without a number in this
 * file changing.
 *
 * Every pixel the flood reached is written at alpha 0, so all four corners of
 * every output are empty and the page's own background is what shows through
 * them. `--check` asserts exactly that and `npm run verify` runs it, so a ground
 * cannot get baked back in without the build saying so.
 */

import { existsSync, mkdirSync, readdirSync, unlinkSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const SITE = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SRC_DIR = join(SITE, 'src/assets/screens');
const OUT_DIR = join(SRC_DIR, 'clean');

const CHECK = process.argv.includes('--check');

/**
 * How far apart two touching pixels may be and still count as the same ground.
 *
 * The gradient moves a level or two per pixel, so it floods at any tolerance at
 * all. What has to stop the flood is the phone shell hairline, a step of about
 * 50, and the widget card border, about 20. Anything under 15 works; 8 leaves
 * room for both.
 */
const TOLERANCE = 8;

/**
 * Below this a pixel is more transparent than it is opaque, and is ground.
 *
 * Half, rather than a hair above zero, because of what the two sources with an
 * alpha channel actually contain: a soft drop shadow, painted as black at an
 * alpha that ramps up from 8 over about forty pixels. That shadow is a design
 * effect baked into the asset, which is the whole category of thing this script
 * exists to take off, and at a threshold of 8 it counted as subject and dragged
 * the crop box out with it.
 */
const CLEAR = 128;

/**
 * The ground in these renders is a blue gradient and the objects on it are
 * neutral. `plate` sources need that said out loud, because the widget cards are
 * `#101010` against a `#0A0D18` ground: a step of 8, which the distance test on
 * its own cannot tell from the gradient's own step, so it floods straight
 * through the card and out the other side.
 *
 * Only the ground has to satisfy this. Everything inside a card, including the
 * blue mark at the top of it, is unreachable once the card edge has stopped the
 * flood, so the app's own blue is never at risk from it.
 */
const COOL = 4;

/**
 * `sheet` sources hold several phones and are labelled into pieces. `phone` and
 * `plate` sources hold one subject. A `null` in a sheet's list is a phone that is
 * on the plate and deliberately not taken from it: the middle phone of
 * `group.png` is the urgent home screen, and `docs/screens/urgent-b.png` is the
 * same screen shot on its own at a higher resolution.
 */
const SOURCES = [
  {
    from: 'create-monitor.png',
    mode: 'sheet',
    group: 'wizard',
    out: ['wizard-1', 'wizard-2', 'wizard-3', 'wizard-4'],
  },
  { from: 'group.png', mode: 'sheet', group: 'phone', out: ['monitors', null, 'detail'] },
  { from: 'urgent.png', mode: 'phone', group: 'phone', out: ['urgent'] },
  { from: 'pager-setup.png', mode: 'phone', group: 'phone', out: ['pager-setup'] },
  // The same widget at its three sizes, laid out side by side on one plate.
  { from: 'widget.png', mode: 'plate', out: ['widget-tall', 'widget-wide', 'widget-flat'] },
];

const NAMES = SOURCES.flatMap((s) => s.out).filter(Boolean);

const lum = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;

/**
 * Flood the ground inward from the four corners.
 *
 * Returns one byte per pixel, 1 where the pixel is ground. Iterative rather than
 * recursive: the largest of these is 3.3 million pixels and a recursive fill
 * would blow the stack on the first one.
 */
function floodGround(data, w, h, cool) {
  const ground = new Uint8Array(w * h);
  const stack = new Int32Array(w * h);
  let top = 0;

  const push = (i) => {
    if (ground[i]) return;
    ground[i] = 1;
    stack[top++] = i;
  };

  // Anything already transparent is ground before the flood starts, which is
  // what lets the two sources that ship with an alpha channel take the same path
  // as the three that do not.
  for (let i = 0; i < w * h; i += 1) {
    if (data[i * 4 + 3] < CLEAR) push(i);
  }

  push(0);
  push(w - 1);
  push((h - 1) * w);
  push(h * w - 1);

  while (top > 0) {
    const i = stack[--top];
    const x = i % w;
    const y = (i / w) | 0;
    const o = i * 4;

    const step = (nx, ny) => {
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) return;
      const j = ny * w + nx;
      if (ground[j]) return;
      const p = j * 4;
      if (data[p + 3] < CLEAR) {
        push(j);
        return;
      }
      if (cool && data[p + 2] - data[p] < COOL) return;
      const d = Math.max(
        Math.abs(data[p] - data[o]),
        Math.abs(data[p + 1] - data[o + 1]),
        Math.abs(data[p + 2] - data[o + 2]),
      );
      if (d <= TOLERANCE) push(j);
    };

    step(x - 1, y);
    step(x + 1, y);
    step(x, y - 1);
    step(x, y + 1);
  }

  return ground;
}

/** Every connected run of not-ground, as a bounding box with its area. */
function pieces(ground, w, h) {
  const seen = new Uint8Array(w * h);
  const stack = new Int32Array(w * h);
  const found = [];

  for (let start = 0; start < w * h; start += 1) {
    if (ground[start] || seen[start]) continue;
    let top = 0;
    stack[top++] = start;
    seen[start] = 1;
    let left = w;
    let right = -1;
    let boxTop = h;
    let bottom = -1;
    let area = 0;

    while (top > 0) {
      const i = stack[--top];
      const x = i % w;
      const y = (i / w) | 0;
      area += 1;
      if (x < left) left = x;
      if (x > right) right = x;
      if (y < boxTop) boxTop = y;
      if (y > bottom) bottom = y;

      const step = (nx, ny) => {
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) return;
        const j = ny * w + nx;
        if (ground[j] || seen[j]) return;
        seen[j] = 1;
        stack[top++] = j;
      };

      step(x - 1, y);
      step(x + 1, y);
      step(x, y - 1);
      step(x, y + 1);
    }

    found.push({ left, top: boxTop, right, bottom, area });
  }

  return found;
}

/**
 * How thick the drawn shell is on one edge of a phone.
 *
 * The render draws the phone the same way on every plate, and a profile across
 * any edge says so: a bright `#3A3D47` hairline at luminance 55 to 61 for a
 * pixel or two, one blend pixel, then eight or nine pixels of flat `#0A0A0C`
 * body, then the screen. So the measurement is that structure walked in order,
 * rather than a brightness test that has to work on both.
 *
 * The blend pixel matters more than it looks. Sampling the body colour where
 * the hairline fades into it gives a colour that belongs to neither, the run
 * ends on the next pixel, and the answer comes back as three: which is what
 * shipped, and it is why every screen on the page had the phone's own grey rim
 * still drawn around it inside the stylesheet's bezel.
 *
 * One scan line is not enough either. The old single line down the middle of
 * each edge measured the hero plate's left edge at 3 and its right edge at 10,
 * so the same phone was cropped two different ways. This walks a spread of
 * lines and takes the median, so one line crossing a control that happens to be
 * the body's colour cannot move the answer.
 */
function shellInset(readAt, span) {
  const LIMIT = Math.min(26, Math.max(4, Math.floor(span / 5)));
  const LINES = 11;
  const found = [];

  for (let n = 0; n < LINES; n += 1) {
    // Across the middle half of the edge. The ends of an edge are arc, not
    // straight run, and the shell is thicker there for a reason that is
    // geometry rather than shell.
    const line = 0.25 + (0.5 * n) / (LINES - 1);
    let k = 0;

    // The hairline, and whatever of it is antialiasing.
    while (k < LIMIT && lum(...readAt(line, k)) > 18) k += 1;
    // One more: where the hairline meets the body the pixel is a blend of the
    // two, and a run measured from a blend is one pixel long.
    k += 1;

    // The body: the flat run that ends where the screen starts.
    const body = readAt(line, Math.min(k, LIMIT - 1));
    while (k < LIMIT) {
      const p = readAt(line, k);
      const d = Math.max(
        Math.abs(p[0] - body[0]),
        Math.abs(p[1] - body[1]),
        Math.abs(p[2] - body[2]),
      );
      // Three levels, not six. The setup screen's own background is `#060606`
      // against a `#0A0A0C` body, and at six they are the same colour and the
      // run walks straight out into the screenshot.
      if (d > 3) break;
      k += 1;
    }

    found.push(k);
  }

  found.sort((a, b) => a - b);
  // Plus one, because the last pixel of the run is still a blend of body and
  // screen. A raster pixel here is about 0.6 of a page pixel, so spending one
  // is invisible and leaving one is the grey hairline this whole function is
  // for.
  return Math.max(2, Math.min(LIMIT, found[(LINES / 2) | 0] + 1));
}

/**
 * The radius of the corner arc the crop inherits, in crop pixels.
 *
 * The phone is a rounded rectangle, so cropping to its screen leaves four
 * corners that are still shell. How deep depends on the render's own corner
 * radius, which is measured here off the flood rather than guessed: walk down
 * the outer edge of the phone and the first row that is not ground is where the
 * arc meets the straight run. Take the inset off that and what is left is the
 * radius the crop needs masking at.
 *
 * Guessed, it was 25 to 28 against an arc of 32 to 40, which is why the corners
 * kept a grey crescent in them after everything else was clean.
 */
function cornerRadius(ground, w, phone, inset) {
  const reach = (along, across) => {
    for (let i = 0; i < 90; i += 1) {
      if (!ground[across(i)]) return i;
    }
    return 0;
  };

  const arcs = [
    reach(0, (i) => (phone.top + i) * w + phone.left),
    reach(0, (i) => (phone.top + i) * w + phone.right),
    reach(0, (i) => (phone.bottom - i) * w + phone.left),
    reach(0, (i) => (phone.bottom - i) * w + phone.right),
  ];

  const outer = Math.max(...arcs);
  return Math.max(0, outer - inset);
}

/**
 * Take the four corner arcs out, with an edge rather than a staircase.
 *
 * Coverage instead of in-or-out: a pixel whose centre is `d` from the corner
 * circle keeps `r + 0.5 - d` of its alpha, clamped. At the scale these are
 * drawn at, a hard test leaves a visible stair on every corner of every screen,
 * and the whole reason the mask exists is that a corner should not be something
 * you can see the making of.
 */
function roundedMask(buf, w, h, r) {
  if (r < 1) return;
  for (let y = 0; y < h; y += 1) {
    for (let x = 0; x < w; x += 1) {
      const px = x + 0.5;
      const py = y + 0.5;
      const cx = px < r ? r : px > w - r ? w - r : px;
      const cy = py < r ? r : py > h - r ? h - r : py;
      if (cx === px || cy === py) continue;
      const d = Math.hypot(px - cx, py - cy);
      const cover = Math.min(1, Math.max(0, r + 0.5 - d));
      if (cover >= 1) continue;
      const o = (y * w + x) * 4;
      buf[o + 3] = Math.round(buf[o + 3] * cover);
      if (buf[o + 3] === 0) {
        buf[o] = 0;
        buf[o + 1] = 0;
        buf[o + 2] = 0;
      }
    }
  }
}

/** Cut one box out of the raw buffer, knocking out every pixel the flood reached. */
function cut(data, ground, w, box, name) {
  const cw = box.right - box.left + 1;
  const ch = box.bottom - box.top + 1;
  if (cw < 32 || ch < 32) throw new Error(`${name}: crop collapsed to ${cw}x${ch}`);

  const out = Buffer.alloc(cw * ch * 4);
  let cleared = 0;
  for (let y = 0; y < ch; y += 1) {
    for (let x = 0; x < cw; x += 1) {
      const i = (box.top + y) * w + (box.left + x);
      if (ground[i]) {
        cleared += 1;
        continue; // leaves 0, 0, 0, 0
      }
      const src = i * 4;
      const dst = (y * cw + x) * 4;
      out[dst] = data[src];
      out[dst + 1] = data[src + 1];
      out[dst + 2] = data[src + 2];
      out[dst + 3] = data[src + 3];
    }
  }

  return { buf: out, w: cw, h: ch, cleared };
}

/**
 * Write one measured crop, padded to whatever its group settled on.
 *
 * The group size is the largest crop in the group, so this only ever pads. The
 * version before it padded to a size typed into this file: four numbers tuned
 * against crops that were themselves wrong, which centred every phone inside a
 * dozen pixels of nothing and then centre-cropped anything that did not fit.
 * The widget the page says you can read at a glance shipped with the bottom of
 * its own last line cut off, and no number in this file could say so.
 */
async function emit(piece, target, name) {
  let { buf, w: fw, h: fh } = piece;

  if (target && (target.w !== fw || target.h !== fh)) {
    if (target.w < fw || target.h < fh) {
      throw new Error(`${name}: ${fw}x${fh} does not fit ${target.w}x${target.h}`);
    }
    const canvas = Buffer.alloc(target.w * target.h * 4);
    const dx = (target.w - fw) >> 1;
    const dy = (target.h - fh) >> 1;
    for (let y = 0; y < fh; y += 1) {
      buf.copy(canvas, ((dy + y) * target.w + dx) * 4, y * fw * 4, (y + 1) * fw * 4);
    }
    buf = canvas;
    fw = target.w;
    fh = target.h;
  }

  roundedMask(buf, fw, fh, piece.radius);

  await sharp(buf, { raw: { width: fw, height: fh, channels: 4 } })
    .png({ compressionLevel: 9, effort: 10 })
    .toFile(join(OUT_DIR, `${name}.png`));

  return {
    name,
    size: `${fw}x${fh}`,
    radius: piece.radius,
    clearedPct: ((piece.cleared / (piece.w * piece.h)) * 100).toFixed(1),
  };
}

async function derive({ from, mode, group, out }) {
  const { data, info } = await sharp(join(SRC_DIR, from))
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  const w = info.width;
  const h = info.height;
  const ground = floodGround(data, w, h, mode === 'plate');
  const all = pieces(ground, w, h);
  if (!all.length) throw new Error(`${from}: the flood reached every pixel`);

  if (mode === 'plate') {
    /*
      One object per file, not one file with three objects on it.

      The widget shot is the same widget at its three sizes laid out side by
      side, and as a single raster it can only ever be as wide as the column it
      is in. On a phone that made all three of them about a hundred pixels
      across and the type in them unreadable, which is a strange thing to do to
      the one asset whose whole subject is a list you are supposed to be able to
      read at a glance. Cut apart, the page stacks them where there is no room
      for a row.
    */
    const objects = all
      .filter((p) => p.area > w * h * 0.004)
      .sort((a, b) => a.left - b.left);
    if (objects.length !== out.length) {
      throw new Error(`${from}: found ${objects.length} objects, expected ${out.length}`);
    }
    // A widget is the card itself, so the flood has already taken its corners
    // off and there is no shell around it to measure or mask.
    return objects.map((object, i) => ({
      name: out[i],
      group,
      ...cut(data, ground, w, object, out[i]),
      radius: 0,
    }));
  }

  // A phone is tall and it is a large part of the plate. Everything else the
  // labeller finds on a contact sheet, wordmarks included, is one or the other of
  // those things and not both.
  const phones = all
    .filter((p) => p.area > w * h * 0.02)
    .filter((p) => p.bottom - p.top > (p.right - p.left) * 1.4)
    .sort((a, b) => a.left - b.left);

  if (phones.length !== out.length) {
    throw new Error(
      `${from}: found ${phones.length} phones, expected ${out.length}. ` +
        `Boxes: ${JSON.stringify(phones.map((p) => [p.left, p.top, p.right, p.bottom]))}`,
    );
  }

  const found = [];
  for (const [i, phone] of phones.entries()) {
    const name = out[i];
    if (!name) continue;
    const at = (x, y) => {
      const o = (y * w + x) * 4;
      return [data[o], data[o + 1], data[o + 2]];
    };
    const span = { x: phone.right - phone.left, y: phone.bottom - phone.top };
    // `line` is a fraction of the edge, so each of the four reads the same
    // spread of scan lines and the four insets are comparable.
    const down = (t) => phone.top + Math.round(span.y * t);
    const across = (t) => phone.left + Math.round(span.x * t);
    const insets = {
      left: shellInset((t, k) => at(phone.left + k, down(t)), span.x),
      right: shellInset((t, k) => at(phone.right - k, down(t)), span.x),
      top: shellInset((t, k) => at(across(t), phone.top + k), span.y),
      bottom: shellInset((t, k) => at(across(t), phone.bottom - k), span.y),
    };
    const box = {
      left: phone.left + insets.left,
      right: phone.right - insets.right,
      top: phone.top + insets.top,
      bottom: phone.bottom - insets.bottom,
    };
    const radius = cornerRadius(ground, w, phone, Math.min(...Object.values(insets)));
    found.push({ name, group, ...cut(data, ground, w, box, name), radius });
  }
  return found;
}

/**
 * What a finished asset has to be true of.
 *
 * The whole point is that the page decides what sits behind the product UI, so
 * the check that says so is that the corners are empty. If a ground ever gets
 * baked back in, all four go opaque at once and this fails rather than quietly
 * shipping a blue rectangle again.
 */
async function verify() {
  const problems = [];
  for (const name of NAMES) {
    const file = join(OUT_DIR, `${name}.png`);
    if (!existsSync(file)) {
      problems.push(`${name}: missing. Run \`npm run screens\`.`);
      continue;
    }
    const { data, info } = await sharp(file)
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true });
    const w = info.width;
    const h = info.height;
    const alpha = (x, y) => data[(y * w + x) * 4 + 3];
    const corners = [alpha(0, 0), alpha(w - 1, 0), alpha(0, h - 1), alpha(w - 1, h - 1)];
    if (corners.some((a) => a > 0)) {
      problems.push(`${name}: corner alpha ${corners.join(', ')}, expected all 0`);
    }
    // And the middle of it still has to be a picture rather than a hole.
    if (alpha((w / 2) | 0, (h / 2) | 0) < 200) {
      problems.push(`${name}: the middle is transparent, so the flood leaked into the subject`);
    }
  }
  return problems;
}

if (CHECK) {
  const problems = await verify();
  if (problems.length) {
    console.error('\n  Derived screen assets are not clean:\n');
    for (const p of problems) console.error(`  ! ${p}`);
    console.error('');
    process.exit(1);
  }
  console.log(`\n  ${NAMES.length} derived screen assets, all with transparent corners.\n`);
} else {
  mkdirSync(OUT_DIR, { recursive: true });
  // Drop anything no longer in the list, so a renamed source cannot leave a
  // stale asset behind that a component is still importing.
  const keep = new Set(NAMES.map((n) => `${n}.png`));
  for (const file of readdirSync(OUT_DIR)) {
    if (!keep.has(file)) unlinkSync(join(OUT_DIR, file));
  }

  // Measured first, written second. A group's size is the largest crop in it,
  // which cannot be known until every crop in it has been measured, and it is
  // the only way padding can be guaranteed never to become cropping.
  const pieces = [];
  for (const source of SOURCES) pieces.push(...(await derive(source)));

  const targets = new Map();
  for (const p of pieces) {
    if (!p.group) continue;
    const at = targets.get(p.group) ?? { w: 0, h: 0 };
    targets.set(p.group, { w: Math.max(at.w, p.w), h: Math.max(at.h, p.h) });
  }

  const rows = [];
  for (const p of pieces) rows.push(await emit(p, targets.get(p.group), p.name));

  console.log('');
  for (const r of rows) {
    console.log(
      `  ${r.name.padEnd(12)} ${r.size.padStart(9)}  r${String(r.radius).padStart(3)}  ` +
        `${String(r.clearedPct).padStart(5)}% cleared`,
    );
  }

  const problems = await verify();
  if (problems.length) {
    console.error('\n  Written, but not clean:\n');
    for (const p of problems) console.error(`  ! ${p}`);
    console.error('');
    process.exit(1);
  }
  console.log(`\n  ${rows.length} assets written to src/assets/screens/clean/.\n`);
}
