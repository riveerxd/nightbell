# Nightbell brand assets

Thirty logo directions, each as a square icon plate and a landscape wordmark lockup.
Open `logos.html` in a browser for the presentation sheet with rationale, size
tests and notes.

## Files

| File | Canvas | Use |
| --- | --- | --- |
| `nightbell-NN-slug-icon.svg` (01–30) | 512 × 512, `rx=112` | Launcher icon, favicon, avatar |
| `nightbell-NN-slug-lockup.svg` | 1130 × 320 | README header, in-app title, docs |
| `png/*-512.png`, `png/*-192.png` | — | Raster icon exports |
| `png/*-lockup.png` | 1760 × 640 | Raster lockup exports |

The thirty `-icon.svg` files are the only hand-authored source. Lockups, PNGs and
`logos.html` are generated:

```bash
python3 docs/brand/build.py          # lockups + logos.html
python3 docs/brand/build.py --png    # also re-export png/ (needs rsvg-convert)
```

## The directions

| # | Name | In one line |
| --- | --- | --- |
| 01 | Cardiogram | Flatline, then a red spike. Safest launcher icon; least distinctive. |
| 02 | Ping | A check leaving the phone. Best silhouette; generic without the red core. |
| 03 | Monogram | P with the ping in its counter. Scales to 16px; says least about uptime. |
| 04 | Red Card | The page you cannot swipe away. Loudest in a grid; red plate spends the alert colour. |
| 05 | Bars | Latency, with the outage in red. Ties to the charts; reads as audio. |
| 06 | Nightwatch | For the 3am promise. Most memorable; least literal. |
| 07 | Shield | Assurance framing. Immediately understood; crowded category. |
| 08 | Board | Nine monitors, one down. Explains the product; soft at 40px. |
| 09 | Sweep | Actively checking. Conveys motion; busy centre. |
| 10 | Gauge | Uptime as a ring, downtime as the notch. Most brand-like; needs the wordmark. |
| 11 | Tower | Transmitting, not measuring. Clear category signal; reads telecom. |
| 12 | Minute | The minute you lost. Ties to quiet hours; overloaded shape on a phone. |
| 13 | Beeper | The device this replaces. Most characterful; needs 96px to land. |
| 14 | Pixel | Monogram on a 3 × 6 grid. Distinctive letterform; fights round icon masks. |
| 15 | Slab | Brutalist P, red foot. Best wordmark pairing; no uptime metaphor at all. |
| 16 | Step | Uptime as a staircase. Reads as data; awkward in a square. |
| 17 | Bell | The alarm, and its clapper. Instantly understood; collides with system icons. |
| 18 | Prompt | For the person who deploys the thing. Right audience; narrow appeal. |
| 19 | Eye | The watcher. Strong metaphor; surveillance overtones. |
| 20 | Stack | Three services, top one down. Infra-native; close to a menu icon. |
| 21 | Sine | Rhythm, then the anomaly. Best storytelling; needs the full width. |
| 22 | Segments | Six monitors, one red. Encodes the count; gaps close up when small. |
| 23 | Bolt | The surge that breaks the line. Most energetic; says power, not uptime. |
| 24 | Bracket | The thing being watched, in brackets. Ages well; anonymous unaccompanied. |
| 25 | Fan | Latency as a gauge. Feels like an instrument; implies a reading to interpret. |
| 26 | Orbit | A check going round. Best animation hook; reads as space. |
| 27 | Rows | The list, with one row down. Obviously the product; least logo-like. |
| 28 | Crosshair | What it is pointed at. Very strong silhouette; targeting overtones. |
| 29 | Halftone | The trace, in beads. Most crafted; beads merge below 48px. |
| 30 | Ring Nightbell | The trace cutting through the ring. Best badge form; busiest centre. |

## Colour

| Token | Hex | Role |
| --- | --- | --- |
| Ink | `#0B0E13` | plate, ground |
| Bone | `#F2F5F8` | glyph, wordmark |
| Signal | `#2FD98A` | up, healthy, checking |
| Alarm | `#FF4D57` | down — appears exactly once per mark |
| Line | `#1E2530` | rules, dividers |

A light-ground variant is a two-value swap: plate `#0B0E13` → `#F2F5F8`, glyph
`#F2F5F8` → `#0B0E13`. Green and red are legible on both and stay as they are.

## What the app actually ships

Direction **30 (Ring Nightbell)** is the chosen mark. In the app it lives in five
places, all generated from `nightbell-30-ringpulse-icon.svg` by
`android_assets.py` — run that, not a text editor, when the geometry changes:

| Where | File |
| --- | --- |
| Launcher icon | `res/drawable/ic_launcher_mark.xml` (legacy, transparent) |
| Widget header | `res/drawable/ic_widget_mark.xml` |
| Notification silhouette | `res/drawable/ic_stat_brand.xml` |
| Adaptive foreground / themed icon | `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml` (kept, unused) |
| In-app header and widget preview | `ui/components/BrandMark.kt` |

Two things worth knowing before touching any of them:

- **The ring is two arcs with a real gap**, not a circle with the gap faked by a
  casing stroke in the plate colour. The faked version is why the plate could not
  be removed — it showed up as a dark scar the moment the background went clear.
- **The launcher icon is deliberately not adaptive.**
  `AdaptiveIconDrawable.draw()` fills its layer bitmap with `Color.BLACK` before
  compositing, so an adaptive icon can never have a transparent background. See
  `docs/reference.md` → "The launcher icon" for the full finding, and
  `LauncherIconInstrumentedTest` for the tests that hold it in place.

Green `#2FD98A` and red `#FF4D57` are also the app's `Mint` and `Rose` status
colours, which is why the mark needs no palette of its own.

The app's own colour is still blue. Green was briefly promoted to the brand family
so the whole UI would match the mark, and that went too far: with chrome and status
sharing one hue, a button, a chip and a healthy monitor were all the same colour and
green stopped meaning anything in particular. Blue is the app; green is reserved for
the thing it measures — the sparkline, the history strip and the latency bars, all of
which still bleed to red at a failed check.

## Before shipping one

- **Outline the wordmark.** The lockups name their intended faces and fall back to
  a system sans/serif/mono, so they will not render identically everywhere.
  Roughly two thirds want a tight geometric sans; 06, 19 and 26 an old-style serif;
  08, 09, 12, 14, 16, 18, 22, 24 and 28 a mono.
- **Inset for adaptive icons.** The plates are full-bleed. A foreground layer has
  to survive a circular mask, so any mark running close to the plate edge — 02, 03,
  08, 10, 12, 14, 15, 16, 21, 28, 29 — needs roughly 15% inset before going into
  `ic_launcher_foreground`. 04 already reads under any mask shape.

Lockup placement is measured, not eyeballed: `build.py` renders each mark, trims it
to its real ink bounds (cached in `bbox.json`) and fits all thirty to the same
optical box, so no lockup looks lighter or heavier than its neighbours.
